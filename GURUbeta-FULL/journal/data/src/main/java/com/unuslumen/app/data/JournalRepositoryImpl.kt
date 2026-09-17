package com.unuslumen.app.data

import com.unuslumen.app.database.dao.JournalDao
import com.unuslumen.app.database.entity.toJournalEntry
import com.unuslumen.app.database.entity.toJournalEntryEntity
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.repository.JournalRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class JournalRepositoryImpl(
    private val journalDao: JournalDao,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
) : JournalRepository {

    override fun getAllEntries(): Flow<List<JournalEntry>> {
        return journalDao.getAllEntries()
            .flowOn(ioDispatcher)
            .map { entries ->
                entries.map { it.toJournalEntry() }
            }
    }

    override suspend fun getEntry(id: String): JournalEntry? {
        return withContext(ioDispatcher) {
            journalDao.getEntry(id)?.toJournalEntry()
        }
    }

    override suspend fun searchEntries(title: String): List<JournalEntry> {
        return withContext(ioDispatcher) {
            journalDao.getEntriesByTitle(title).map { it.toJournalEntry() }
        }
    }

    override suspend fun addEntry(journal: JournalEntry) {
        return withContext(ioDispatcher) {
            journalDao.insertEntry(journal.toJournalEntryEntity())
        }
    }

    override suspend fun updateEntry(journal: JournalEntry) {
        withContext(ioDispatcher) {
            journalDao.updateEntry(journal.toJournalEntryEntity())
        }
    }

    override suspend fun deleteEntry(journal: JournalEntry) {
        withContext(ioDispatcher) {
            journalDao.deleteEntry(journal.toJournalEntryEntity())
        }
    }
}