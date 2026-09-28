package com.timelimittracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.timelimittracker.ui.addapp.AddAppScreen
import com.timelimittracker.ui.debug.DebugLogScreen
import com.timelimittracker.ui.home.HomeScreen
import com.timelimittracker.ui.onboarding.OnboardingScreen
import com.timelimittracker.ui.templates.TemplatesScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD_APP = "add_app"
    const val TEMPLATES = "templates"
    const val DEBUG_LOG = "debug_log"
}

@Composable
fun AppNavGraph(startDestination: String) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            val fromHome = navController.previousBackStackEntry != null
            OnboardingScreen(
                onAllRequiredGranted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                onBack = if (fromHome) ({ navController.popBackStack() }) else null,
                autoNavigate = !fromHome
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAddApp = { navController.navigate(Routes.ADD_APP) },
                onEditApp = { navController.navigate(Routes.ADD_APP) },
                onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                onOpenPermissions = { navController.navigate(Routes.ONBOARDING) },
                onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) }
            )
        }
        composable(Routes.ADD_APP) {
            AddAppScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TEMPLATES) {
            TemplatesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onBack = { navController.popBackStack() })
        }
    }
}
