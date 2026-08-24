package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DatabaseConverters { @TypeConverter fun fromAccountKind(v: AccountKind) = v.name; @TypeConverter fun toAccountKind(v: String) = AccountKind.valueOf(v); @TypeConverter fun fromProductType(v: AccountProductType) = v.name; @TypeConverter fun toProductType(v: String) = AccountProductType.valueOf(v); @TypeConverter fun fromAssetClass(v: AssetClass) = v.name; @TypeConverter fun toAssetClass(v: String) = AssetClass.valueOf(v); @TypeConverter fun fromDirection(v: TransactionDirection) = v.name; @TypeConverter fun toDirection(v: String) = TransactionDirection.valueOf(v); @TypeConverter fun fromEnvelope(v: EnvelopeType?) = v?.name; @TypeConverter fun toEnvelope(v: String?) = v?.let(EnvelopeType::valueOf) }

@Database(entities = [ConfigEntity::class, AccountEntity::class, TransactionEntity::class, EnvelopeEntity::class, CategoryEntity::class, PayeeEntity::class, InvestmentBalanceSnapshotEntity::class, NetWorthSnapshotEntity::class], version = 9, exportSchema = false)
@TypeConverters(DatabaseConverters::class)
abstract class NirmalamDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun envelopeDao(): EnvelopeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun payeeDao(): PayeeDao
    abstract fun investmentBalanceSnapshotDao(): InvestmentBalanceSnapshotDao
    abstract fun netWorthSnapshotDao(): NetWorthSnapshotDao
    companion object {
        const val FILE_NAME = "nirmalam_dhanam.db"
        fun create(context: Context, passphrase: CharArray): NirmalamDatabase {
            System.loadLibrary("sqlcipher")
            val key = DatabaseKeyManager(context.applicationContext).derive(passphrase)
            return Room.databaseBuilder(context, NirmalamDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN neurodiverseModeEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** Adds user-authored transaction context without changing any existing balances. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT")
                database.execSQL("ALTER TABLE transactions ADD COLUMN payee TEXT")
                database.execSQL("ALTER TABLE transactions ADD COLUMN description TEXT")
                database.execSQL("UPDATE transactions SET payee = merchant WHERE payee IS NULL AND merchant IS NOT NULL")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE accounts ADD COLUMN productType TEXT NOT NULL DEFAULT 'CASH'")
                database.execSQL("CREATE TABLE IF NOT EXISTS categories (id TEXT NOT NULL, name TEXT NOT NULL, transactionDirection TEXT NOT NULL, isSystem INTEGER NOT NULL, PRIMARY KEY(id))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
                database.execSQL("CREATE TABLE IF NOT EXISTS payees (id TEXT NOT NULL, name TEXT NOT NULL, defaultCategory TEXT, lastUsedEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payees_name ON payees(name)")
                database.execSQL("UPDATE accounts SET productType = CASE kind WHEN 'INVESTMENT' THEN 'MUTUAL_FUNDS' ELSE 'CASH' END")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS investment_balance_snapshots (id TEXT NOT NULL, accountId TEXT NOT NULL, asOfEpochDay INTEGER NOT NULL, totalCostPaise INTEGER NOT NULL, currentValuePaise INTEGER NOT NULL, netContributionPaise INTEGER NOT NULL, note TEXT, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_investment_balance_snapshots_accountId_asOfEpochDay ON investment_balance_snapshots(accountId, asOfEpochDay)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_investment_balance_snapshots_asOfEpochDay ON investment_balance_snapshots(asOfEpochDay)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS net_worth_snapshots (id TEXT NOT NULL, asOfEpochDay INTEGER NOT NULL, netWorthPaise INTEGER NOT NULL, portfolioValuePaise INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_net_worth_snapshots_asOfEpochDay ON net_worth_snapshots(asOfEpochDay)")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE accounts ADD COLUMN assetClass TEXT NOT NULL DEFAULT 'CASH'")
                database.execSQL("ALTER TABLE accounts ADD COLUMN targetAllocationBps INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE accounts SET assetClass = CASE productType WHEN 'EQUITY' THEN 'EQUITY' WHEN 'MUTUAL_FUNDS' THEN 'EQUITY' WHEN 'PPF' THEN 'DEBT' WHEN 'EPF' THEN 'RETIREMENT' WHEN 'NPS' THEN 'RETIREMENT' WHEN 'SUPERANNUATION' THEN 'RETIREMENT' ELSE 'CASH' END")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN iconKey TEXT")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN starterDataRemoved INTEGER NOT NULL DEFAULT 0")
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
