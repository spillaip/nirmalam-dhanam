package com.nirmalamgroup.nirmalamdhanam.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NdfDatabaseValidationCallbackTest {
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
    fun exactSchemaVersion_opensWithoutChangingUserVersion() {
        createDatabaseAtVersion(NirmalamDatabase.SCHEMA_VERSION)

        validationHelper(NirmalamDatabase.SCHEMA_VERSION).use { helper ->
            helper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(NirmalamDatabase.SCHEMA_VERSION, cursor.getInt(0))
            }
        }
    }

    @Test
    fun olderDatabase_rejectedInsteadOfSilentlyUpgraded() {
        val oldVersion = NirmalamDatabase.SCHEMA_VERSION - 1
        createDatabaseAtVersion(oldVersion)

        assertThrows(IllegalStateException::class.java) {
            validationHelper(NirmalamDatabase.SCHEMA_VERSION).use { it.writableDatabase }
        }

        assertEquals(oldVersion, readUserVersionWithoutMigration(oldVersion))
    }

    @Test
    fun newerDatabase_rejectedInsteadOfSilentlyDowngraded() {
        val newerVersion = NirmalamDatabase.SCHEMA_VERSION + 1
        createDatabaseAtVersion(newerVersion)

        assertThrows(IllegalStateException::class.java) {
            validationHelper(NirmalamDatabase.SCHEMA_VERSION).use { it.writableDatabase }
        }

        assertEquals(newerVersion, readUserVersionWithoutMigration(newerVersion))
    }

    private fun createDatabaseAtVersion(version: Int) {
        rawHelper(version).use { helper ->
            helper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS sentinel(id INTEGER PRIMARY KEY)")
        }
    }

    private fun validationHelper(expectedVersion: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(NdfDatabaseValidationCallback(expectedVersion))
                .build()
        )

    private fun readUserVersionWithoutMigration(version: Int): Int =
        rawHelper(version).use { helper ->
            helper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    private fun rawHelper(version: Int): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(callback)
                .build()
        )
    }

    private companion object {
        const val DB_NAME = "ndf-validation-callback-test.db"
    }
}
