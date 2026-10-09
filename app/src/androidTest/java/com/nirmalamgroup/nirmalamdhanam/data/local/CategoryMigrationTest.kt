package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration12To13_addsMissingPriorityAndNatureToHistoricallyMigratedDatabase() {
        val helper = openVersion12Database(categoryColumnsAlreadyPresent = false)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey) " +
                "VALUES('cat-1', 'Groceries', 'DEBIT', 1, NULL)"
        )

        migration12To13().migrate(db)

        assertTrue(db.hasColumnForTest("categories", "priority"))
        assertTrue(db.hasColumnForTest("categories", "nature"))
        db.query("SELECT priority, nature FROM categories WHERE id = 'cat-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("NEED", cursor.getString(0))
            assertEquals("VARIABLE", cursor.getString(1))
        }
        helper.close()
    }

    @Test
    fun migration12To13_doesNotReAddColumnsWhenFreshV12AlreadyContainsThem() {
        val helper = openVersion12Database(categoryColumnsAlreadyPresent = true)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey, priority, nature) " +
                "VALUES('cat-2', 'Travel', 'DEBIT', 0, NULL, 'WANT', 'VARIABLE')"
        )

        migration12To13().migrate(db)

        db.query("SELECT priority, nature FROM categories WHERE id = 'cat-2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("WANT", cursor.getString(0))
            assertEquals("VARIABLE", cursor.getString(1))
        }
        helper.close()
    }

    private fun migration12To13() = NirmalamDatabase.migrationChain().single {
        it.startVersion == 12 && it.endVersion == 13
    }

    @Test
    fun migration13To14_repairsOnlyKnownSystemIncomeCategoryDirections() {
        val helper = openVersion13CategoryDatabase()
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey, priority, nature) VALUES('salary', 'Salary & wages', 'DEBIT', 1, NULL, 'NEED', 'VARIABLE')")
        db.execSQL("INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey, priority, nature) VALUES('interest', 'Interest & dividends', 'DEBIT', 1, NULL, 'NEED', 'VARIABLE')")
        db.execSQL("INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey, priority, nature) VALUES('food', 'Food & dining', 'DEBIT', 1, NULL, 'NEED', 'VARIABLE')")
        db.execSQL("INSERT INTO categories(id, name, transactionDirection, isSystem, iconKey, priority, nature) VALUES('user-salary', 'Custom income', 'DEBIT', 0, NULL, 'NEED', 'VARIABLE')")

        migration13To14().migrate(db)

        assertEquals("CREDIT", categoryDirection(db, "salary"))
        assertEquals("CREDIT", categoryDirection(db, "interest"))
        assertEquals("DEBIT", categoryDirection(db, "food"))
        assertEquals("DEBIT", categoryDirection(db, "user-salary"))
        helper.close()
    }

    private fun migration13To14() = NirmalamDatabase.migrationChain().single {
        it.startVersion == 13 && it.endVersion == 14
    }

    private fun categoryDirection(db: SupportSQLiteDatabase, id: String): String =
        db.query("SELECT transactionDirection FROM categories WHERE id = ?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun openVersion13CategoryDatabase(): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE categories (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "transactionDirection TEXT NOT NULL, " +
                        "isSystem INTEGER NOT NULL, " +
                        "iconKey TEXT, " +
                        "priority TEXT NOT NULL DEFAULT 'NEED', " +
                        "nature TEXT NOT NULL DEFAULT 'VARIABLE', " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL("CREATE UNIQUE INDEX index_categories_name ON categories(name)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(callback)
                .build()
        )
    }

    private fun openVersion12Database(categoryColumnsAlreadyPresent: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val classificationColumns = if (categoryColumnsAlreadyPresent) {
                    ", priority TEXT NOT NULL DEFAULT 'NEED', nature TEXT NOT NULL DEFAULT 'VARIABLE'"
                } else {
                    ""
                }
                db.execSQL(
                    "CREATE TABLE categories (" +
                        "id TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "transactionDirection TEXT NOT NULL, " +
                        "isSystem INTEGER NOT NULL, " +
                        "iconKey TEXT" +
                        classificationColumns +
                        ", PRIMARY KEY(id))"
                )
                db.execSQL("CREATE UNIQUE INDEX index_categories_name ON categories(name)")

                // MIGRATION_12_13 also repairs the investment snapshot table, so reproduce the
                // columns that existed at v12 to exercise the real production migration object.
                db.execSQL(
                    "CREATE TABLE investment_balance_snapshots (" +
                        "id TEXT NOT NULL, " +
                        "accountId TEXT NOT NULL, " +
                        "asOfEpochDay INTEGER NOT NULL, " +
                        "totalCostPaise INTEGER NOT NULL, " +
                        "currentValuePaise INTEGER NOT NULL, " +
                        "netContributionPaise INTEGER NOT NULL, " +
                        "note TEXT, " +
                        "createdAtEpochMs INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(callback)
                .build()
        )
    }

    private fun SupportSQLiteDatabase.hasColumnForTest(table: String, column: String): Boolean {
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private companion object {
        const val DB_NAME = "category-migration-test.db"
    }
}
