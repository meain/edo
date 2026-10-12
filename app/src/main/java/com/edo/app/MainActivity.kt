package com.edo.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.edo.app.ui.chat.ChatScreen
import com.edo.app.ui.files.FilesScreen
import com.edo.app.ui.projects.ProjectsScreen
import com.edo.app.ui.settings.SettingsScreen
import com.edo.app.ui.share.ShareScreen
import com.edo.app.ui.theme.EdoTheme
import com.edo.app.ui.threads.ThreadsScreen

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /** Turns an ACTION_SEND / ACTION_SEND_MULTIPLE intent into pending shared content. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val text = if (subject.isNotBlank() && !body.contains(subject)) "$subject\n\n$body".trim() else body
        if (text.isBlank() && uris.isEmpty()) return
        (application as EdoApp).container.offerShare(text, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully transparent system bars (no scrim) so the Compose TopAppBar /
        // bottom input bar paint right up to the screen edges without a seam.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val container = (application as EdoApp).container
        // On rotation etc. the original share intent is redelivered; only handle it once.
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            val isDark = isSystemInDarkTheme()
            EdoTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    val share by container.pendingShare.collectAsState()
                    // A fresh, not-yet-placed share opens the destination picker.
                    LaunchedEffect(share?.id) {
                        if (share?.confirmed == false) {
                            nav.navigate("share") { launchSingleTop = true }
                        }
                    }
                    NavHost(navController = nav, startDestination = "chat") {
                        composable("share") {
                            ShareScreen(
                                container = container,
                                onOpenProjects = { nav.navigate("projects") },
                                onCancel = {
                                    container.clearShare()
                                    nav.popBackStack()
                                },
                                onDone = { nav.popBackStack("chat", inclusive = false) },
                            )
                        }
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
