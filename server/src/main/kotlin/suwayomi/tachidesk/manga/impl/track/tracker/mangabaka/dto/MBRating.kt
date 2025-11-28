package suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto

import kotlinx.serialization.Serializable
import suwayomi.tachidesk.manga.impl.track.tracker.model.Track

@Serializable
data class MBRating(
    val rating: Double? = null,
)

fun MBRating.copyTo(track: Track): Track {
    return track.apply {
        this.score = rating ?: 0.0
    }
}
