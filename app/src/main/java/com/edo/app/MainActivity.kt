package com.edo.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.IntentCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import com.edo.app.ui.chat.ChatScreen
import com.edo.app.schedule.ScheduleNotifier
import com.edo.app.ui.PanelSide
import com.edo.app.ui.SwipePanels
import com.edo.app.ui.files.FilesScreen
import com.edo.app.ui.rememberSwipePanelsState
import com.edo.app.ui.projects.ProjectsScreen
import com.edo.app.ui.schedules.SchedulesScreen
import com.edo.app.ui.settings.SettingsScreen
import com.edo.app.ui.share.ShareScreen
import com.edo.app.ui.theme.EdoTheme
import com.edo.app.ui.threads.ThreadsScreen

private const val NAV_MS = 200

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleOpenThreadIntent(intent)
    }

    /** A scheduled-run notification was tapped: show the thread it created. */
    private fun handleOpenThreadIntent(intent: Intent?) {
        if (intent == null || !intent.hasExtra(ScheduleNotifier.EXTRA_THREAD_ID)) return
        val projectId = intent.getLongExtra(ScheduleNotifier.EXTRA_PROJECT_ID, -1L)
        val threadId = intent.getLongExtra(ScheduleNotifier.EXTRA_THREAD_ID, -1L)
        // Consume the extras so a rotation doesn't re-apply them over later navigation.
        intent.removeExtra(ScheduleNotifier.EXTRA_THREAD_ID)
        intent.removeExtra(ScheduleNotifier.EXTRA_PROJECT_ID)
        if (projectId <= 0) return
        val container = (application as EdoApp).container
        container.setActiveProject(projectId)
        if (threadId > 0) container.setActiveThread(threadId)
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
        if (savedInstanceState == null) {
            handleShareIntent(intent)
            handleOpenThreadIntent(intent)
        }
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
                    // Screens' back/select handlers can fire twice (double tap, or a tap while the
                    // exit transition is still showing). An unguarded second pop removes the start
                    // destination and leaves NavHost empty — a blank screen until the app restarts.
                    val popBack: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
                    // Likewise a double tap on a menu button must not push the same screen twice.
                    val open: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
                    // Short slide + fade; the default 700 ms crossfade feels sluggish. The outgoing
                    // screen only drifts a little and the pop reverses the push.
                    val spec = tween<Float>(NAV_MS, easing = FastOutSlowInEasing)
                    val offsetSpec = tween<IntOffset>(NAV_MS, easing = FastOutSlowInEasing)
                    NavHost(
                        navController = nav,
                        startDestination = "chat",
                        enterTransition = { slideIn(offsetSpec) { IntOffset(it.width / 6, 0) } + fadeIn(spec) },
                        exitTransition = { slideOut(offsetSpec) { IntOffset(-it.width / 10, 0) } + fadeOut(spec) },
                        popEnterTransition = { slideIn(offsetSpec) { IntOffset(-it.width / 10, 0) } + fadeIn(spec) },
                        popExitTransition = { slideOut(offsetSpec) { IntOffset(it.width / 6, 0) } + fadeOut(spec) },
                    ) {
                        composable("share") {
                            ShareScreen(
                                container = container,
                                onOpenProjects = { open("projects") },
                                onCancel = {
                                    container.clearShare()
                                    popBack()
                                },
                                onDone = { nav.popBackStack("chat", inclusive = false) },
                            )
                        }
                        composable("chat") {
                            // Chats slide in from the left, files from the right, both following the finger.
                            val panels = rememberSwipePanelsState()
                            val hasProject = container.activeProjectId.collectAsState().value > 0
                            SwipePanels(
                                state = panels,
                                rightEnabled = hasProject,
                                left = { ThreadsScreen(container = container, onBack = panels::close) },
                                right = { FilesScreen(container = container, onBack = panels::close) },
                            ) {
                                ChatScreen(
                                    container = container,
                                    onOpenSettings = { open("settings") },
                                    onOpenProjects = { open("projects") },
                                    onOpenThreads = { panels.open(PanelSide.Left) },
                                    onOpenFiles = { panels.open(PanelSide.Right) },
                                    onOpenSchedules = { open("schedules") },
                                )
                            }
                        }
                        composable("schedules") {
                            SchedulesScreen(container = container, onBack = popBack)
                        }
                        dialog("projects") {
                            ProjectsScreen(container = container, onBack = popBack)
                        }
                        composable("settings") {
                            SettingsScreen(container = container, onBack = popBack)
                        }
                    }
                }
            }
        }
    }
}
