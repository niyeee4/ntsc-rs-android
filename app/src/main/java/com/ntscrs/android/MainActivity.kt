/* SPDX-License-Identifier: GPL-2.0-or-later */
package com.ntscrs.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fullscreen: edge-to-edge + hide system bars (sticky immersive).
        enableEdgeToEdge()
        hideSystemBars()
        val app = application as NtscApp
        val launchUri: Uri? = intent?.let { grabShareableUri(it) }
        val launchType: SourceType? = intent?.type?.let {
            when {
                it.startsWith("image/") -> SourceType.IMAGE
                it.startsWith("video/") -> SourceType.VIDEO
                else -> null
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: EditorViewModel = viewModel()
                vm.attachEngine(app.engine)
                vm.attachPrefs(this)
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                // Deep-link from share/open intents.
                if (launchUri != null && launchType != null && screen == Screen.Home) {
                    vm.openSource(this, launchUri, launchType)
                    screen = Screen.Editor
                }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(
                        onOpenImage = { screen = Screen.EditorPendingImage },
                        onOpenVideo = { screen = Screen.EditorPendingVideo },
                        onAbout = { screen = Screen.About },
                        onSettings = { screen = Screen.Settings }
                    )
                    Screen.EditorPendingImage, Screen.EditorPendingVideo -> PickAndEnter(
                        video = s == Screen.EditorPendingVideo,
                        vm = vm,
                        onDone = { screen = Screen.Editor },
                        onCancel = { screen = Screen.Home }
                    )
                    Screen.Editor -> EditorScreen(vm = vm, onBack = {
                        // Stop preview playback AND its audio when leaving.
                        vm.pausePlayback(reload = false)
                        screen = Screen.Home
                    })
                    Screen.About -> AboutScreen(onBack = { screen = Screen.Home })
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
                }
            }
        }
    }

    private fun hideSystemBars() {
        try {
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                window, window.decorView
            )
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide after dialogs, shares, permission sheets, etc.
        if (hasFocus) hideSystemBars()
    }

    private fun grabShareableUri(intent: android.content.Intent): Uri? {
        return when (intent.action) {
            android.content.Intent.ACTION_SEND ->
                intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
            android.content.Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }
}

sealed interface Screen {
    data object Home : Screen
    data object EditorPendingImage : Screen
    data object EditorPendingVideo : Screen
    data object Editor : Screen
    data object About : Screen
    data object Settings : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenImage: () -> Unit,
    onOpenVideo: () -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ntsc-rs") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "NTSC / VHS video artifacts, on Android",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Same engine as desktop ntsc-rs. " +
                    "Engine v${NtscBridge.nativeVersion()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onOpenImage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Open image")
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenVideo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Text("  Open video")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Text("  About & licenses")
            }
        }
    }
}

/** Launches the SAF picker immediately, then enters the editor (or back). */
@Composable
fun PickAndEnter(video: Boolean, vm: EditorViewModel, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var launched by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            onCancel()
        } else {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            vm.openSource(
                context, uri,
                if (video) SourceType.VIDEO else SourceType.IMAGE
            )
            onDone()
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            picker.launch(arrayOf(if (video) "video/*" else "image/*"))
        }
    }
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Pick a ${if (video) "video" else "image"}…")
    }
}
