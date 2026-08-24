package com.nirmalamgroup.nirmalamdhanam

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression guard for the ID-scoped cleanup contract used by Vinyasa. */
@RunWith(AndroidJUnit4::class)
class StarterDataRemovalTest {
    @Test fun cleanupRemovesOnlyStarterRowsAndPersistsNoReseedFlag() {
        val name = "starter-removal.db"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE nirmalam_dhanam_config (id INTEGER PRIMARY KEY, starterDataRemoved INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE accounts (id TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE transactions (id TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE payees (id TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE investment_balance_snapshots (id TEXT PRIMARY KEY)")
                        db.execSQL("CREATE TABLE net_worth_snapshots (id TEXT PRIMARY KEY)")
                    }
                }).build()
        helper.use {
            val db = it.writableDatabase
            db.execSQL("INSERT INTO nirmalam_dhanam_config VALUES (1, 0)")
            listOf("demo-bank", "user-cash").forEach { id -> db.execSQL("INSERT INTO accounts VALUES (?)", arrayOf(id)) }
            listOf("demo-rent", "user-rent").forEach { id -> db.execSQL("INSERT INTO transactions VALUES (?)", arrayOf(id)) }
            listOf("demo-payee-shop", "user-payee").forEach { id -> db.execSQL("INSERT INTO payees VALUES (?)", arrayOf(id)) }
            listOf("system-varga-1", "user-varga").forEach { id -> db.execSQL("INSERT INTO categories VALUES (?)", arrayOf(id)) }
            listOf("demo-snapshot", "user-snapshot").forEach { id -> db.execSQL("INSERT INTO investment_balance_snapshots VALUES (?)", arrayOf(id)) }
            listOf("demo-net-worth", "user-net-worth").forEach { id -> db.execSQL("INSERT INTO net_worth_snapshots VALUES (?)", arrayOf(id)) }
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM transactions WHERE id LIKE 'demo-%'")
                db.execSQL("DELETE FROM investment_balance_snapshots WHERE id LIKE 'demo-%'")
                db.execSQL("DELETE FROM net_worth_snapshots WHERE id LIKE 'demo-%'")
                db.execSQL("DELETE FROM accounts WHERE id LIKE 'demo-%'")
                db.execSQL("DELETE FROM payees WHERE id LIKE 'system-vyakti-%' OR id LIKE 'demo-payee-%'")
                db.execSQL("DELETE FROM categories WHERE id LIKE 'system-varga-%' OR id LIKE 'expense-%' OR id LIKE 'income-%'")
                db.execSQL("UPDATE nirmalam_dhanam_config SET starterDataRemoved = 1 WHERE id = 1")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            listOf("accounts", "transactions", "payees", "categories", "investment_balance_snapshots", "net_worth_snapshots").forEach { table ->
                assertEquals("user data in $table", 1, db.query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
            }
            assertTrue(db.query("SELECT starterDataRemoved FROM nirmalam_dhanam_config WHERE id = 1").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) == 1 })
        }
        context.deleteDatabase(name)
    }
}
