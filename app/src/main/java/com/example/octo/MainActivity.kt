package com.example.octo

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.octo.ui.components.OctoBackground
import com.example.octo.ui.navigation.OctoBottomBar
import com.example.octo.ui.navigation.OctoDestination
import com.example.octo.ui.navigation.OctoNavHost
import com.example.octo.ui.navigation.PART_ROUTE_BASE
import com.example.octo.ui.navigation.PLAYER_ROUTE_BASE
import com.example.octo.ui.navigation.VIDEO_ROUTE_BASE
import androidx.compose.ui.graphics.Color
import com.example.octo.ui.theme.OctoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 15 belt-and-braces portrait lock: the manifest pins portrait on every
        // activity (android:screenOrientation="portrait"); this runtime call guarantees
        // it on devices/launch paths where the manifest attr alone could be bypassed.
        // Ignored where the OS disallows it (large screens with multi-window), where
        // resizeableActivity="false" covers us instead.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        setContent {
            OctoTheme {
                OctoApp()
            }
        }
    }
}

/**
 * App scaffold: custom octopus bottom bar + [OctoNavHost] content.
 *
 * ROOT-CAUSE FIX (Phase 9): exactly ONE [NavHostController] is created here and
 * passed down into [OctoNavHost]. Previously the bar called `rememberNavController()`
 * in [OctoApp] while the NavHost created its own second controller internally —
 * the bar was navigating a controller whose graph was never set, so every tap
 * hit the Phase 6 `runCatching` guard, got a null start destination, and
 * silently no-op'd. One shared controller makes selection state and navigation
 * hit the same graph the content renders from.
 */
@Composable
fun OctoApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Full-bleed playback / detail screens: hide the bottom bar on any player,
    // body-part-detail, or video-detail destination (Phase 11/12).
    val showBottomBar = currentRoute?.startsWith(PLAYER_ROUTE_BASE) != true &&
        currentRoute?.startsWith(PART_ROUTE_BASE) != true &&
        currentRoute?.startsWith(VIDEO_ROUTE_BASE) != true

    val darkTheme = isSystemInDarkTheme()

    // One ambient underwater backdrop behind every destination; screens render
    // on top with transparent backgrounds.
    Box(modifier = Modifier.fillMaxSize()) {
        OctoBackground(darkTheme = darkTheme, modifier = Modifier.fillMaxSize())
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            // Transparent container means contentColorFor() can't derive a content
            // color — without this every Text without an explicit color renders
            // Compose's fallback BLACK on the dark water.
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                if (showBottomBar) {
                    OctoBottomBar(
                        destinations = OctoDestination.entries.toList(),
                        currentRoute = currentRoute,
                        onNavigate = { destination -> navigateToTab(navController, destination) },
                    )
                }
            },
        ) { innerPadding ->
            OctoNavHost(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * Top-level tab navigation: pop everything above the start destination, save
 * and restore per-tab state, never stack duplicates. The start destination is
 * read defensively (graph is set synchronously by NavHost in the same
 * composition, but a tap cannot arrive before that anyway because the bar only
 * renders inside the same composition that hosts the NavHost).
 */
private fun navigateToTab(navController: NavHostController, destination: OctoDestination) {
    val startId = navController.graph.findStartDestination().id
    navController.navigate(destination.route) {
        popUpTo(startId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
