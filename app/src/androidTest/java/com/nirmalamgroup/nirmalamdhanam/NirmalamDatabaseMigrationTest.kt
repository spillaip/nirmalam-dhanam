package com.nirmalamgroup.nirmalamdhanam

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nirmalamgroup.nirmalamdhanam.data.local.NirmalamDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises every supported on-device schema starting point through the production migration chain. */
@RunWith(AndroidJUnit4::class)
class NirmalamDatabaseMigrationTest {
    @Test fun allVersionsOneThroughEightMigrateToNine() {
        (1..8).forEach { sourceVersion ->
            val name = "migration-$sourceVersion.db"
            database(name, 1).use { it.writableDatabase }
            if (sourceVersion > 1) database(name, sourceVersion).use { it.writableDatabase }
            database(name, 9).use { helper ->
                val database = helper.writableDatabase
                assertHasColumns(database, "nirmalam_dhanam_config", "neurodiverseModeEnabled", "starterDataRemoved")
                assertHasColumns(database, "accounts", "productType", "assetClass", "targetAllocationBps")
                assertHasColumns(database, "transactions", "category", "payee", "description")
                assertHasColumns(database, "categories", "iconKey")
                assertTrue(tableExists(database, "payees"))
                assertTrue(tableExists(database, "investment_balance_snapshots"))
                assertTrue(tableExists(database, "net_worth_snapshots"))
            }
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)
        }
    }

    private fun database(name: String, version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createVersionOneSchema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        NirmalamDatabase.migrationChain()
                            .filter { it.startVersion >= oldVersion && it.endVersion <= newVersion }
                            .forEach { it.migrate(db) }
                    }
                }).build()
        )

    private fun createVersionOneSchema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS nirmalam_dhanam_config (id INTEGER NOT NULL, hourlyRatePaise INTEGER NOT NULL, impulseCoolDownThresholdPaise INTEGER NOT NULL, currencyCode TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS accounts (id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, openingBalancePaise INTEGER NOT NULL, isArchived INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS transactions (id TEXT NOT NULL, accountId TEXT NOT NULL, amountPaise INTEGER NOT NULL, direction TEXT NOT NULL, merchant TEXT, envelopeType TEXT, occurredAtEpochMs INTEGER NOT NULL, isHoldingTank INTEGER NOT NULL, coolDownExpiryEpochMs INTEGER, note TEXT, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS budget_envelopes (id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, dailyLimitPaise INTEGER NOT NULL, allocatedPaise INTEGER NOT NULL, isActive INTEGER NOT NULL, PRIMARY KEY(id))")
    }

    private fun assertHasColumns(database: SupportSQLiteDatabase, table: String, vararg expected: String) {
        val actual = buildSet {
            database.query("PRAGMA table_info($table)").use { cursor ->
                val index = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }
        expected.forEach { assertTrue("$table should contain $it", it in actual) }
    }

    private fun tableExists(database: SupportSQLiteDatabase, name: String): Boolean =
        database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }
}
