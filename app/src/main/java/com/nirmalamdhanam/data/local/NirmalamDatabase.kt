package com.nirmalamdhanam.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DatabaseConverters { @TypeConverter fun fromAccountKind(v: AccountKind) = v.name; @TypeConverter fun toAccountKind(v: String) = AccountKind.valueOf(v); @TypeConverter fun fromDirection(v: TransactionDirection) = v.name; @TypeConverter fun toDirection(v: String) = TransactionDirection.valueOf(v); @TypeConverter fun fromEnvelope(v: EnvelopeType?) = v?.name; @TypeConverter fun toEnvelope(v: String?) = v?.let(EnvelopeType::valueOf) }

@Database(entities = [ConfigEntity::class, AccountEntity::class, TransactionEntity::class, EnvelopeEntity::class], version = 2, exportSchema = false)
@TypeConverters(DatabaseConverters::class)
abstract class NirmalamDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun envelopeDao(): EnvelopeDao
    companion object {
        const val FILE_NAME = "nirmalam_dhanam.db"
        fun create(context: Context, passphrase: CharArray): NirmalamDatabase {
            SQLiteDatabase.loadLibs(context)
            val key = DatabaseKeyManager(context.applicationContext).derive(passphrase)
            return Room.databaseBuilder(context, NirmalamDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportFactory(key, true))
                .addMigrations(MIGRATION_1_2)
                .build()
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN neurodiverseModeEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

class DatabaseKeyManager(context: Context) {
    private val preferences = context.getSharedPreferences("db_key_material", Context.MODE_PRIVATE)
    val salt: ByteArray
        get() = preferences.getString("pbkdf2_salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            check(preferences.edit().putString("pbkdf2_salt", Base64.encodeToString(it, Base64.NO_WRAP)).commit())
        }
    fun derive(passphrase: CharArray): ByteArray = DatabaseKeyDeriver.derive(passphrase, salt)
    fun replaceSalt(newSalt: ByteArray) {
        require(newSalt.size >= 16)
        check(preferences.edit().putString("pbkdf2_salt", Base64.encodeToString(newSalt, Base64.NO_WRAP)).commit())
    }
}

object DatabaseKeyDeriver {
    /** PBKDF2 output is passed directly to SQLCipher; callers must clear their passphrase. */
    fun derive(passphrase: CharArray, salt: ByteArray): ByteArray = try {
        require(salt.size >= 16)
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, 210_000, 256)).encoded
    } finally { passphrase.fill('\u0000') }
}
