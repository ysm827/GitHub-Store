package zed.rainxch.githubstore

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.koin.compose.viewmodel.koinViewModel
import zed.rainxch.core.presentation.theme.GithubStoreTheme
import zed.rainxch.core.presentation.utils.ApplyAndroidSystemBars
import zed.rainxch.core.presentation.utils.ObserveAsEvents
import zed.rainxch.githubstore.app.components.RateLimitDialog
import zed.rainxch.githubstore.app.components.SessionExpiredDialog
import zed.rainxch.githubstore.app.deeplink.DeepLinkDestination
import zed.rainxch.githubstore.app.deeplink.DeepLinkParser
import zed.rainxch.githubstore.app.desktop.KeyboardNavigation
import zed.rainxch.githubstore.app.desktop.KeyboardNavigationEvent
import zed.rainxch.githubstore.app.navigation.AppNavigation
import zed.rainxch.githubstore.app.navigation.GithubStoreGraph
import zed.rainxch.githubstore.app.navigation.getCurrentScreen

@Composable
fun App(deepLinkUri: String? = null) {
    val viewModel: MainViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val currentScreen = navController.currentBackStackEntryAsState().value.getCurrentScreen()

    LaunchedEffect(deepLinkUri) {
        deepLinkUri?.let { uri ->
            when (val destination = DeepLinkParser.parse(uri)) {
                is DeepLinkDestination.Repository -> {
                    navController.navigate(
                        GithubStoreGraph.DetailsScreen(
                            owner = destination.owner,
                            repo = destination.repo,
                        ),
                    )
                }

                DeepLinkDestination.Apps -> {
                    // Pending-install notification dropped us here.
                    // Navigate to the apps tab so the user can finish
                    // the deferred install from the row.
                    navController.navigate(GithubStoreGraph.AppsScreen) {
                        popUpTo(GithubStoreGraph.HomeScreen) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                DeepLinkDestination.None -> {
                    // ignore unrecognized deep links
                }
            }
        }
    }

    ObserveAsEvents(KeyboardNavigation.events) { event ->
        when (event) {
            KeyboardNavigationEvent.OnCtrlFClick -> {
                if (currentScreen !is GithubStoreGraph.SearchScreen) {
                    navController.navigate(GithubStoreGraph.SearchScreen) {
                        popUpTo(GithubStoreGraph.HomeScreen) {
                            saveState = true
                        }

                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }

    GithubStoreTheme(
        fontTheme = state.currentFontTheme,
        appTheme = state.currentColorTheme,
        isAmoledTheme = state.isAmoledTheme,
        isDarkTheme = state.isDarkTheme ?: isSystemInDarkTheme(),
    ) {
        ApplyAndroidSystemBars(state.isDarkTheme)

        if (state.showRateLimitDialog && state.rateLimitInfo != null) {
            RateLimitDialog(
                rateLimitInfo = state.rateLimitInfo!!,
                isAuthenticated = state.isLoggedIn,
                onDismiss = {
                    viewModel.onAction(MainAction.DismissRateLimitDialog)
                },
                onSignIn = {
                    viewModel.onAction(MainAction.DismissRateLimitDialog)

                    navController.navigate(GithubStoreGraph.AuthenticationScreen)
                },
            )
        }

        if (state.showSessionExpiredDialog) {
            SessionExpiredDialog(
                onDismiss = {
                    viewModel.onAction(MainAction.DismissSessionExpiredDialog)
                },
                onSignIn = {
                    viewModel.onAction(MainAction.DismissSessionExpiredDialog)
                    navController.navigate(GithubStoreGraph.AuthenticationScreen)
                },
            )
        }

        AppNavigation(
            navController = navController,
            isLiquidGlassEnabled = state.isLiquidGlassEnabled,
            isScrollbarEnabled = state.isScrollbarEnabled,
        )
    }
}
