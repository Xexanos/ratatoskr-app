/*
 * Ratatoskr Android app
 * Copyright (C) 2026  Ratatoskr contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.xexanos.ratatoskr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.xexanos.ratatoskr.di.AppContainer
import io.github.xexanos.ratatoskr.ui.auth.SignInScreen
import io.github.xexanos.ratatoskr.ui.auth.SignInViewModel
import io.github.xexanos.ratatoskr.ui.connect.ConnectScreen
import io.github.xexanos.ratatoskr.ui.connect.ConnectViewModel
import io.github.xexanos.ratatoskr.ui.library.LibraryScreen
import io.github.xexanos.ratatoskr.ui.library.LibraryViewModel
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingScreen
import io.github.xexanos.ratatoskr.ui.nowplaying.NowPlayingViewModel
import io.github.xexanos.ratatoskr.ui.settings.SettingsScreen
import io.github.xexanos.ratatoskr.ui.settings.SettingsViewModel
import io.github.xexanos.ratatoskr.ui.speakers.SpeakersScreen
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
            ConnectScreen(vm) {
                navController.navigate(Route.SignIn) {
                    popUpTo(Route.Connect) { inclusive = true }
                }
            }
        }

        composable<Route.SignIn> {
            val vm = viewModel<SignInViewModel>(
                factory = containerFactory { SignInViewModel(container.connectionManager) },
            )
            SignInScreen(vm) {
                navController.navigate(Route.Library) {
                    popUpTo(Route.SignIn) { inclusive = true }
                }
            }
        }

        composable<Route.Library> {
            val vm = viewModel<LibraryViewModel>(
                factory = containerFactory { LibraryViewModel(container.connectionManager) },
            )
            LibraryScreen(
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
            SpeakersScreen(vm) {
                navController.navigate(Route.NowPlaying) {
                    popUpTo(Route.Library)
                }
            }
        }

        composable<Route.NowPlaying> {
            val vm = viewModel<NowPlayingViewModel>(
                factory = containerFactory { NowPlayingViewModel(container.connectionManager) },
            )
            NowPlayingScreen(vm) {
                navController.popBackStack(Route.Library, inclusive = false)
            }
        }

        composable<Route.Settings> {
            val vm = viewModel<SettingsViewModel>(
                factory = containerFactory { SettingsViewModel(container.connectionManager) },
            )
            SettingsScreen(
                viewModel = vm,
                onReTrust = {
                    // Clear the whole back stack so Back can't return to authenticated screens.
                    // launchSingleTop is required because Connect is the graph's start
                    // destination in a first-run session: without it, popping the graph and
                    // navigating to the start destination does not land on Connect (the screen
                    // would stay on Settings).
                    navController.navigate(Route.Connect) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
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
