package suwayomi.tachidesk.manga.impl.track.tracker.mangabaka

import suwayomi.tachidesk.manga.impl.track.tracker.DeletableTracker
import suwayomi.tachidesk.manga.impl.track.tracker.Tracker
import suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto.MBListItem
import suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto.MBRecord
import suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto.copyTo
import suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto.toTrackSearch
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track
import suwayomi.tachidesk.manga.impl.track.tracker.model.TrackSearch
import kotlin.Exception

/**
 * MangaBaka tracker implementation for Suwayomi.
 *
 * Patterned after other suwayomi trackers (e.g. MangaUpdates).
 *
 * Note:
 * - Uses Int constants for statuses to match suwayomi Tracker API.
 * - Expects there to be a MangaBakaApi and MangaBakaInterceptor present that work with
 *   suwayomi.tachidesk.manga.impl.track.tracker.model.Track and TrackSearch (or provide conversion).
 */
class MangaBaka(
    id: Int,
) : Tracker(id, "MangaBaka"),
    DeletableTracker {

    companion object {
        const val READING = 1
        const val COMPLETED = 2
        const val PAUSED = 3
        const val DROPPED = 4
        const val PLAN_TO_READ = 5
        const val REREADING = 6

        private val STATUS_SET = setOf(READING, COMPLETED, PAUSED, DROPPED, PLAN_TO_READ, REREADING)
        // Use a plain Kotlin List instead of kotlinx ImmutableList to avoid extra dependency
        private val SCORE_LIST: List<String> =
            (0..100).map { i -> "%.1f".format(i / 10.0) }
    }

    private val interceptor by lazy { MangaBakaInterceptor(this) }
    private val api by lazy { MangaBakaApi(interceptor, client) }

    override fun getLogo(): String = "/static/tracker/manga_baka.png"

    override val supportsPrivateTracking: Boolean = true

    override fun getStatusList(): List<Int> = listOf(READING, COMPLETED, PAUSED, DROPPED, PLAN_TO_READ, REREADING)

    override fun getStatus(status: Int): String? =
        when (status) {
            READING -> "Reading"
            COMPLETED -> "Completed"
            PAUSED -> "Paused"
            DROPPED -> "Dropped"
            PLAN_TO_READ -> "Plan to read"
            REREADING -> "Rereading"
            else -> null
        }

    override fun getReadingStatus(): Int = READING
    override fun getRereadingStatus(): Int = REREADING
    override fun getCompletionStatus(): Int = COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST
    override fun indexToScore(index: Int): Double = SCORE_LIST[index].toDouble()
    override fun displayScore(track: Track): String = "%.1f".format(track.score)

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        val previousListItem: MBListItem = try {
            api.getLibraryEntryWithSeries(track.remote_id) ?: return track
        } catch (_: Exception) {
            return track
        }

        val releaseIsCompleted = previousListItem.Series?.status == "completed"
        val total = previousListItem.Series?.total_chapters?.toIntOrNull() ?: 0
        if (releaseIsCompleted && track.last_chapter_read > total && total > 0) {
            track.last_chapter_read = total.toDouble()
        }

        val previousStatus = previousListItem.state?.let {
            when (it) {
                "reading" -> READING
                "completed" -> COMPLETED
                "paused" -> PAUSED
                "dropped" -> DROPPED
                "plan_to_read" -> PLAN_TO_READ
                "rereading" -> REREADING
                else -> PLAN_TO_READ
            }
        } ?: PLAN_TO_READ

        if (previousStatus == COMPLETED && track.status != COMPLETED) {
            when (track.status) {
                READING -> if (total > 0) track.last_chapter_read = (total - 1).toDouble()
                PLAN_TO_READ -> track.last_chapter_read = 0.0
            }
        }

        val progress = track.last_chapter_read.toInt()
        val statusEligible = track.status == READING || track.status == PLAN_TO_READ
        if (progress == total && total > 0 && releaseIsCompleted && statusEligible) {
            track.status = COMPLETED
            if (track.finished_reading_date == 0L) {
                track.finished_reading_date = System.currentTimeMillis()
            }
        }

        if (track.status != COMPLETED && didReadChapter) {
            if (track.started_reading_date == 0L) {
                track.started_reading_date = System.currentTimeMillis()
            }
        }

        val previousRereads = previousListItem.number_of_rereads ?: 0
        val wasRereading = previousListItem.state == "rereading"
        var rereadsToSend: Int? = null
        if (track.status == COMPLETED && wasRereading) {
            rereadsToSend = previousRereads + 1
        }

        try {
            api.updateSeriesEntryPatch(track, rereadsToSend)
        } catch (_: Exception) {
            // best-effort only
        }

        track.tracking_url = "https://mangabaka.dev/${track.remote_id}"
        return track
    }

    override suspend fun delete(track: Track) {
        try {
            api.deleteSeriesEntry(track.remote_id)
        } catch (_: Exception) {
            // ignore
        }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val item: MBListItem? = try {
            api.getSeriesListItem(track.remote_id)
        } catch (_: Exception) {
            null
        }

        if (item != null) {
            try {
                item.copyTo(track)
            } catch (_: Exception) {
            }

            val seriesRecord: MBRecord? = try {
                api.getSeries(track.remote_id)
            } catch (_: Exception) {
                item.Series
            } ?: item.Series

            // totalFromSeries as Int to match Track.total_chapters type (avoid Long/Int mismatch)
            val totalFromSeries: Int = seriesRecord?.total_chapters?.toIntOrNull() ?: 0
            if (totalFromSeries > 0) {
                track.total_chapters = totalFromSeries
                if (seriesRecord?.status == "completed" && track.last_chapter_read > totalFromSeries.toDouble()) {
                    track.last_chapter_read = totalFromSeries.toDouble()
                }
            }

            try {
                autoCompleteIfFinished(track, seriesRecord)
            } catch (_: Exception) {
            }

            if (track.status == 0 ||
                item.state.isNullOrBlank() ||
                !STATUS_SET.contains(track.status)
            ) {
                track.status = PLAN_TO_READ
            }
            track.tracking_url = "https://mangabaka.dev/${track.remote_id}"
            return track
        }

        // create new entry
        track.score = 0.0
        try {
            api.addSeriesEntry(track, hasReadChapters)
        } catch (_: Exception) {
        }

        val seriesRecord: MBRecord? = try {
            api.getSeries(track.remote_id)
        } catch (_: Exception) {
            null
        }

        val totalFromSeries: Int = seriesRecord?.total_chapters?.toIntOrNull() ?: 0
        if (totalFromSeries > 0) {
            track.total_chapters = totalFromSeries
            if (seriesRecord?.status == "completed" && track.last_chapter_read > totalFromSeries.toDouble()) {
                track.last_chapter_read = totalFromSeries.toDouble()
            }
        }
        try {
            autoCompleteIfFinished(track, seriesRecord)
        } catch (_: Exception) {
        }
        track.status = PLAN_TO_READ
        track.tracking_url = "https://mangabaka.dev/${track.remote_id}"
        return track
    }

    private fun autoCompleteIfFinished(track: Track, series: MBRecord?) {
        val releaseIsCompleted = series?.status == "completed"
        val progress = track.last_chapter_read.toInt()
        val total = series?.total_chapters?.toIntOrNull() ?: 0
        val statusEligible = track.status == READING || track.status == PLAN_TO_READ
        if (progress == total && total > 0 && releaseIsCompleted && statusEligible) {
            track.status = COMPLETED
            if (track.finished_reading_date == 0L) {
                track.finished_reading_date = System.currentTimeMillis()
            }
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        // Support searching by id: prefix (like MyAnimeList)
        if (query.startsWith("id:")) {
            val idPart = query.substringAfter("id:")
            val remoteId = idPart.toLongOrNull()
            if (remoteId != null) {
                val rec: MBRecord? = try { api.getSeries(remoteId) } catch (_: Exception) { null }
                return if (rec != null) listOf(rec.toTrackSearch(id)) else emptyList()
            }
        }

        val results: List<MBRecord> = try {
            api.search(query)
        } catch (_: Exception) {
            emptyList()
        }
        return results.map { it.toTrackSearch(id) }
    }

    override suspend fun refresh(track: Track): Track {
        val item: MBListItem? = try {
            api.getSeriesListItem(track.remote_id)
        } catch (_: Exception) {
            null
        }

        if (item != null) {
            try {
                item.copyTo(track)
            } catch (_: Exception) {
            }

            val seriesRecord: MBRecord? = try {
                api.getSeries(track.remote_id)
            } catch (_: Exception) {
                item.Series
            } ?: item.Series

            val totalFromSeries: Int = seriesRecord?.total_chapters?.toIntOrNull() ?: 0
            if (totalFromSeries > 0) {
                track.total_chapters = totalFromSeries
                if (seriesRecord?.status == "completed" && track.last_chapter_read > totalFromSeries.toDouble()) {
                    track.last_chapter_read = totalFromSeries.toDouble()
                }
            }
            try {
                autoCompleteIfFinished(track, seriesRecord)
            } catch (_: Exception) {
            }
            track.tracking_url = "https://mangabaka.dev/${track.remote_id}"
            return track
        }

        val seriesOnly: MBRecord? = try {
            api.getSeries(track.remote_id)
        } catch (_: Exception) {
            null
        }
        if (seriesOnly != null) {
            val totalFromSeries: Int = seriesOnly.total_chapters?.toIntOrNull() ?: 0
            if (totalFromSeries > 0) {
                track.total_chapters = totalFromSeries
                if (seriesOnly.status == "completed" && track.last_chapter_read > totalFromSeries.toDouble()) {
                    track.last_chapter_read = totalFromSeries.toDouble()
                }
            }
            try {
                autoCompleteIfFinished(track, seriesOnly)
            } catch (_: Exception) {
            }
        }
        return track
    }

    override suspend fun login(username: String, password: String) {
        // MangaBaka uses a PAT in the password field; we store username and PAT like other trackers
        saveCredentials(username, password)
        interceptor.newAuth(password)
        val ok = try {
            api.testLibraryAuth()
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            logout()
            throw Throwable("PAT is invalid or authentication failed")
        }
    }

    override fun logout() {
        super.logout()
        // ensure stored credentials are cleared
        trackPreferences.setTrackCredentials(this, "", "")
        interceptor.newAuth(null)
    }

    fun restoreSession(): String? = trackPreferences.getTrackPassword(this)?.ifBlank { null }
}
