// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.ToolObservationEntity

/**
 * ToolObservationDao — every read and write on the structured observation log.
 *
 * Every listing query orders timestamp DESC, so "most recent N" needs no
 * secondary sort and the (package_name, timestamp) index carries the package
 * reads without a table scan.
 *
 * search matches the package and tool name with LIKE rather than FTS5, for the
 * same reason ToolResultDao's fallback search does: the log is small, the query
 * is a name fragment rather than prose, and a LIKE read can never fail on a
 * device whose SQLite build lacks the FTS module. A query is matched
 * case-insensitively because the column collation is not set and callers type
 * package and tool names in whatever case they remember.
 */
@Dao
interface ToolObservationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(observation: ToolObservationEntity)

    @Query("SELECT * FROM tool_observations WHERE package_name = :packageName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByPackage(packageName: String, limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations WHERE package_name = :packageName AND success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailuresByPackage(packageName: String, limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations WHERE tool_name = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByToolName(toolName: String, limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations WHERE tool_name = :toolName AND success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailuresByToolName(toolName: String, limit: Int): List<ToolObservationEntity>

    /**
     * Both filters at once. The tool that retrieves observations accepts a
     * package, a tool name, or both, and "both" is the query that actually
     * answers "what did this tool do to this screen last time"; running the two
     * single-filter reads and intersecting them in memory would return fewer
     * rows than the limit allows and lose the tail.
     */
    @Query("SELECT * FROM tool_observations WHERE package_name = :packageName AND tool_name = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByPackageAndToolName(packageName: String, toolName: String, limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations WHERE package_name = :packageName AND tool_name = :toolName AND success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailuresByPackageAndToolName(packageName: String, toolName: String, limit: Int): List<ToolObservationEntity>

    @Query("SELECT * FROM tool_observations WHERE package_name LIKE '%' || :query || '%' OR tool_name LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<ToolObservationEntity>

    @Query("DELETE FROM tool_observations WHERE timestamp < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    /**
     * Retention for the observation log, run from the same boot-and-daily pass
     * that prunes tool results. Ninety days matches tool_results' own window,
     * so the two logs age together: a failure recorded in one is still
     * comparable against the other for as long as either survives. Rows are
     * deleted by age only — the log is not capped by count, because the rows
     * worth keeping are the rare failures, not the volume.
     */
    suspend fun pruneObservations(currentTime: Long) {
        val retentionMillis = 90L * 24 * 60 * 60 * 1000
        deleteOlderThan(currentTime - retentionMillis)
    }
}
