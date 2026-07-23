/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.xexanos.ratatoskr.di.AppContainer
import io.github.xexanos.ratatoskr.ui.auth.SignInScreenHost
import io.github.xexanos.ratatoskr.ui.auth.SignInViewModel
import io.github.xexanos.ratatoskr.ui.connect.ConnectScreenHost
import io.github.xexanos.ratatoskr.ui.connect.ConnectViewModel
import io.github.xexanos.ratatoskr.ui.library.LibraryScreenHost
import io.github.xexanos.ratatoskr.ui.library.LibraryViewModel
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingScreenHost
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingViewModel
import io.github.xexanos.ratatoskr.ui.settings.SettingsScreenHost
import io.github.xexanos.ratatoskr.ui.settings.SettingsViewModel
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersScreenHost
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersViewModel
import kotlinx.serialization.Serializable

/**
 * The single-activity navigation graph as a sealed, type-safe route set (SPEC section 13).
 * Destinations and their arguments are Kotlin types, so an argument like [Speakers.itemId] is
 * carried by the type system rather than a stringly-typed key that could silently be missing.
 */
sealed interface Route {
    @Serializable data object Connect : Route
    @Serializable data object SignIn : Route
    @Serializable data object Library : Route
    @Serializable data class Speakers(val itemId: String) : Route
    @Serializable data object NowPlaying : Route
    @Serializable data object Settings : Route
}

/** Builds a ViewModel from the manual container (SPEC section 12) without a DI framework. */
private inline fun <reified VM : ViewModel> containerFactory(crossinline create: () -> VM) =
    viewModelFactory { initializer { create() } }

@Composable
fun RatatoskrNavHost(container: AppContainer, startDestination: Route) {
    val navController = rememberNavController()

    // A terminal auth failure anywhere in the app (a token lapse the network layer cannot
    // silently recover from, SPEC section 5) sends the user back to sign-in with an empty back
    // stack, so Back cannot reach the now-unauthenticated screens. The trusted server and its
    // certificate are kept - only the tokens were cleared - so this lands on sign-in, not connect.
    val reauthRequired by container.connectionManager.reauthRequired.collectAsStateWithLifecycle()
    LaunchedEffect(reauthRequired) {
        if (reauthRequired) {
            navController.navigate(Route.SignIn) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            container.connectionManager.acknowledgeReauth()
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Connect> {
            val vm = viewModel<ConnectViewModel>(
                factory = containerFactory {
                    ConnectViewModel(
                        container.certificateInspector,
                        container.connectionStore,
                        container.connectionManager,
                    )
                },
            )
            ConnectScreenHost(vm) {
                navController.navigate(Route.SignIn) {
                    popUpTo(Route.Connect) { inclusive = true }
                }
            }
        }

        composable<Route.SignIn> {
            val vm = viewModel<SignInViewModel>(
                factory = containerFactory { SignInViewModel(container.connectionManager) },
            )
            SignInScreenHost(vm) {
                navController.navigate(Route.Library) {
                    popUpTo(Route.SignIn) { inclusive = true }
                }
            }
        }

        composable<Route.Library> {
            val vm = viewModel<LibraryViewModel>(
                factory = containerFactory {
                    LibraryViewModel(container.connectionManager, container.sessionManager, container.speakerManager)
                },
            )
            LibraryScreenHost(
                viewModel = vm,
                onOpenItem = { itemId -> navController.navigate(Route.Speakers(itemId)) },
                onOpenNowPlaying = { navController.navigate(Route.NowPlaying) },
                onOpenSettings = { navController.navigate(Route.Settings) },
            )
        }

        composable<Route.Speakers> { backStackEntry ->
            val itemId = backStackEntry.toRoute<Route.Speakers>().itemId
            val vm = viewModel<SpeakersViewModel>(
                factory = containerFactory { SpeakersViewModel(container.connectionManager, itemId) },
            )
            SpeakersScreenHost(vm) {
                navController.navigate(Route.NowPlaying) {
                    popUpTo(Route.Library)
                }
            }
        }

        composable<Route.NowPlaying> {
            val vm = viewModel<NowPlayingViewModel>(
                factory = containerFactory { NowPlayingViewModel(container.sessionManager) },
            )
            NowPlayingScreenHost(vm) {
                navController.popBackStack(Route.Library, inclusive = false)
            }
        }

        composable<Route.Settings> {
            val vm = viewModel<SettingsViewModel>(
                factory = containerFactory {
                    SettingsViewModel(container.connectionManager, container.coverImages::clear)
                },
            )
            SettingsScreenHost(
                viewModel = vm,
                onReTrust = {
                    // Re-trust: return to the connect screen with an empty back stack, so Back
                    // can't reach the now-untrusted authenticated screens.
                    navController.navigate(Route.Connect) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onSignedOut = {
                    navController.navigate(Route.SignIn) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }
    }
}
