package com.unuslumen.app.database.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.database.migrations.MIGRATION_1_2
import com.unuslumen.app.database.migrations.MIGRATION_2_3
import com.unuslumen.app.database.migrations.MIGRATION_3_4
import com.unuslumen.app.database.migrations.MIGRATION_4_5
import com.unuslumen.app.database.migrations.MIGRATION_5_6
import com.unuslumen.app.database.migrations.MIGRATION_6_7
import com.unuslumen.app.database.migrations.MIGRATION_7_8
import com.unuslumen.app.database.migrations.MIGRATION_8_9
import com.unuslumen.app.database.migrations.MIGRATION_9_10
import com.unuslumen.app.database.migrations.MIGRATION_10_11
import com.unuslumen.app.database.migrations.MIGRATION_11_12
import com.unuslumen.app.database.migrations.MIGRATION_12_13
import com.unuslumen.app.database.migrations.MIGRATION_13_14
import com.unuslumen.app.database.migrations.MIGRATION_14_15
import com.unuslumen.app.database.migrations.MIGRATION_15_16
import com.unuslumen.app.database.migrations.MIGRATION_16_17
import com.unuslumen.app.database.migrations.MIGRATION_17_18
import com.unuslumen.app.database.migrations.MIGRATION_18_19
import com.unuslumen.app.database.migrations.MIGRATION_19_20
import com.unuslumen.app.database.migrations.MIGRATION_20_21
import com.unuslumen.app.database.migrations.MIGRATION_21_22
import com.unuslumen.app.database.migrations.MIGRATION_22_23
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

private fun createToolResultsFts(db: SupportSQLiteDatabase) {
    try {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS tool_results_fts USING fts5(result_text, content='tool_results', content_rowid='rowid')")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tool_results_ai AFTER INSERT ON tool_results BEGIN INSERT INTO tool_results_fts(rowid, result_text) VALUES (new.rowid, new.result_text); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tool_results_ad AFTER DELETE ON tool_results BEGIN INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text) VALUES ('delete', old.rowid, old.result_text); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS tool_results_au AFTER UPDATE ON tool_results BEGIN INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text) VALUES ('delete', old.rowid, old.result_text); INSERT INTO tool_results_fts(rowid, result_text) VALUES (new.rowid, new.result_text); END")
    } catch (e: Exception) {
        android.util.Log.w("guru", "FTS5 creation for tool_results failed: ${e.message}")
    }
}

val databaseModule = module {

    single {
        Room.databaseBuilder(
            androidContext(),
            guruDatabase::class.java,
            guruDatabase.DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    // On fresh install, Room creates tool_results table from the @Entity annotation
                    // but does NOT create the FTS5 virtual table. Create it here.
                    createToolResultsFts(db)
                }
            })
            .build()
    }

    single { get<guruDatabase>().noteDao() }
    single { get<guruDatabase>().taskDao() }
    single { get<guruDatabase>().journalDao() }
    single { get<guruDatabase>().bookmarkDao() }
    single { get<guruDatabase>().alarmDao() }
    single { get<guruDatabase>().conversationDao() }
    single { get<guruDatabase>().messageDao() }
    single { get<guruDatabase>().memoryFactDao() }
    single { get<guruDatabase>().memoryEdgeDao() }
    single { get<guruDatabase>().memoryEventDao() }
    single { get<guruDatabase>().memoryCrossReferenceDao() }
    single { get<guruDatabase>().conversationThreadDao() }
    single { get<guruDatabase>().hiveMindStateDao() }
    single { get<guruDatabase>().promptSectionDao() }
    single { get<guruDatabase>().promptAmendmentDao() }
    single { get<guruDatabase>().guruDefinedToolDao() }
    single { get<guruDatabase>().guruAutomationDao() }
    single { get<guruDatabase>().luxifyDao() }
    single { get<guruDatabase>().guruHookDao() }
    single { get<guruDatabase>().guruJobDao() }
    single { get<guruDatabase>().guruNoteToSelfDao() }
    single { get<guruDatabase>().guruThoughtCycleDao() }
    single { get<guruDatabase>().guruInsightDao() }
    single { get<guruDatabase>().projectDao() }
    single { get<guruDatabase>().projectMessageDao() }
    single { get<guruDatabase>().projectDocumentDao() }
    single { get<guruDatabase>().projectFactDao() }
    single { get<guruDatabase>().toolResultDao() }
    single { get<guruDatabase>().jobExecutionHistoryDao() }
    single { get<guruDatabase>().seenImageDao() }

}
