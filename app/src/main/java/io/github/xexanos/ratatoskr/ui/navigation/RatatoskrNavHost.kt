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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

/** The single-activity navigation graph (SPEC section 13). */
object Routes {
    const val CONNECT = "connect"
    const val SIGN_IN = "signin"
    const val LIBRARY = "library"
    const val SPEAKERS = "speakers"
    const val NOW_PLAYING = "nowplaying"
    const val SETTINGS = "settings"
    const val ITEM_ID_ARG = "itemId"

    fun speakers(itemId: String) = "$SPEAKERS/$itemId"
}

/** Builds a ViewModel from the manual container (SPEC section 12) without a DI framework. */
private inline fun <reified VM : ViewModel> containerFactory(crossinline create: () -> VM) =
    viewModelFactory { initializer { create() } }

@Composable
fun RatatoskrNavHost(container: AppContainer, startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.CONNECT) {
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
                navController.navigate(Routes.SIGN_IN) {
                    popUpTo(Routes.CONNECT) { inclusive = true }
                }
            }
        }

        composable(Routes.SIGN_IN) {
            val vm = viewModel<SignInViewModel>(
                factory = containerFactory { SignInViewModel(container.connectionManager) },
            )
            SignInScreen(vm) {
                navController.navigate(Routes.LIBRARY) {
                    popUpTo(Routes.SIGN_IN) { inclusive = true }
                }
            }
        }

        composable(Routes.LIBRARY) {
            val vm = viewModel<LibraryViewModel>(
                factory = containerFactory { LibraryViewModel(container.connectionManager) },
            )
            LibraryScreen(
                viewModel = vm,
                onOpenItem = { itemId -> navController.navigate(Routes.speakers(itemId)) },
                onOpenNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = "${Routes.SPEAKERS}/{${Routes.ITEM_ID_ARG}}",
            arguments = listOf(navArgument(Routes.ITEM_ID_ARG) { type = NavType.StringType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString(Routes.ITEM_ID_ARG).orEmpty()
            val vm = viewModel<SpeakersViewModel>(
                factory = containerFactory { SpeakersViewModel(container.connectionManager, itemId) },
            )
            SpeakersScreen(vm) {
                navController.navigate(Routes.NOW_PLAYING) {
                    popUpTo(Routes.LIBRARY)
                }
            }
        }

        composable(Routes.NOW_PLAYING) {
            val vm = viewModel<NowPlayingViewModel>(
                factory = containerFactory { NowPlayingViewModel(container.connectionManager) },
            )
            NowPlayingScreen(vm) {
                navController.popBackStack(Routes.LIBRARY, inclusive = false)
            }
        }

        composable(Routes.SETTINGS) {
            val vm = viewModel<SettingsViewModel>(
                factory = containerFactory { SettingsViewModel(container.connectionManager) },
            )
            SettingsScreen(
                viewModel = vm,
                onReTrust = {
                    navController.navigate(Routes.CONNECT) { popUpTo(0) }
                },
                onSignedOut = {
                    navController.navigate(Routes.SIGN_IN) { popUpTo(0) }
                },
            )
        }
    }
}
