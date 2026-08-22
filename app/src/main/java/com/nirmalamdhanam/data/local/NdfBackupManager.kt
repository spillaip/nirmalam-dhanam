package com.nirmalamdhanam.data.local

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.sqlcipher.database.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The serialisation gate used by writes and backup operations. */
object DatabaseAccessGate { val writeLock = Mutex() }

@Serializable
data class NdfManifest(
    val format: String = "nirmalam-dhanam-backup",
    val formatVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val databaseFile: String = DATABASE_ENTRY,
    val kdf: String = "PBKDF2WithHmacSHA256",
    val kdfIterations: Int = 210_000,
    val kdfSaltBase64: String
) {
    companion object { const val DATABASE_ENTRY = "database.sqlcipher"; const val MANIFEST_ENTRY = "manifest.json" }
}

sealed interface NdfResult {
    data class Exported(val uri: Uri, val databaseBytes: Long) : NdfResult
    data class Imported(val databaseBytes: Long) : NdfResult
    data class Failure(val message: String, val cause: Throwable? = null) : NdfResult
}

/**
 * `.ndf` is a ZIP container. Its payload remains SQLCipher-encrypted; compression is only
 * packaging and must not be treated as encryption. A backup can be imported on another device
 * when the user supplies the same passphrase.
 */
class NdfBackupManager(
    private val context: Context,
    private val database: NirmalamDatabase,
    private val closeActiveDatabase: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val keyManager = DatabaseKeyManager(context.applicationContext)

    suspend fun exportTo(destination: Uri, passphrase: CharArray): NdfResult = withContext(Dispatchers.IO) {
        DatabaseAccessGate.writeLock.withLock {
            try {
                // The caller normally receives this after device authentication. Deriving and
                // immediately clearing it also ensures a passphrase is actually supplied here.
                keyManager.derive(passphrase).fill(0)
                database.withTransaction { /* waits for all Room writes already in progress */ }
                database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
                val source = context.getDatabasePath(NirmalamDatabase.FILE_NAME)
                check(source.isFile && source.length() > 0) { "No local database is available to export." }
                val manifest = NdfManifest(exportedAtEpochMs = System.currentTimeMillis(), kdfSaltBase64 = Base64.encodeToString(keyManager.salt, Base64.NO_WRAP))
                context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                        zip.putNextEntry(ZipEntry(NdfManifest.MANIFEST_ENTRY))
                        zip.write(json.encodeToString(NdfManifest.serializer(), manifest).encodeToByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry(NdfManifest.DATABASE_ENTRY))
                        FileInputStream(source).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                } ?: error("Unable to open the selected export destination.")
                passphrase.fill('\u0000')
                NdfResult.Exported(destination, source.length())
            } catch (t: Throwable) {
                passphrase.fill('\u0000')
                NdfResult.Failure("Export failed; the existing database was not changed.", t)
            }
        }
    }

    /** Caller must stop all app database consumers before invoking import. */
    suspend fun importFrom(source: Uri, passphrase: CharArray): NdfResult = withContext(Dispatchers.IO) {
        DatabaseAccessGate.writeLock.withLock {
            val staging = File(context.cacheDir, "ndf-import-${UUID.randomUUID()}.db")
            try {
                val manifest = unpack(source, staging)
                validateManifest(manifest)
                val importedSalt = Base64.decode(manifest.kdfSaltBase64, Base64.NO_WRAP)
                validateEncryptedDatabase(staging, passphrase, importedSalt)
                closeActiveDatabase()
                replaceDatabaseAtomically(staging, importedSalt)
                NdfResult.Imported(context.getDatabasePath(NirmalamDatabase.FILE_NAME).length())
            } catch (t: Throwable) {
                staging.delete()
                passphrase.fill('\u0000')
                NdfResult.Failure("Import failed; local data was left unchanged.", t)
            }
        }
    }

    private fun unpack(source: Uri, staging: File): NdfManifest {
        var manifestText: String? = null
        var databaseFound = false
        var totalUncompressed = 0L
        context.contentResolver.openInputStream(source)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Folders are not permitted in an .ndf backup." }
                    when (entry.name) {
                        NdfManifest.MANIFEST_ENTRY -> {
                            require(manifestText == null) { "Backup contains multiple manifests." }
                            manifestText = zip.readLimitedText(MAX_MANIFEST_BYTES)
                        }
                        NdfManifest.DATABASE_ENTRY -> {
                            require(!databaseFound) { "Backup contains multiple database entries." }
                            FileOutputStream(staging).use { out -> totalUncompressed += zip.copyLimitedTo(out, MAX_DATABASE_BYTES) }
                            databaseFound = true
                        }
                        else -> error("Unrecognised entry in .ndf backup.")
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to read the selected .ndf file.")
        require(databaseFound && staging.length() > 0 && totalUncompressed > 0) { "Backup does not contain a database." }
        return json.decodeFromString(NdfManifest.serializer(), requireNotNull(manifestText) { "Backup manifest is missing." })
    }

    private fun validateManifest(manifest: NdfManifest) {
        require(manifest.format == "nirmalam-dhanam-backup" && manifest.formatVersion == 1) { "Unsupported .ndf backup format." }
        require(manifest.databaseFile == NdfManifest.DATABASE_ENTRY && manifest.kdf == "PBKDF2WithHmacSHA256" && manifest.kdfIterations == 210_000) { "Invalid .ndf encryption metadata." }
        require(Base64.decode(manifest.kdfSaltBase64, Base64.NO_WRAP).size >= 16) { "Invalid .ndf key salt." }
    }

    private fun validateEncryptedDatabase(file: File, passphrase: CharArray, salt: ByteArray) {
        SQLiteDatabase.loadLibs(context)
        val key = DatabaseKeyDeriver.derive(passphrase, salt)
        try {
            val encrypted = SQLiteDatabase.openDatabase(file.absolutePath, key, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cursor = encrypted.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>())
                try { require(cursor.moveToFirst()) { "Encrypted database could not be read." } } finally { cursor.close() }
            } finally { encrypted.close() }
        } finally { key.fill(0) }
    }

    private fun replaceDatabaseAtomically(staging: File, importedSalt: ByteArray) {
        val target = context.getDatabasePath(NirmalamDatabase.FILE_NAME)
        val recovery = File(target.parentFile, "${target.name}.pre-import")
        val oldSalt = keyManager.salt
        require(!recovery.exists() || recovery.delete()) { "Unable to prepare the database import." }
        try {
            if (target.exists()) require(target.renameTo(recovery)) { "Unable to preserve the current database." }
            deleteSidecars(target)
            require(staging.renameTo(target)) { "Unable to install imported database." }
            keyManager.replaceSalt(importedSalt)
            recovery.delete()
        } catch (t: Throwable) {
            target.delete()
            if (recovery.exists()) recovery.renameTo(target)
            keyManager.replaceSalt(oldSalt)
            throw t
        } finally { deleteSidecars(target) }
    }

    private fun deleteSidecars(databaseFile: File) {
        File("${databaseFile.path}-wal").delete()
        File("${databaseFile.path}-shm").delete()
    }

    private fun InputStream.copyLimitedTo(output: FileOutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L
        while (true) { val read = read(buffer); if (read < 0) return total; total += read; require(total <= limit) { "Backup database exceeds the allowed size." }; output.write(buffer, 0, read) }
    }
    private fun InputStream.readLimitedText(limit: Int): String {
        val bytes = readBytes(); require(bytes.size <= limit) { "Backup manifest is too large." }; return bytes.decodeToString()
    }
    private companion object { const val MAX_MANIFEST_BYTES = 16 * 1024; const val MAX_DATABASE_BYTES = 512L * 1024 * 1024 }
}
