package com.htmltomp4.converter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.htmltomp4.converter.model.ThemeMode
import com.htmltomp4.converter.ui.MainScreen
import com.htmltomp4.converter.ui.theme.HtmlToMp4Theme
import com.htmltomp4.converter.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val userPrefs by viewModel.userPreferences.collectAsStateWithLifecycle()
            val renderState by viewModel.renderState.collectAsStateWithLifecycle()

            val systemDark = isSystemInDarkTheme()
            val isDark = when (userPrefs.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            val htmlFilePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { viewModel.loadHtmlFromUri(it) }
            }

            val audioFilePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { viewModel.setExternalAudioUri(it) }
            }

            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri: Uri? ->
                treeUri?.let { viewModel.setOutputFolderUri(it) }
            }

            HtmlToMp4Theme(themeMode = userPrefs.themeMode) {
                MainScreen(
                    uiState = uiState,
                    userPrefs = userPrefs,
                    renderState = renderState,
                    onPickHtmlFile = {
                        htmlFilePicker.launch(
                            arrayOf("text/html", "application/xhtml+xml", "text/plain")
                        )
                    },
                    onHtmlContentChange = viewModel::updateHtmlContent,
                    onLoadSampleHtml = viewModel::loadSampleHtml,
                    onClearHtml = viewModel::clearHtmlContent,
                    onSelectResolution = viewModel::setResolution,
                    onSelectFps = viewModel::setFps,
                    onSelectVideoBitrate = viewModel::setVideoBitrateMbps,
                    onDurationChange = viewModel::setDurationSecondsText,
                    onSelectAudioSource = viewModel::setAudioSourceMode,
                    onPickExternalAudio = {
                        audioFilePicker.launch(
                            arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/*")
                        )
                    },
                    onSelectAudioSampleRate = viewModel::setAudioSampleRate,
                    onSelectAudioBitrate = viewModel::setAudioBitrate,
                    onPickOutputFolder = { folderPicker.launch(null) },
                    onToggleTheme = viewModel::cycleThemeMode,
                    onStartRender = viewModel::startRender,
                    onCancelRender = viewModel::cancelRender,
                    onPlayVideo = { uriString -> openVideoPlayer(uriString) },
                    onDismissResult = viewModel::dismissResultState
                )
            }
        }
    }

    private fun openVideoPlayer(uriString: String) {
        val videoUri = Uri.parse(uriString)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(viewIntent, "Putar Video MP4"))
        }.onFailure {
            Toast.makeText(
                this,
                "Tidak ditemukan aplikasi pemutar video di perangkat.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
