package com.phoneagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.phoneagent.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneAgentApp() {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBar = !(currentRoute == "chat" && imeVisible)

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    tonalElevation = 4.dp
                ) {
                    NavigationBarItem(
                        selected = currentRoute == "chat",
                        onClick = { navController.navigate("chat") { popUpTo("chat") { inclusive = true } } },
                        icon = {
                            Icon(
                                if (currentRoute == "chat") Icons.Default.Chat else Icons.Outlined.Chat,
                                contentDescription = "对话"
                            )
                        },
                        label = { Text("对话") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "tasks",
                        onClick = { navController.navigate("tasks") { popUpTo("chat") } },
                        icon = {
                            Icon(
                                if (currentRoute == "tasks") Icons.Default.Schedule else Icons.Outlined.Schedule,
                                contentDescription = "任务"
                            )
                        },
                        label = { Text("任务") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { popUpTo("chat") } },
                        icon = {
                            Icon(
                                if (currentRoute == "settings") Icons.Default.Settings else Icons.Outlined.Settings,
                                contentDescription = "设置"
                            )
                        },
                        label = { Text("设置") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding)
        ) {
            composable("chat") { ChatScreen() }
            composable("prompts") {
                PromptScreen(
                    onNavigateToChat = {
                        navController.navigate("chat") { popUpTo("chat") { inclusive = true } }
                    }
                )
            }
            composable("tasks") { TaskScreen() }
            composable("skills") { SkillScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
