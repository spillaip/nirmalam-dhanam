package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class DatabaseConverters {
    @TypeConverter fun fromAccountKind(v: AccountKind) = v.name
    @TypeConverter fun toAccountKind(v: String) = AccountKind.valueOf(v)
    @TypeConverter fun fromProductType(v: AccountProductType) = v.name
    @TypeConverter fun toProductType(v: String) = AccountProductType.valueOf(v)
    @TypeConverter fun fromAssetClass(v: AssetClass) = v.name
    @TypeConverter fun toAssetClass(v: String) = AssetClass.valueOf(v)
    @TypeConverter fun fromBenchmarkTrackingMethod(v: BenchmarkTrackingMethod) = v.name
    @TypeConverter fun toBenchmarkTrackingMethod(v: String) = BenchmarkTrackingMethod.valueOf(v)
    @TypeConverter fun fromDirection(v: TransactionDirection) = v.name
    @TypeConverter fun toDirection(v: String) = TransactionDirection.valueOf(v)
    @TypeConverter fun fromEnvelope(v: EnvelopeType?) = v?.name
    @TypeConverter fun toEnvelope(v: String?) = v?.let(EnvelopeType::valueOf)
    @TypeConverter fun fromDateFormatPreference(v: DateFormatPreference) = v.name
    @TypeConverter fun toDateFormatPreference(v: String) = DateFormatPreference.valueOf(v)
}

@Database(
    entities = [
        ConfigEntity::class, AccountEntity::class, TransactionEntity::class, EnvelopeEntity::class,
        CategoryEntity::class, PayeeEntity::class, InvestmentBalanceSnapshotEntity::class,
        NetWorthSnapshotEntity::class, GoalEntity::class, GoalAllocationEntity::class
    ],
    version = NirmalamDatabase.SCHEMA_VERSION,
    exportSchema = true
)
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
    abstract fun goalDao(): GoalDao
    abstract fun goalAllocationDao(): GoalAllocationDao

    companion object {
        const val FILE_NAME = "nirmalam_dhanam.db"
        const val SCHEMA_VERSION = 15

        fun create(context: Context, passphrase: CharArray): NirmalamDatabase {
            System.loadLibrary("sqlcipher")
            val key = DatabaseKeyManager(context.applicationContext).derive(passphrase)
            return Room.databaseBuilder(context, NirmalamDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(*migrationChain())
                .build()
        }

        /** Used by instrumentation tests so they exercise the exact production migration chain. */
        fun migrationChain(): Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
            MIGRATION_13_14, MIGRATION_14_15
        )

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return true
            }
            return false
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN neurodiverseModeEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds user-authored transaction context without changing any existing balances. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN payee TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN description TEXT")
                db.execSQL("UPDATE transactions SET payee = merchant WHERE payee IS NULL AND merchant IS NOT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN productType TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("CREATE TABLE IF NOT EXISTS categories (id TEXT NOT NULL, name TEXT NOT NULL, transactionDirection TEXT NOT NULL, isSystem INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
                db.execSQL("CREATE TABLE IF NOT EXISTS payees (id TEXT NOT NULL, name TEXT NOT NULL, defaultCategory TEXT, lastUsedEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_payees_name ON payees(name)")
                db.execSQL("UPDATE accounts SET productType = CASE kind WHEN 'INVESTMENT' THEN 'MUTUAL_FUNDS' ELSE 'CASH' END")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS investment_balance_snapshots (id TEXT NOT NULL, accountId TEXT NOT NULL, asOfEpochDay INTEGER NOT NULL, totalCostPaise INTEGER NOT NULL, currentValuePaise INTEGER NOT NULL, netContributionPaise INTEGER NOT NULL, note TEXT, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_investment_balance_snapshots_accountId_asOfEpochDay ON investment_balance_snapshots(accountId, asOfEpochDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_balance_snapshots_asOfEpochDay ON investment_balance_snapshots(asOfEpochDay)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS net_worth_snapshots (id TEXT NOT NULL, asOfEpochDay INTEGER NOT NULL, netWorthPaise INTEGER NOT NULL, portfolioValuePaise INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_net_worth_snapshots_asOfEpochDay ON net_worth_snapshots(asOfEpochDay)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN assetClass TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("ALTER TABLE accounts ADD COLUMN targetAllocationBps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE accounts SET assetClass = CASE productType WHEN 'EQUITY' THEN 'EQUITY' WHEN 'MUTUAL_FUNDS' THEN 'EQUITY' WHEN 'PPF' THEN 'DEBT' WHEN 'EPF' THEN 'RETIREMENT' WHEN 'NPS' THEN 'RETIREMENT' WHEN 'SUPERANNUATION' THEN 'RETIREMENT' ELSE 'CASH' END")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN iconKey TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN starterDataRemoved INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN benchmarkIndexName TEXT")
                db.execSQL("ALTER TABLE accounts ADD COLUMN benchmarkTrackingMethod TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE accounts ADD COLUMN benchmarkIsTotalReturn INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN dateFormatPreference TEXT NOT NULL DEFAULT 'DEVICE_LOCALE'")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN savedLedgerRange TEXT NOT NULL DEFAULT 'MONTH'")
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN savedLedgerFilter TEXT NOT NULL DEFAULT 'ALL'")
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN savedLedgerAccountId TEXT")
                db.execSQL("ALTER TABLE nirmalam_dhanam_config ADD COLUMN savedLedgerCategoryName TEXT")
            }
        }

        /**
         * Repairs the v12 migration gap and introduces auditable investment deltas + goal linking.
         * The column checks make this safe for both fresh v12 databases (where Room already created
         * priority/nature) and databases that reached v12 through the historical migration chain.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("categories", "priority")) {
                    db.execSQL("ALTER TABLE categories ADD COLUMN priority TEXT NOT NULL DEFAULT 'NEED'")
                }
                if (!db.hasColumn("categories", "nature")) {
                    db.execSQL("ALTER TABLE categories ADD COLUMN nature TEXT NOT NULL DEFAULT 'VARIABLE'")
                }
                if (!db.hasColumn("investment_balance_snapshots", "previousCostPaise")) {
                    db.execSQL("ALTER TABLE investment_balance_snapshots ADD COLUMN previousCostPaise INTEGER")
                }
                if (!db.hasColumn("investment_balance_snapshots", "previousValuePaise")) {
                    db.execSQL("ALTER TABLE investment_balance_snapshots ADD COLUMN previousValuePaise INTEGER")
                }
                if (!db.hasColumn("investment_balance_snapshots", "costDeltaPaise")) {
                    db.execSQL("ALTER TABLE investment_balance_snapshots ADD COLUMN costDeltaPaise INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("investment_balance_snapshots", "valueDeltaPaise")) {
                    db.execSQL("ALTER TABLE investment_balance_snapshots ADD COLUMN valueDeltaPaise INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("investment_balance_snapshots", "marketMovementPaise")) {
                    db.execSQL("ALTER TABLE investment_balance_snapshots ADD COLUMN marketMovementPaise INTEGER NOT NULL DEFAULT 0")
                }
                // Existing snapshots get deterministic deltas. netContributionPaise is retained as
                // historical input for compatibility; all new/edited snapshots are recalculated.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS goals (
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmountPaise INTEGER NOT NULL,
                        targetDateEpochDay INTEGER,
                        note TEXT,
                        isArchived INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goals_targetDateEpochDay ON goals(targetDateEpochDay)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS goal_allocations (
                        id TEXT NOT NULL,
                        goalId TEXT NOT NULL,
                        accountId TEXT NOT NULL,
                        allocationBps INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_goal_allocations_goalId_accountId ON goal_allocations(goalId, accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_goal_allocations_accountId ON goal_allocations(accountId)")
            }
        }

        /**
         * Repairs historical seed metadata without changing user-created categories. Older builds
         * stored every starter category as DEBIT, including income categories. Category direction
         * is used by filtering/default-entry UX, so known system income categories must be CREDIT.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE categories
                    SET transactionDirection = 'CREDIT'
                    WHERE isSystem = 1
                      AND name IN (
                        'Salary & wages',
                        'Freelance & business',
                        'Interest & dividends',
                        'Refunds & cashback'
                      )
                    """.trimIndent()
                )
            }
        }

        /** Persists import provenance so repeated CSV/.dhanam records can be identified exactly. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("transactions", "sourceFingerprint")) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN sourceFingerprint TEXT")
                }
                if (!db.hasColumn("transactions", "sourceKind")) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN sourceKind TEXT")
                }
                if (!db.hasColumn("transactions", "reconciledAtEpochMs")) {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN reconciledAtEpochMs INTEGER")
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_sourceFingerprint ON transactions(sourceFingerprint)")
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
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, 210_000, 256)).encoded
    } finally {
        passphrase.fill('\u0000')
    }
}
