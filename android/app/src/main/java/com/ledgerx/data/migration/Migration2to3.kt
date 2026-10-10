package com.ledgerx.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve queued work and attach a safe, actionable failure reason. */
object Migration2to3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_queue ADD COLUMN lastError TEXT DEFAULT NULL")
        // Give pre-diagnostic failures one fresh attempt after queue reconciliation.
        db.execSQL("UPDATE sync_queue SET attempts = 0, lastAttemptAt = NULL")
    }
}
