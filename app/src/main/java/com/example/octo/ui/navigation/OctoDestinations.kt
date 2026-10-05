package com.example.octo.ui.navigation

/** Bottom-nav destinations; routes are stable strings used by [OctoNavHost]. */
enum class OctoDestination(
    val route: String,
    val label: String,
) {
    BODY("body", "Body"),
    LIBRARY("library", "Library"),
    STATS("stats", "Stats"),
    SETTINGS("settings", "Settings"),
}

/**
 * Player detail route (full-bleed playback): the bottom bar is hidden while a
 * destination whose route starts with this base pattern is current.
 */
const val PLAYER_ROUTE_PATTERN = "player/{videoId}"
const val PLAYER_ROUTE_BASE = "player/"

/**
 * Phase 11 — body-part detail route. Bottom bar hidden while current (same
 * rule as the player route). Arg `bodyPartId` is a [com.example.octo.data.local.BodyPart] id.
 */
const val PART_ROUTE_PATTERN = "part/{bodyPartId}"
const val PART_ROUTE_BASE = "part/"

/**
 * Phase 12 — video detail route: mini player + "today vs this-video" mini body map.
 * Bottom bar hidden while current (same rule as player/part). Arg is a videoId.
 */
const val VIDEO_ROUTE_PATTERN = "video/{videoId}"
const val VIDEO_ROUTE_BASE = "video/"
