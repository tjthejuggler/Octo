package com.example.octo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.octo.ui.body.BodyScreen
import com.example.octo.ui.part.BodyPartDetailScreen
import com.example.octo.ui.library.LibraryScreen
import com.example.octo.ui.player.PlayerScreen
import com.example.octo.ui.settings.SettingsScreen
import com.example.octo.ui.stats.StatsScreen
import com.example.octo.ui.video.VideoDetailScreen

/**
 * Top-level [NavHost] wiring the four bottom-nav destinations plus the Phase 4
 * player route ("player/{videoId}") that the Library's Play button navigates to.
 *
 * ROOT-CAUSE FIX (Phase 9): the [NavHostController] is now passed in from
 * [com.example.octo.MainActivity] instead of being created here — the old
 * internal `rememberNavController()` produced a second controller that the
 * bottom bar (bound to the first one) could not influence, so taps did nothing.
 */
@Composable
fun OctoNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = OctoDestination.BODY.route,
        modifier = modifier,
    ) {
        composable(OctoDestination.BODY.route) {
            BodyScreen(onOpenPart = { part -> navController.navigate("part/${part.id}") })
        }
        composable(OctoDestination.LIBRARY.route) {
            // Phase 12: card taps open the video detail screen (it hosts the
            // poster + today-vs-this-video body map); Phase 26: tapping the
            // poster starts playback AND goes to the immersive player route.
            LibraryScreen(onOpenVideo = { videoId -> navController.navigate("video/$videoId") })
        }
        composable(OctoDestination.STATS.route) { StatsScreen() }
        composable(OctoDestination.SETTINGS.route) { SettingsScreen() }

        // Phase 4 — real Media3 playback + session tracking. The bottom bar is
        // hidden while this route is current (full-bleed playback).
        composable(
            route = PLAYER_ROUTE_PATTERN,
            arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
        ) { entry ->
            val videoId = entry.arguments?.getString("videoId").orEmpty()
            PlayerScreen(
                videoId = videoId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // Phase 12 — video detail: mini player + "today vs this video" mini body map.
        // Maximize pushes the Phase 4 full-screen player on top (back returns here).
        composable(
            route = VIDEO_ROUTE_PATTERN,
            arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
        ) { entry ->
            val videoId = entry.arguments?.getString("videoId").orEmpty()
            VideoDetailScreen(
                videoId = videoId,
                onNavigateBack = { navController.popBackStack() },
                onMaximize = { id -> navController.navigate("player/$id") },
                onOpenPart = { partId -> navController.navigate("part/$partId") },
            )
        }

        // Phase 11 — per-body-part detail (videos + stats + history for one part).
        // Phase 12: video taps now open the video detail screen.
        composable(
            route = PART_ROUTE_PATTERN,
            arguments = listOf(navArgument("bodyPartId") { type = NavType.StringType }),
        ) { entry ->
            val partId = entry.arguments?.getString("bodyPartId").orEmpty()
            BodyPartDetailScreen(
                partId = partId,
                onNavigateBack = { navController.popBackStack() },
                onOpenVideo = { videoId -> navController.navigate("video/$videoId") },
            )
        }
    }
}
