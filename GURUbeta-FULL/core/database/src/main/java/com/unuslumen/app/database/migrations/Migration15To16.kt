package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Expand memory_facts with new columns
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN layer TEXT NOT NULL DEFAULT 'BUFFER'")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN strength REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN trigger TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN before_context TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN after_context TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN emotional_valence REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN domain TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN topic TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN subtopic TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory_facts ADD COLUMN signature TEXT NOT NULL DEFAULT ''")

        // Create memory_edges table
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_edges (source_id TEXT NOT NULL, target_id TEXT NOT NULL, edge_type TEXT NOT NULL, weight REAL NOT NULL DEFAULT 1.0, created_at INTEGER NOT NULL DEFAULT 0, metadata TEXT NOT NULL DEFAULT '', PRIMARY KEY(source_id, target_id, edge_type))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_source_id ON memory_edges(source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_target_id ON memory_edges(target_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_edges_edge_type ON memory_edges(edge_type)")

        // Create memory_events table
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_events (id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL DEFAULT '', message_id TEXT NOT NULL DEFAULT '', role TEXT NOT NULL DEFAULT '', content TEXT NOT NULL DEFAULT '', enriched INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_events_enriched ON memory_events(enriched)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_events_created_at ON memory_events(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_events_conversation_id ON memory_events(conversation_id)")

        // Create memory_cross_references table
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_cross_references (fact_id_a TEXT NOT NULL, fact_id_b TEXT NOT NULL, ref_type TEXT NOT NULL DEFAULT 'RELATED', created_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(fact_id_a, fact_id_b))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_cross_references_fact_id_a ON memory_cross_references(fact_id_a)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_cross_references_fact_id_b ON memory_cross_references(fact_id_b)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_cross_references_ref_type ON memory_cross_references(ref_type)")

        // FTS5 is not available in Room bundled SQLite. Using LIKE queries in MemoryFactDao instead.
    }
}
