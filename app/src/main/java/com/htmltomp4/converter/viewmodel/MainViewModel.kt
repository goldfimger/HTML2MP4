package com.htmltomp4.converter.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.htmltomp4.converter.data.PreferencesRepository
import com.htmltomp4.converter.model.AudioBitrate
import com.htmltomp4.converter.model.AudioSampleRate
import com.htmltomp4.converter.model.AudioSourceMode
import com.htmltomp4.converter.model.RenderConfig
import com.htmltomp4.converter.model.RenderState
import com.htmltomp4.converter.model.ThemeMode
import com.htmltomp4.converter.model.VideoFps
import com.htmltomp4.converter.model.VideoResolution
import com.htmltomp4.converter.service.RenderForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val htmlContent: String = DEFAULT_SAMPLE_HTML,
    val htmlSourceName: String = "Tempel Kode / Sample",
    val resolution: VideoResolution = VideoResolution.RES_720P,
    val fps: VideoFps = VideoFps.FPS_30,
    val videoBitrateMbps: Int = 5,
    val durationSecondsText: String = "10",
    val audioSourceMode: AudioSourceMode = AudioSourceMode.SILENT,
    val externalAudioUri: String? = null,
    val externalAudioName: String? = null,
    val audioSampleRate: AudioSampleRate = AudioSampleRate.SR_44100,
    val audioBitrate: AudioBitrate = AudioBitrate.BR_128K,
    val outputFileName: String = "",
    val validationError: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = PreferencesRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val userPreferences = prefsRepo.userPreferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = com.htmltomp4.converter.data.UserPreferences()
    )

    val renderState: StateFlow<RenderState> = RenderForegroundService.renderState

    fun updateHtmlContent(text: String) {
        _uiState.update {
            it.copy(
                htmlContent = text,
                validationError = null
            )
        }
    }

    fun clearHtmlContent() {
        _uiState.update {
            it.copy(
                htmlContent = "",
                htmlSourceName = "Kosong",
                validationError = null
            )
        }
    }

    fun loadSampleHtml() {
        _uiState.update {
            it.copy(
                htmlContent = DEFAULT_SAMPLE_HTML,
                htmlSourceName = "Sample Animasi Canvas",
                validationError = null
            )
        }
    }

    fun loadHtmlFromUri(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val name = resolveDisplayName(uri) ?: "document.html"
                val content = app.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                } ?: ""
                name to content
            }
            if (result.second.isNotBlank()) {
                _uiState.update {
                    it.copy(
                        htmlContent = result.second,
                        htmlSourceName = result.first,
                        validationError = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(validationError = "Gagal membaca isi file HTML yang dipilih.")
                }
            }
        }
    }

    fun setResolution(resolution: VideoResolution) {
        _uiState.update { it.copy(resolution = resolution) }
    }

    fun setFps(fps: VideoFps) {
        _uiState.update { it.copy(fps = fps) }
    }

    fun setVideoBitrateMbps(mbps: Int) {
        _uiState.update { it.copy(videoBitrateMbps = mbps.coerceIn(2, 10)) }
    }

    fun setDurationSecondsText(value: String) {
        val filtered = value.filter { it.isDigit() }.take(4)
        _uiState.update { it.copy(durationSecondsText = filtered, validationError = null) }
    }

    fun setAudioSourceMode(mode: AudioSourceMode) {
        _uiState.update { it.copy(audioSourceMode = mode, validationError = null) }
    }

    fun setAudioSampleRate(sampleRate: AudioSampleRate) {
        _uiState.update { it.copy(audioSampleRate = sampleRate) }
    }

    fun setAudioBitrate(bitrate: AudioBitrate) {
        _uiState.update { it.copy(audioBitrate = bitrate) }
    }

    fun setExternalAudioUri(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = resolveDisplayName(uri) ?: "audio_backsound"
        _uiState.update {
            it.copy(
                externalAudioUri = uri.toString(),
                externalAudioName = name,
                audioSourceMode = AudioSourceMode.EXTERNAL_FILE,
                validationError = null
            )
        }
    }

    fun setOutputFolderUri(treeUri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val folderName = DocumentFile.fromTreeUri(app, treeUri)?.name
            ?: treeUri.lastPathSegment
            ?: "Folder Terpilih"
        viewModelScope.launch {
            prefsRepo.saveOutputFolder(treeUri.toString(), folderName)
        }
    }

    fun cycleThemeMode() {
        val current = userPreferences.value.themeMode
        val next = when (current) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        viewModelScope.launch {
            prefsRepo.saveThemeMode(next)
        }
    }

    fun startRender() {
        val state = _uiState.value
        val html = state.htmlContent.trim()
        if (html.isEmpty()) {
            _uiState.update { it.copy(validationError = "Kode HTML tidak boleh kosong.") }
            return
        }

        val durationSec = state.durationSecondsText.toIntOrNull()
        if (durationSec == null || durationSec < 1 || durationSec > 600) {
            _uiState.update { it.copy(validationError = "Durasi harus antara 1 hingga 600 detik.") }
            return
        }

        if (state.audioSourceMode == AudioSourceMode.EXTERNAL_FILE && state.externalAudioUri.isNullOrBlank()) {
            _uiState.update {
                it.copy(validationError = "Pilih file audio MP3 atau WAV terlebih dahulu.")
            }
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseName = state.htmlSourceName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(24)
            .ifBlank { "html_video" }
        val fileName = "${baseName}_$timestamp.mp4"

        val config = RenderConfig(
            htmlContent = html,
            htmlSourceName = state.htmlSourceName,
            resolution = state.resolution,
            fps = state.fps.fps,
            videoBitrateMbps = state.videoBitrateMbps,
            durationSeconds = durationSec,
            audioSourceMode = state.audioSourceMode,
            externalAudioUri = state.externalAudioUri,
            externalAudioName = state.externalAudioName,
            audioSampleRate = state.audioSampleRate,
            audioBitrate = state.audioBitrate,
            outputTreeUri = userPreferences.value.outputTreeUri,
            outputFileName = fileName
        )

        _uiState.update { it.copy(validationError = null, outputFileName = fileName) }
        RenderForegroundService.startRender(getApplication(), config)
    }

    fun cancelRender() {
        RenderForegroundService.cancelRender(getApplication())
    }

    fun dismissResultState() {
        RenderForegroundService.resetStateToIdle()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}

private val DEFAULT_SAMPLE_HTML = """
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8" />
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    width: 100vw; height: 100vh; overflow: hidden;
    background: #0d1b2a; color: #f8fafc;
    font-family: sans-serif;
    display: flex; align-items: center; justify-content: center;
  }
  canvas { position: absolute; inset: 0; width: 100%; height: 100%; }
  .card {
    position: relative; z-index: 2;
    padding: 36px 48px; border-radius: 24px;
    background: rgba(15, 23, 42, 0.72);
    border: 2px solid rgba(148, 163, 184, 0.25);
    text-align: center;
  }
  h1 { font-size: 48px; margin-bottom: 12px; letter-spacing: -0.5px; }
  p { font-size: 24px; color: #93c5fd; }
</style>
</head>
<body>
  <canvas id="bg"></canvas>
  <div class="card">
    <h1>HTML to MP4</h1>
    <p id="clock">0.00s</p>
  </div>
  <script>
    const canvas = document.getElementById('bg');
    const ctx = canvas.getContext('2d');
    const clock = document.getElementById('clock');
    function draw(t) {
      if (canvas.width !== window.innerWidth || canvas.height !== window.innerHeight) {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
      }
      const sec = t / 1000;
      clock.textContent = sec.toFixed(2) + 's';
      ctx.fillStyle = '#0d1b2a';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      for (let i = 0; i < 8; i++) {
        const angle = sec * 0.9 + i * (Math.PI / 4);
        const cx = canvas.width / 2 + Math.cos(angle) * (canvas.width * 0.28);
        const cy = canvas.height / 2 + Math.sin(angle * 1.3) * (canvas.height * 0.24);
        ctx.beginPath();
        ctx.arc(cx, cy, 42 + Math.sin(sec * 2 + i) * 14, 0, Math.PI * 2);
        ctx.fillStyle = 'hsla(' + ((i * 42 + sec * 40) % 360) + ', 80%, 60%, 0.45)';
        ctx.fill();
      }
      requestAnimationFrame(draw);
    }
    requestAnimationFrame(draw);
  </script>
</body>
</html>
""".trimIndent()
