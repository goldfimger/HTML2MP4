package com.htmltomp4.converter.model

enum class VideoResolution(
    val label: String,
    val width: Int,
    val height: Int
) {
    RES_480P("480p", 854, 480),
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080)
}

enum class VideoFps(val fps: Int, val label: String) {
    FPS_24(24, "24 fps"),
    FPS_30(30, "30 fps"),
    FPS_60(60, "60 fps")
}

enum class AudioSourceMode(val label: String) {
    SILENT("Hening"),
    WEBVIEW_HTML("Audio HTML / WebView"),
    EXTERNAL_FILE("File Audio (MP3/WAV)")
}

enum class AudioSampleRate(val hz: Int, val label: String) {
    SR_44100(44100, "44.1 kHz"),
    SR_48000(48000, "48 kHz")
}

enum class AudioBitrate(val bps: Int, val label: String) {
    BR_128K(128_000, "128 kbps"),
    BR_192K(192_000, "192 kbps")
}

enum class ThemeMode(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Terang"),
    DARK("Gelap")
}

data class RenderConfig(
    val htmlContent: String,
    val htmlSourceName: String,
    val resolution: VideoResolution,
    val fps: Int,
    val videoBitrateMbps: Int,
    val durationSeconds: Int,
    val audioSourceMode: AudioSourceMode,
    val externalAudioUri: String?,
    val externalAudioName: String?,
    val audioSampleRate: AudioSampleRate,
    val audioBitrate: AudioBitrate,
    val outputTreeUri: String?,
    val outputFileName: String
)

sealed interface RenderState {
    data object Idle : RenderState

    data class Running(
        val progress: Float,
        val statusText: String,
        val currentFrame: Int = 0,
        val totalFrames: Int = 0
    ) : RenderState

    data class Completed(
        val outputUriString: String,
        val fileName: String,
        val fileSizeBytes: Long,
        val elapsedMs: Long
    ) : RenderState

    data class Failed(
        val errorMessage: String
    ) : RenderState

    data object Cancelled : RenderState
}
