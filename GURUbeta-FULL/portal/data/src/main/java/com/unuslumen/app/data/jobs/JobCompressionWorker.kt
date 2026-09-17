package com.unuslumen.app.data.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unuslumen.app.database.dao.JobExecutionHistoryDao
import com.unuslumen.app.domain.repository.JobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import android.util.Base64

class JobCompressionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val jobRepository: JobRepository by inject()
    private val historyDao: JobExecutionHistoryDao by inject()

    companion object {
        private const val TAG = "guru_jobs"
        const val WORK_NAME = "job_compression_periodic"
        const val WORK_NAME_ONE_TIME = "job_compression_one_time"

        fun scheduleDynamic(context: Context, shortestCycleMs: Long) {
            val workManager = WorkManager.getInstance(context)
            if (shortestCycleMs < 15 * 60 * 1000L) {
                // Below periodic floor, use OneTimeWork chaining
                val request = OneTimeWorkRequestBuilder<JobCompressionWorker>()
                    .setInitialDelay(shortestCycleMs, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(WORK_NAME_ONE_TIME, ExistingWorkPolicy.REPLACE, request)
            } else {
                val cycleMinutes = shortestCycleMs / (60 * 1000)
                val request = PeriodicWorkRequestBuilder<JobCompressionWorker>(cycleMinutes, TimeUnit.MINUTES)
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val enabledJobs = jobRepository.getEnabledJobs()
            val compressionJobs = enabledJobs.filter { it.compressionEnabled }

            if (compressionJobs.isEmpty()) {
                Log.d(TAG, "No jobs with compression enabled, skipping compression cycle")
                return@withContext Result.success()
            }

            val now = System.currentTimeMillis()

            for (job in compressionJobs) {
                val destTable = job.destinationTable
                if (destTable == null) continue

                // Compress destination table rows older than retentionWindowHours
                val retentionHours = job.retentionWindowHours
                if (retentionHours != null) {
                    val cutoffTime = now - (retentionHours * 60 * 60 * 1000L)
                    compressDestinationTableRows(destTable, cutoffTime)
                }

                // Compress execution history rows older than historyRetentionHours
                val historyHours = job.historyRetentionHours
                if (historyHours != null) {
                    val cutoffTime = now - (historyHours * 60 * 60 * 1000L)
                    compressHistoryRows(job.id, cutoffTime)
                }
            }

            // Self-chain for next cycle based on shortest compressionCycleMs
            val shortestCycle = compressionJobs.mapNotNull { it.compressionCycleMs }.minOrNull()
            if (shortestCycle != null && shortestCycle < 15 * 60 * 1000L) {
                scheduleDynamic(applicationContext, shortestCycle)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Compression worker error: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun compressDestinationTableRows(tableName: String, cutoffTimestamp: Long) {
        try {
            val guruDb = org.koin.core.context.GlobalContext.get()
                .get<com.unuslumen.app.database.guruDatabase>()
            val db = guruDb.openHelper.writableDatabase

            // Find rows with a 'content' or 'result' column that are older than the cutoff and not yet compressed
            // We try common column names and compress whichever exists
            val columns = db.query("PRAGMA table_info($tableName)")
            val columnNames = mutableListOf<String>()
            columns.use { c ->
                while (c.moveToNext()) {
                    columnNames.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
            }

            val contentColumn = when {
                "content" in columnNames -> "content"
                "result" in columnNames -> "result"
                "result_text" in columnNames -> "result_text"
                else -> return // No content column to compress
            }

            val hasCompressedFlag = "compressed" in columnNames

            // Query rows older than cutoff
            val timeColumn = when {
                "timestamp" in columnNames -> "timestamp"
                "polled_at" in columnNames -> "polled_at"
                "createdAt" in columnNames -> "createdAt"
                else -> return
            }

            val whereClause = if (hasCompressedFlag) {
                "$timeColumn < ? AND compressed = 0"
            } else {
                "$timeColumn < ?"
            }

            val cursor = db.query("SELECT rowid, $contentColumn FROM $tableName WHERE $whereClause", arrayOf(cutoffTimestamp))
            cursor.use { c ->
                while (c.moveToNext()) {
                    val rowid = c.getLong(0)
                    val content = c.getString(1) ?: continue
                    if (content.isBlank()) continue

                    val compressed = compressString(content)
                    if (hasCompressedFlag) {
                        db.execSQL("UPDATE $tableName SET $contentColumn = ?, compressed = 1 WHERE rowid = ?", arrayOf<Any?>(compressed, rowid))
                    } else {
                        db.execSQL("UPDATE $tableName SET $contentColumn = ? WHERE rowid = ?", arrayOf<Any?>(compressed, rowid))
                    }
                }
            }
            Log.d(TAG, "Compressed rows in $tableName older than $cutoffTimestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress rows in table $tableName: ${e.message}")
        }
    }

    private suspend fun compressHistoryRows(jobId: String, cutoffTimestamp: Long) {
        try {
            val uncompressedRows = historyDao.getUncompressedOlderThan(jobId, cutoffTimestamp)
            for (row in uncompressedRows) {
                val compressed = row.resultSummary?.let { compressString(it) }
                if (compressed != null) {
                    historyDao.markCompressed(row.id, compressed)
                }
            }
            Log.d(TAG, "Compressed ${uncompressedRows.size} history rows for job $jobId older than $cutoffTimestamp")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress history rows for job $jobId: ${e.message}")
        }
    }

    private fun compressString(input: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input.toByteArray(Charsets.UTF_8)) }
        return "gzip:" + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    private fun decompressString(compressed: String): String {
        if (!compressed.startsWith("gzip:")) return compressed
        val data = Base64.decode(compressed.substring(6), Base64.NO_WRAP)
        GZIPInputStream(ByteArrayInputStream(data)).use {gis ->
            return gis.readBytes().toString(Charsets.UTF_8)
        }
    }
}