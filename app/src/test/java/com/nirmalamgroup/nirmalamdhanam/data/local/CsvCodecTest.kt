package com.nirmalamgroup.nirmalamdhanam.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {
    @Test
    fun roundTripHandlesCommasQuotesAndEmbeddedNewlines() {
        val rows = listOf(
            listOf("Date", "Payee", "Notes"),
            listOf("07/10/2026", "ACME, Inc.", "He said \"hello\"\nand paid")
        )
        val encoded = rows.joinToString("\r\n") { CsvCodec.encodeRecord(it) } + "\r\n"

        assertEquals(rows, CsvCodec.parse(encoded))
        assertTrue(encoded.contains("\"ACME, Inc.\""))
        assertTrue(encoded.contains("\"He said \"\"hello\"\"\nand paid\""))
    }

    @Test
    fun parsesDoubledQuotesAndCrLfInsideQuotedField() {
        val csv = "a,b\r\n\"x\"\"y\",\"line1\r\nline2\"\r\n"

        assertEquals(
            listOf(
                listOf("a", "b"),
                listOf("x\"y", "line1\r\nline2")
            ),
            CsvCodec.parse(csv)
        )
    }

    @Test
    fun trailingNewlineDoesNotCreatePhantomBlankRecord() {
        assertEquals(listOf(listOf("a", "b")), CsvCodec.parse("a,b\r\n"))
    }

    @Test
    fun malformedUnterminatedQuoteFailsImportParsing() {
        val failure = runCatching { CsvCodec.parse("a,b\r\n1,\"broken") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("unterminated quoted field"))
    }

    @Test
    fun quoteInsideUnquotedFieldIsRejected() {
        val failure = runCatching { CsvCodec.parse("a,b\r\n1,abc\"def") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("quote inside an unquoted field"))
    }

    @Test
    fun unexpectedDataAfterClosingQuoteIsRejected() {
        val failure = runCatching { CsvCodec.parse("a,b\r\n1,\"abc\"oops") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("unexpected character after a closing quote"))
    }
}
