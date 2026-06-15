package com.edo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.edo.app.ui.chat.ChatScreen
import com.edo.app.ui.files.FilesScreen
import com.edo.app.ui.projects.ProjectsScreen
import com.edo.app.ui.settings.SettingsScreen
import com.edo.app.ui.threads.ThreadsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EdoApp).container
        setContent {
            val isDark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("chat") {
                            ChatScreen(
                                container = container,
                                onOpenSettings = { nav.navigate("settings") },
                                onOpenProjects = { nav.navigate("projects") },
                                onOpenThreads = { nav.navigate("threads") },
                                onOpenFiles = { nav.navigate("files") },
                            )
                        }
                        composable("projects") {
                            ProjectsScreen(
                                container = container,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("threads") {
                            ThreadsScreen(
                                container = container,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("files") {
                            FilesScreen(
                                container = container,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(container = container, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
