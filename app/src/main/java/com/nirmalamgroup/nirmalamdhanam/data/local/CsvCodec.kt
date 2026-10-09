package com.nirmalamgroup.nirmalamdhanam.data.local

/**
 * Small RFC-4180-style CSV codec used by transaction import/export.
 *
 * Supported:
 * - comma delimiters
 * - quoted fields
 * - doubled quotes ("" -> ")
 * - CRLF and LF record separators
 * - embedded commas, CR/LF, and quotes inside quoted fields
 * - empty fields and empty records
 *
 * The parser is deliberately strict about malformed quoting so a damaged CSV
 * fails before any database writes are attempted.
 */
internal object CsvCodec {
    fun encodeRecord(fields: List<String>): String = fields.joinToString(",") { encodeField(it) }

    fun encodeField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuotes) return value
        return buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }

    fun parse(text: String): List<List<String>> {
        if (text.isEmpty()) return emptyList()

        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var state = State.START_FIELD
        var i = 0
        var recordStarted = false

        fun finishField() {
            row += field.toString()
            field.setLength(0)
            state = State.START_FIELD
            recordStarted = true
        }

        fun finishRecord() {
            finishField()
            records += row
            row = mutableListOf()
            recordStarted = false
        }

        while (i < text.length) {
            val ch = text[i]
            when (state) {
                State.START_FIELD -> when (ch) {
                    '"' -> {
                        state = State.IN_QUOTED
                        recordStarted = true
                    }
                    ',' -> finishField()
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        finishRecord()
                    }
                    else -> {
                        field.append(ch)
                        state = State.IN_UNQUOTED
                        recordStarted = true
                    }
                }

                State.IN_UNQUOTED -> when (ch) {
                    ',' -> finishField()
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        finishRecord()
                    }
                    '"' -> throw csvError(i, "quote inside an unquoted field")
                    else -> field.append(ch)
                }

                State.IN_QUOTED -> when (ch) {
                    '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            state = State.AFTER_QUOTE
                        }
                    }
                    else -> field.append(ch)
                }

                State.AFTER_QUOTE -> when (ch) {
                    ',' -> finishField()
                    '\r', '\n' -> {
                        if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        finishRecord()
                    }
                    ' ', '\t' -> {
                        // Be tolerant of whitespace after a closing quote; it is
                        // not part of the field value and is common in bank CSVs.
                    }
                    else -> throw csvError(i, "unexpected character after a closing quote")
                }
            }
            i++
        }

        if (state == State.IN_QUOTED) {
            throw csvError(text.length, "unterminated quoted field")
        }

        // A trailing record separator already committed the final record. Do not
        // manufacture an extra blank row solely because the file ends in CR/LF.
        if (recordStarted || field.isNotEmpty() || row.isNotEmpty() || state == State.AFTER_QUOTE) {
            finishField()
            records += row
        }

        return records
    }

    private fun csvError(offset: Int, reason: String): IllegalArgumentException =
        IllegalArgumentException("Malformed CSV at character ${offset + 1}: $reason.")

    private enum class State {
        START_FIELD,
        IN_UNQUOTED,
        IN_QUOTED,
        AFTER_QUOTE
    }
}
