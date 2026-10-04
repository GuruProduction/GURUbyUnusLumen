// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS seen_images (
                path TEXT NOT NULL PRIMARY KEY,
                mime_type TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                conversation_id TEXT NOT NULL,
                source TEXT NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_seen_images_timestamp ON seen_images(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_seen_images_conversation_id ON seen_images(conversation_id)")
    }
}