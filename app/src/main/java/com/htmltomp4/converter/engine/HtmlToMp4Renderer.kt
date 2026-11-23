package com.htmltomp4.converter.engine

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.htmltomp4.converter.model.AudioSourceMode
import com.htmltomp4.converter.model.RenderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RenderProgressUpdate(
    val progress: Float,
    val statusText: String,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0
)

/**
 * Offscreen HTML-to-MP4 hardware renderer optimized for Samsung Exynos 850 (Mali-G52 MP1).
 *
 * Key optimizations:
 * - Zero Bitmap allocations per frame (draws directly via Surface.lockHardwareCanvas into MediaCodec).
 * - Deterministic Virtual Clock + Web Animations API stepping for exact Constant Frame Rate (CFR).
 * - Thermal-aware adaptive backpressure using PowerManager thermal status on API 29+.
 * - Strict resource cleanup of MediaCodec, MediaMuxer, VirtualDisplay, Presentation, and WebView.
 */
class HtmlToMp4Renderer(private val appContext: Context) {

    companion object {
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val ENCODER_TIMEOUT_US = 10_000L
        private const val BRIDGE_AUDIO_NAME = "HtmlAudioBridge"
        private const val BRIDGE_FRAME_NAME = "HtmlFrameBridge"
    }

    private class HtmlAudioBridge(
        private val pcmOutStream: BufferedOutputStream?,
        private val sampleRate: Int,
        private val onDiscoveredMediaUrl: (String) -> Unit
    ) {
        private val closed = AtomicBoolean(false)

        @JavascriptInterface
        fun pushPcmBase64(base64Pcm: String) {
            if (closed.get() || pcmOutStream == null) return
            try {
                val bytes = Base64.decode(base64Pcm, Base64.DEFAULT)
                synchronized(pcmOutStream) {
                    if (!closed.get()) {
                        pcmOutStream.write(bytes)
                    }
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun reportMediaUrl(url: String) {
            if (url.isNotBlank()) {
                onDiscoveredMediaUrl(url.trim())
            }
        }

        @JavascriptInterface
        fun getTargetSampleRate(): Int = sampleRate

        fun markClosed() {
            closed.set(true)
        }
    }

    private class HtmlFrameBridge

    suspend fun renderToMp4File(
        config: RenderConfig,
        finalOutputMp4: File,
        onProgress: (RenderProgressUpdate) -> Unit
    ) {
        val cacheDir = appContext.cacheDir
        val stamp = System.currentTimeMillis()
        val tempVideoFile = File(cacheDir, "render_vid_$stamp.mp4")
        val tempAudioFile = File(cacheDir, "render_aud_$stamp.m4a")
        val tempWebAudioPcmFile = File(cacheDir, "render_webaudio_$stamp.pcm")

        var webAudioStream: BufferedOutputStream? = null
        var audioBridge: HtmlAudioBridge? = null
        val discoveredHtmlAudioUrls = mutableListOf<String>()

        var virtualDisplay: VirtualDisplay? = null
        var dummySurfaceTexture: SurfaceTexture? = null
        var dummySurface: Surface? = null
        var presentation: Presentation? = null
        var webView: WebView? = null

        var videoEncoder: MediaCodec? = null
        var encoderInputSurface: Surface? = null
        var videoMuxer: MediaMuxer? = null

        try {
            onProgress(RenderProgressUpdate(0.02f, "Menyiapkan WebView offscreen..."))

            val totalFrames = (config.durationSeconds * config.fps).coerceAtLeast(1)
            val codecSelection = selectHardwareAvcEncoder(
                requestedWidth = config.resolution.width,
                requestedHeight = config.resolution.height
            )
            val width = codecSelection.alignedWidth
            val height = codecSelection.alignedHeight

            // Pre-open WebAudio PCM stream if HTML audio capture is requested
            if (config.audioSourceMode == AudioSourceMode.WEBVIEW_HTML) {
                webAudioStream = BufferedOutputStream(FileOutputStream(tempWebAudioPcmFile), 16384)
                audioBridge = HtmlAudioBridge(
                    pcmOutStream = webAudioStream,
                    sampleRate = config.audioSampleRate.hz,
                    onDiscoveredMediaUrl = { url ->
                        synchronized(discoveredHtmlAudioUrls) {
                            if (!discoveredHtmlAudioUrls.contains(url)) {
                                discoveredHtmlAudioUrls.add(url)
                            }
                        }
                    }
                )
            }

            // Step 1: Prepare offscreen WebView on Main Thread
            val pageLoaded = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                WebView.enableSlowWholeDocumentDraw()

                val st = SurfaceTexture(0).apply {
                    setDefaultBufferSize(width, height)
                }
                dummySurfaceTexture = st
                val dSurface = Surface(st)
                dummySurface = dSurface

                val displayManager =
                    appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val vd = displayManager.createVirtualDisplay(
                    "HtmlToMp4Offscreen",
                    width,
                    height,
                    DisplayMetrics.DENSITY_DEFAULT,
                    dSurface,
                    0
                )
                virtualDisplay = vd

                val wv = createConfiguredWebView(
                    context = appContext,
                    width = width,
                    height = height,
                    audioBridge = audioBridge,
                    enableAudio = config.audioSourceMode == AudioSourceMode.WEBVIEW_HTML,
                    onPageFinished = {
                        if (!pageLoaded.isCompleted) {
                            pageLoaded.complete(Unit)
                        }
                    }
                )
                webView = wv

                // Attach WebView to Presentation on VirtualDisplay so Chromium compositor stays active even with screen off
                val attachedToPresentation = try {
                    val pres = Presentation(appContext, vd.display)
                    val container = FrameLayout(appContext).apply {
                        layoutParams = ViewGroup.LayoutParams(width, height)
                        addView(wv, FrameLayout.LayoutParams(width, height))
                    }
                    pres.setContentView(container)
                    pres.show()
                    presentation = pres
                    true
                } catch (_: Exception) {
                    false
                }

                if (!attachedToPresentation) {
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    wv.measure(widthSpec, heightSpec)
                    wv.layout(0, 0, width, height)
                }

                val injectedHtml = injectDeterministicRuntime(
                    rawHtml = config.htmlContent,
                    captureWebAudio = config.audioSourceMode == AudioSourceMode.WEBVIEW_HTML,
                    targetSampleRate = config.audioSampleRate.hz
                )
                wv.loadDataWithBaseURL(
                    "https://localhost/",
                    injectedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }

            // Wait for initial HTML load and resources settle
            pageLoaded.await()
            delay(250L)

            if (config.audioSourceMode == AudioSourceMode.WEBVIEW_HTML) {
                inspectHtmlMediaElements(webView)
                delay(120L)
            }

            // Step 2: Configure hardware H.264 MediaCodec encoder
            val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 1) // Best-effort offline priority to minimize thermal throttling
                runCatching {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
            }

            val encoder = if (codecSelection.codecName != null) {
                MediaCodec.createByCodecName(codecSelection.codecName)
            } else {
                MediaCodec.createEncoderByType(VIDEO_MIME)
            }
            videoEncoder = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoderInputSurface = surface
            encoder.start()

            val muxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoMuxer = muxer

            val muxState = VideoMuxState(muxer)
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val mainHandler = Handler(Looper.getMainLooper())

            // Step 3: Deterministic frame-by-frame offscreen render loop
            val frameDurationMs = 1000.0 / config.fps.toDouble()
            for (frameIdx in 0 until totalFrames) {
                coroutineContext.ensureActive()

                val frameTimeMs = frameIdx * frameDurationMs
                stepAndDrawFrameOnMainThread(
                    handler = mainHandler,
                    webView = webView!!,
                    inputSurface = surface,
                    frameTimeMs = frameTimeMs,
                    frameIndex = frameIdx,
                    totalFrames = totalFrames
                )

                // Drain hardware encoder synchronously for backpressure (keeps Mali-G52 queue tiny)
                drainVideoEncoder(
                    encoder = encoder,
                    muxState = muxState,
                    fps = config.fps,
                    endOfStream = false
                )

                // Update UI & notification progress cleanly without excessive allocations
                if (frameIdx == 0 || (frameIdx + 1) % 3 == 0 || frameIdx == totalFrames - 1) {
                    val renderRatio = (frameIdx + 1).toFloat() / totalFrames.toFloat()
                    val overallProgress = 0.05f + renderRatio * 0.80f
                    onProgress(
                        RenderProgressUpdate(
                            progress = overallProgress,
                            statusText = "Merender frame ${frameIdx + 1}/$totalFrames...",
                            currentFrame = frameIdx + 1,
                            totalFrames = totalFrames
                        )
                    )
                }

                // Exynos 850 thermal protection: yield briefly if device reports severe thermal status
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val thermalStatus = powerManager.currentThermalStatus
                    if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                        delay(18L)
                    } else if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                        delay(4L)
                    }
                }
            }

            // Signal EOS and drain remaining H.264 frames
            encoder.signalEndOfInputStream()
            drainVideoEncoder(
                encoder = encoder,
                muxState = muxState,
                fps = config.fps,
                endOfStream = true
            )

            if (muxState.started) {
                muxer.stop()
            }

            // Release video encoder & WebView immediately to free RAM before audio processing
            audioBridge?.markClosed()
            runCatching { webAudioStream?.flush() }
            runCatching { webAudioStream?.close() }
            webAudioStream = null

            withContext(Dispatchers.Main) {
                cleanupWebView(webView, presentation, virtualDisplay, dummySurface, dummySurfaceTexture)
                webView = null
                presentation = null
                virtualDisplay = null
                dummySurface = null
                dummySurfaceTexture = null
            }

            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            videoEncoder = null
            runCatching { surface.release() }
            encoderInputSurface = null
            runCatching { muxer.release() }
            videoMuxer = null

            // Step 4: Audio processing & MP4 muxing
            var hasEncodedAudio = false
            if (config.audioSourceMode != AudioSourceMode.SILENT) {
                onProgress(RenderProgressUpdate(0.87f, "Memproses audio..."))
                hasEncodedAudio = when (config.audioSourceMode) {
                    AudioSourceMode.SILENT -> false
                    AudioSourceMode.EXTERNAL_FILE -> {
                        val extUriStr = config.externalAudioUri
                        if (!extUriStr.isNullOrBlank()) {
                            AudioProcessor.encodeUriToAac(
                                context = appContext,
                                sourceUri = Uri.parse(extUriStr),
                                outputAacFile = tempAudioFile,
                                targetSampleRate = config.audioSampleRate.hz,
                                targetBitrate = config.audioBitrate.bps,
                                durationSeconds = config.durationSeconds
                            )
                        } else {
                            false
                        }
                    }
                    AudioSourceMode.WEBVIEW_HTML -> {
                        // Prefer captured live WebAudio PCM if present, else decode embedded <audio>/<video> source
                        if (tempWebAudioPcmFile.exists() && tempWebAudioPcmFile.length() > 1024L) {
                            AudioProcessor.encodeCapturedWebAudioPcmToAac(
                                pcmFile = tempWebAudioPcmFile,
                                outputAacFile = tempAudioFile,
                                sampleRate = config.audioSampleRate.hz,
                                bitrate = config.audioBitrate.bps,
                                durationSeconds = config.durationSeconds
                            )
                        } else {
                            val mediaUrl = synchronized(discoveredHtmlAudioUrls) {
                                discoveredHtmlAudioUrls.firstOrNull()
                            }
                            if (mediaUrl != null) {
                                AudioProcessor.encodeHtmlMediaSourceToAac(
                                    context = appContext,
                                    sourceUrl = mediaUrl,
                                    outputAacFile = tempAudioFile,
                                    targetSampleRate = config.audioSampleRate.hz,
                                    targetBitrate = config.audioBitrate.bps,
                                    durationSeconds = config.durationSeconds
                                )
                            } else {
                                false
                            }
                        }
                    }
                }
            }

            onProgress(RenderProgressUpdate(0.94f, "Menyimpan file MP4..."))

            if (hasEncodedAudio && tempAudioFile.exists() && tempAudioFile.length() > 0L) {
                AudioProcessor.muxVideoAndAudio(
                    videoMp4File = tempVideoFile,
                    audioAacFile = tempAudioFile,
                    finalMp4File = finalOutputMp4
                )
            } else {
                tempVideoFile.copyTo(finalOutputMp4, overwrite = true)
            }

            onProgress(
                RenderProgressUpdate(
                    progress = 1.0f,
                    statusText = "Selesai",
                    currentFrame = totalFrames,
                    totalFrames = totalFrames
                )
            )
        } finally {
            audioBridge?.markClosed()
            runCatching { webAudioStream?.close() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { encoderInputSurface?.release() }
            runCatching { videoMuxer?.release() }

            if (webView != null || presentation != null || virtualDisplay != null) {
                withContext(Dispatchers.Main) {
                    cleanupWebView(webView, presentation, virtualDisplay, dummySurface, dummySurfaceTexture)
                }
            }

            tempVideoFile.delete()
            tempAudioFile.delete()
            tempWebAudioPcmFile.delete()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        context: Context,
        width: Int,
        height: Int,
        audioBridge: HtmlAudioBridge?,
        enableAudio: Boolean,
        onPageFinished: () -> Unit
    ): WebView {
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(width, height)
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setInitialScale(100)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            addJavascriptInterface(HtmlFrameBridge(), BRIDGE_FRAME_NAME)
            if (enableAudio && audioBridge != null) {
                addJavascriptInterface(audioBridge, BRIDGE_AUDIO_NAME)
            }

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    onPageFinished()
                }
            }
        }
    }

    private suspend fun inspectHtmlMediaElements(webView: WebView?) {
        if (webView == null) return
        withContext(Dispatchers.Main) {
            val js = """
                (function() {
                    try {
                        var els = document.querySelectorAll('audio, video');
                        for (var i = 0; i < els.length; i++) {
                            var el = els[i];
                            var src = el.currentSrc || el.getAttribute('src');
                            if (!src) {
                                var s = el.querySelector('source');
                                if (s) src = s.getAttribute('src');
                            }
                            if (src && window.$BRIDGE_AUDIO_NAME) {
                                window.$BRIDGE_AUDIO_NAME.reportMediaUrl(src);
                            }
                        }
                    } catch (e) {}
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private suspend fun stepAndDrawFrameOnMainThread(
        handler: Handler,
        webView: WebView,
        inputSurface: Surface,
        frameTimeMs: Double,
        frameIndex: Int,
        totalFrames: Int
    ) {
        // Step 1: Advance virtual clock, CSS/Web Animations, and rAF queue inside WebView
        suspendCancellableCoroutine { cont ->
            handler.post {
                val js = "window.__htmlToMp4Step && window.__htmlToMp4Step($frameTimeMs, $frameIndex, $totalFrames);"
                webView.evaluateJavascript(js) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }

        // Step 2: Allow Chromium compositor to commit frame & draw directly to hardware encoder Surface
        suspendCancellableCoroutine { cont ->
            handler.post {
                try {
                    val drawBlock = Runnable {
                        try {
                            val canvas = inputSurface.lockHardwareCanvas()
                            try {
                                canvas.drawColor(Color.BLACK)
                                webView.draw(canvas)
                            } finally {
                                inputSurface.unlockCanvasAndPost(canvas)
                            }
                            if (cont.isActive) cont.resume(Unit)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                    // postOnAnimation ensures layout/paint invalidations from JS step are committed
                    webView.postOnAnimation(drawBlock)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                }
            }
        }
    }

    private class VideoMuxState(val muxer: MediaMuxer) {
        var trackIndex: Int = -1
        var started: Boolean = false
        var encodedFrameIndex: Long = 0L
    }

    private fun drainVideoEncoder(
        encoder: MediaCodec,
        muxState: VideoMuxState,
        fps: Int,
        endOfStream: Boolean
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val adjustedInfo = MediaCodec.BufferInfo()

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, ENCODER_TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxState.started) {
                        muxState.trackIndex = muxState.muxer.addTrack(encoder.outputFormat)
                        muxState.muxer.start()
                        muxState.started = true
                    }
                }
                outIndex >= 0 -> {
                    val encodedBuffer = encoder.getOutputBuffer(outIndex)
                    if (encodedBuffer != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && muxState.started) {
                            encodedBuffer.position(bufferInfo.offset)
                            encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // Enforce exact CFR presentation timestamps independent of Exynos render speed
                            val deterministicPtsUs = muxState.encodedFrameIndex * 1_000_000L / fps
                            adjustedInfo.set(
                                bufferInfo.offset,
                                bufferInfo.size,
                                deterministicPtsUs,
                                bufferInfo.flags
                            )
                            muxState.muxer.writeSampleData(muxState.trackIndex, encodedBuffer, adjustedInfo)
                            muxState.encodedFrameIndex++
                        }
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private data class CodecSelection(
        val codecName: String?,
        val alignedWidth: Int,
        val alignedHeight: Int
    )

    private fun selectHardwareAvcEncoder(
        requestedWidth: Int,
        requestedHeight: Int
    ): CodecSelection {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var chosenName: String? = null
        var widthAlign = 2
        var heightAlign = 2

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(VIDEO_MIME, ignoreCase = true) }) continue

            val nameLower = info.name.lowercase()
            val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isSoftwareOnly
            } else {
                nameLower.startsWith("omx.google.") || nameLower.startsWith("c2.android.")
            }
            if (isSoftware) continue

            val caps = runCatching { info.getCapabilitiesForType(VIDEO_MIME) }.getOrNull() ?: continue
            val vCaps = caps.videoCapabilities ?: continue

            chosenName = info.name
            widthAlign = vCaps.widthAlignment.coerceAtLeast(2)
            heightAlign = vCaps.heightAlignment.coerceAtLeast(2)
            break
        }

        val alignedWidth = alignDimension(requestedWidth, widthAlign)
        val alignedHeight = alignDimension(requestedHeight, heightAlign)
        return CodecSelection(chosenName, alignedWidth, alignedHeight)
    }

    private fun alignDimension(value: Int, alignment: Int): Int {
        val align = alignment.coerceAtLeast(2)
        val remainder = value % align
        return if (remainder == 0) value else value - remainder
    }

    private fun cleanupWebView(
        webView: WebView?,
        presentation: Presentation?,
        virtualDisplay: VirtualDisplay?,
        dummySurface: Surface?,
        dummySurfaceTexture: SurfaceTexture?
    ) {
        runCatching {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
        runCatching { presentation?.dismiss() }
        runCatching { virtualDisplay?.release() }
        runCatching { dummySurface?.release() }
        runCatching { dummySurfaceTexture?.release() }
    }

    private fun injectDeterministicRuntime(
        rawHtml: String,
        captureWebAudio: Boolean,
        targetSampleRate: Int
    ): String {
        val audioHookScript = if (captureWebAudio) {
            """
            (function() {
                var OrigAC = window.AudioContext || window.webkitAudioContext;
                if (!OrigAC || !window.$BRIDGE_AUDIO_NAME) return;
                function WrappedAudioContext(opts) {
                    var ctx = new OrigAC(opts || { sampleRate: $targetSampleRate });
                    var origDest = ctx.destination;
                    try {
                        var processor = ctx.createScriptProcessor(2048, 2, 2);
                        var sinkGain = ctx.createGain();
                        sinkGain.gain.value = 0.0;
                        processor.onaudioprocess = function(ev) {
                            var ch0 = ev.inputBuffer.getChannelData(0);
                            var ch1 = ev.inputBuffer.numberOfChannels > 1 ? ev.inputBuffer.getChannelData(1) : ch0;
                            var len = ch0.length;
                            var buf = new Int16Array(len * 2);
                            for (var i = 0; i < len; i++) {
                                var l = Math.max(-1, Math.min(1, ch0[i]));
                                var r = Math.max(-1, Math.min(1, ch1[i]));
                                buf[i * 2] = l < 0 ? l * 32768 : l * 32767;
                                buf[i * 2 + 1] = r < 0 ? r * 32768 : r * 32767;
                            }
                            var bytes = new Uint8Array(buf.buffer);
                            var bin = '';
                            for (var j = 0; j < bytes.length; j++) {
                                bin += String.fromCharCode(bytes[j]);
                            }
                            window.$BRIDGE_AUDIO_NAME.pushPcmBase64(btoa(bin));
                        };
                        processor.connect(sinkGain);
                        sinkGain.connect(origDest);

                        var origConnect = AudioNode.prototype.connect;
                        AudioNode.prototype.connect = function(target) {
                            if (target === origDest && this !== sinkGain && this !== processor) {
                                try { origConnect.call(this, processor); } catch(e) {}
                            }
                            return origConnect.apply(this, arguments);
                        };
                    } catch (err) {}
                    return ctx;
                }
                WrappedAudioContext.prototype = OrigAC.prototype;
                window.AudioContext = WrappedAudioContext;
                window.webkitAudioContext = WrappedAudioContext;
            })();
            """.trimIndent()
        } else {
            ""
        }

        val runtimeScript = """
            <script>
            (function() {
                window.__virtualTimeMs = 0;
                var nextRafId = 1;
                var rafQueue = [];

                if (window.performance && typeof window.performance.now === 'function') {
                    window.performance.now = function() {
                        return window.__virtualTimeMs;
                    };
                }

                window.requestAnimationFrame = function(cb) {
                    var id = nextRafId++;
                    rafQueue.push({ id: id, cb: cb });
                    return id;
                };

                window.cancelAnimationFrame = function(id) {
                    rafQueue = rafQueue.filter(function(item) { return item.id !== id; });
                };

                window.__htmlToMp4Step = function(timeMs, frameIndex, totalFrames) {
                    window.__virtualTimeMs = timeMs;
                    try {
                        if (typeof document.getAnimations === 'function') {
                            var anims = document.getAnimations();
                            for (var i = 0; i < anims.length; i++) {
                                var a = anims[i];
                                if (a.playState !== 'paused') {
                                    a.pause();
                                }
                                a.currentTime = timeMs;
                            }
                        }
                    } catch (e) {}

                    if (typeof window.onRenderFrame === 'function') {
                        try { window.onRenderFrame(timeMs, frameIndex, totalFrames); } catch (e) {}
                    }

                    var currentBatch = rafQueue.slice();
                    rafQueue = [];
                    for (var j = 0; j < currentBatch.length; j++) {
                        try { currentBatch[j].cb(timeMs); } catch (e) {}
                    }
                };
                $audioHookScript
            })();
            </script>
        """.trimIndent()

        val headIndex = rawHtml.indexOf("<head>", ignoreCase = true)
        return when {
            headIndex >= 0 -> {
                val insertPos = headIndex + "<head>".length
                rawHtml.substring(0, insertPos) + "\n" + runtimeScript + "\n" + rawHtml.substring(insertPos)
            }
            rawHtml.contains("<html", ignoreCase = true) -> {
                val closeBracket = rawHtml.indexOf('>', rawHtml.indexOf("<html", ignoreCase = true))
                if (closeBracket >= 0) {
                    rawHtml.substring(0, closeBracket + 1) + "\n<head>$runtimeScript</head>\n" + rawHtml.substring(closeBracket + 1)
                } else {
                    runtimeScript + "\n" + rawHtml
                }
            }
            else -> {
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$runtimeScript</head><body>$rawHtml</body></html>"
            }
        }
    }
}
