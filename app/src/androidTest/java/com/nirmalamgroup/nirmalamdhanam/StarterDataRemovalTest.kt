package com.nirmalamgroup.nirmalamdhanam

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the ID-scoped cleanup contract used by Vinyasa.
 *
 * Starter/demo rows must be removed without deleting user-created data.
 * The cleanup also persists the no-reseed flag.
 */
@RunWith(AndroidJUnit4::class)
class StarterDataRemovalTest {

    @Test
    fun cleanupRemovesOnlyStarterRowsAndPersistsNoReseedFlag() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "starter-removal.db"

        // Ensure the test always starts with a fresh database.
        context.deleteDatabase(databaseName)

        val configuration =
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {

                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE nirmalam_dhanam_config (
                                    id INTEGER PRIMARY KEY,
                                    starterDataRemoved INTEGER NOT NULL DEFAULT 0
                                )
                                """.trimIndent()
                            )

                            db.execSQL(
                                "CREATE TABLE accounts (id TEXT PRIMARY KEY)"
                            )

                            db.execSQL(
                                "CREATE TABLE transactions (id TEXT PRIMARY KEY)"
                            )

                            db.execSQL(
                                "CREATE TABLE payees (id TEXT PRIMARY KEY)"
                            )

                            db.execSQL(
                                "CREATE TABLE categories (id TEXT PRIMARY KEY)"
                            )

                            db.execSQL(
                                """
                                CREATE TABLE investment_balance_snapshots (
                                    id TEXT PRIMARY KEY
                                )
                                """.trimIndent()
                            )

                            db.execSQL(
                                """
                                CREATE TABLE net_worth_snapshots (
                                    id TEXT PRIMARY KEY
                                )
                                """.trimIndent()
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // This isolated regression-test database has one schema version.
                        }
                    }
                )
                .build()

        val helper =
            FrameworkSQLiteOpenHelperFactory()
                .create(configuration)

        try {
            val db = helper.writableDatabase

            seedTestData(db)
            removeStarterData(db)

            assertUserDataPreserved(db)
            assertStarterDataRemoved(db)
            assertNoReseedFlagPersisted(db)
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun seedTestData(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO nirmalam_dhanam_config
                (id, starterDataRemoved)
            VALUES
                (1, 0)
            """.trimIndent()
        )

        listOf(
            "demo-bank",
            "user-cash",
        ).forEach { id ->
            db.execSQL(
                "INSERT INTO accounts (id) VALUES (?)",
                arrayOf(id),
            )
        }

        listOf(
            "demo-rent",
            "user-rent",
        ).forEach { id ->
            db.execSQL(
                "INSERT INTO transactions (id) VALUES (?)",
                arrayOf(id),
            )
        }

        listOf(
            "demo-payee-shop",
            "user-payee",
        ).forEach { id ->
            db.execSQL(
                "INSERT INTO payees (id) VALUES (?)",
                arrayOf(id),
            )
        }

        listOf(
            "system-varga-1",
            "user-varga",
        ).forEach { id ->
            db.execSQL(
                "INSERT INTO categories (id) VALUES (?)",
                arrayOf(id),
            )
        }

        listOf(
            "demo-snapshot",
            "user-snapshot",
        ).forEach { id ->
            db.execSQL(
                """
                INSERT INTO investment_balance_snapshots (id)
                VALUES (?)
                """.trimIndent(),
                arrayOf(id),
            )
        }

        listOf(
            "demo-net-worth",
            "user-net-worth",
        ).forEach { id ->
            db.execSQL(
                """
                INSERT INTO net_worth_snapshots (id)
                VALUES (?)
                """.trimIndent(),
                arrayOf(id),
            )
        }
    }

    private fun removeStarterData(db: SupportSQLiteDatabase) {
        db.beginTransaction()

        try {
            db.execSQL(
                "DELETE FROM transactions WHERE id LIKE 'demo-%'"
            )

            db.execSQL(
                """
                DELETE FROM investment_balance_snapshots
                WHERE id LIKE 'demo-%'
                """.trimIndent()
            )

            db.execSQL(
                """
                DELETE FROM net_worth_snapshots
                WHERE id LIKE 'demo-%'
                """.trimIndent()
            )

            db.execSQL(
                "DELETE FROM accounts WHERE id LIKE 'demo-%'"
            )

            db.execSQL(
                """
                DELETE FROM payees
                WHERE id LIKE 'system-vyakti-%'
                   OR id LIKE 'demo-payee-%'
                """.trimIndent()
            )

            db.execSQL(
                """
                DELETE FROM categories
                WHERE id LIKE 'system-varga-%'
                   OR id LIKE 'expense-%'
                   OR id LIKE 'income-%'
                """.trimIndent()
            )

            db.execSQL(
                """
                UPDATE nirmalam_dhanam_config
                SET starterDataRemoved = 1
                WHERE id = 1
                """.trimIndent()
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun assertUserDataPreserved(db: SupportSQLiteDatabase) {
        val tables = listOf(
            "accounts",
            "transactions",
            "payees",
            "categories",
            "investment_balance_snapshots",
            "net_worth_snapshots",
        )

        tables.forEach { table ->
            val count = queryInt(
                db = db,
                sql = "SELECT COUNT(*) FROM $table",
            )

            assertEquals(
                "user data in $table",
                1,
                count,
            )
        }
    }

    private fun assertStarterDataRemoved(db: SupportSQLiteDatabase) {
        assertEquals(
            0,
            queryInt(
                db,
                "SELECT COUNT(*) FROM accounts WHERE id LIKE 'demo-%'",
            ),
        )

        assertEquals(
            0,
            queryInt(
                db,
                "SELECT COUNT(*) FROM transactions WHERE id LIKE 'demo-%'",
            ),
        )

        assertEquals(
            0,
            queryInt(
                db,
                """
                SELECT COUNT(*)
                FROM payees
                WHERE id LIKE 'system-vyakti-%'
                   OR id LIKE 'demo-payee-%'
                """.trimIndent(),
            ),
        )

        assertEquals(
            0,
            queryInt(
                db,
                """
                SELECT COUNT(*)
                FROM categories
                WHERE id LIKE 'system-varga-%'
                   OR id LIKE 'expense-%'
                   OR id LIKE 'income-%'
                """.trimIndent(),
            ),
        )

        assertEquals(
            0,
            queryInt(
                db,
                """
                SELECT COUNT(*)
                FROM investment_balance_snapshots
                WHERE id LIKE 'demo-%'
                """.trimIndent(),
            ),
        )

        assertEquals(
            0,
            queryInt(
                db,
                """
                SELECT COUNT(*)
                FROM net_worth_snapshots
                WHERE id LIKE 'demo-%'
                """.trimIndent(),
            ),
        )
    }

    private fun assertNoReseedFlagPersisted(db: SupportSQLiteDatabase) {
        val starterDataRemoved =
            queryInt(
                db,
                """
                SELECT starterDataRemoved
                FROM nirmalam_dhanam_config
                WHERE id = 1
                """.trimIndent(),
            )

        assertTrue(
            "starterDataRemoved must be persisted after cleanup",
            starterDataRemoved == 1,
        )
    }

    private fun queryInt(
        db: SupportSQLiteDatabase,
        sql: String,
    ): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst()) {
                "Query returned no rows: $sql"
            }

            cursor.getInt(0)
        }
}