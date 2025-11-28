package suwayomi.tachidesk.manga.impl.track.tracker.mangabaka.dto

import kotlinx.serialization.Serializable

@Serializable
data class MBSearchResponse(
    val status: Int,
    val pagination: MBPagination,
    val data: List<MBRecord>,
    val excluded_count: Int? = null,
)

@Serializable
data class MBLibrarySearchResponse(
    val status: Int,
    val pagination: MBPagination,
    val data: List<MBListItem>,
    val excluded_count: Int? = null,
)

@Serializable
data class MBPagination(
    val count: Int,
    val page: Int,
    val limit: Int,
    val next: String? = null,
    val previous: String? = null,
)
