package com.htmltomp4.converter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.htmltomp4.converter.MainActivity
import com.htmltomp4.converter.R
import com.htmltomp4.converter.engine.HtmlToMp4Renderer
import com.htmltomp4.converter.model.RenderConfig
import com.htmltomp4.converter.model.RenderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory handoff store to pass large HTML payloads safely without hitting
 * Android Binder's 1 MB Intent extra transaction limit.
 */
object RenderSessionStore {
    private val pendingConfig = AtomicReference<RenderConfig?>(null)

    fun setPendingConfig(config: RenderConfig) {
        pendingConfig.set(config)
    }

    fun consumePendingConfig(): RenderConfig? = pendingConfig.getAndSet(null)
}

class RenderForegroundService : Service() {

    companion object {
        const val ACTION_START_RENDER = "com.htmltomp4.converter.ACTION_START_RENDER"
        const val ACTION_CANCEL_RENDER = "com.htmltomp4.converter.ACTION_CANCEL_RENDER"

        private const val CHANNEL_ID = "html_to_mp4_render_channel"
        private const val NOTIFICATION_ID = 1001

        private val _renderState = MutableStateFlow<RenderState>(RenderState.Idle)
        val renderState: StateFlow<RenderState> = _renderState.asStateFlow()

        fun startRender(context: Context, config: RenderConfig) {
            RenderSessionStore.setPendingConfig(config)
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_START_RENDER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelRender(context: Context) {
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_CANCEL_RENDER
            }
            context.startService(intent)
        }

        fun resetStateToIdle() {
            if (_renderState.value !is RenderState.Running) {
                _renderState.value = RenderState.Idle
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentRenderJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_RENDER -> {
                currentRenderJob?.cancel()
                _renderState.value = RenderState.Cancelled
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_START_RENDER -> {
                val config = RenderSessionStore.consumePendingConfig()
                if (config == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRenderTask(config)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRenderTask(config: RenderConfig) {
        currentRenderJob?.cancel()

        val initialNotification = buildProgressNotification(
            progressPercent = 0,
            statusText = "Menyiapkan WebView offscreen..."
        )
        startForegroundCompat(initialNotification)

        _renderState.value = RenderState.Running(
            progress = 0f,
            statusText = "Menyiapkan WebView offscreen..."
        )

        currentRenderJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HtmlToMp4::RenderWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire((config.durationSeconds * 4L + 120L) * 1000L)
            }

            val tempFinalMp4 = File(cacheDir, "final_out_${System.currentTimeMillis()}.mp4")
            val startTimeMs = System.currentTimeMillis()
            var lastNotificationUpdateMs = 0L

            try {
                val renderer = HtmlToMp4Renderer(applicationContext)
                renderer.renderToMp4File(
                    config = config,
                    finalOutputMp4 = tempFinalMp4,
                    onProgress = { update ->
                        _renderState.value = RenderState.Running(
                            progress = update.progress,
                            statusText = update.statusText,
                            currentFrame = update.currentFrame,
                            totalFrames = update.totalFrames
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationUpdateMs >= 450L || update.progress >= 1.0f) {
                            lastNotificationUpdateMs = now
                            val pct = (update.progress * 100).toInt().coerceIn(0, 100)
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildProgressNotification(pct, update.statusText)
                            )
                        }
                    }
                )

                val savedUri = persistOutputFile(
                    sourceFile = tempFinalMp4,
                    treeUriString = config.outputTreeUri,
                    fileName = config.outputFileName
                )

                val elapsed = System.currentTimeMillis() - startTimeMs
                val fileSize = tempFinalMp4.length()

                _renderState.value = RenderState.Completed(
                    outputUriString = savedUri.toString(),
                    fileName = config.outputFileName,
                    fileSizeBytes = fileSize,
                    elapsedMs = elapsed
                )
                showCompletionNotification(config.outputFileName)
            } catch (_: CancellationException) {
                _renderState.value = RenderState.Cancelled
            } catch (t: Throwable) {
                val message = t.localizedMessage ?: "Terjadi kesalahan saat merender video"
                _renderState.value = RenderState.Failed(message)
                showErrorNotification(message)
            } finally {
                tempFinalMp4.delete()
                if (wakeLock.isHeld) {
                    runCatching { wakeLock.release() }
                }
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun persistOutputFile(
        sourceFile: File,
        treeUriString: String?,
        fileName: String
    ): Uri {
        // 1. Try user-selected SAF folder first
        if (!treeUriString.isNullOrBlank()) {
            val treeUri = Uri.parse(treeUriString)
            val treeDoc = DocumentFile.fromTreeUri(applicationContext, treeUri)
            if (treeDoc != null && treeDoc.canWrite()) {
                val existing = treeDoc.findFile(fileName)
                existing?.delete()
                val created = treeDoc.createFile("video/mp4", fileName)
                if (created != null) {
                    contentResolver.openOutputStream(created.uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                    return created.uri
                }
            }
        }

        // 2. Fallback to MediaStore Movies/HtmlToMp4 on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/HtmlToMp4"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = contentResolver.insert(collection, values)
            if (itemUri != null) {
                contentResolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
                return itemUri
            }
        }

        // 3. Fallback to internal app files exposed securely via FileProvider
        val outDir = File(filesDir, "rendered_videos").apply { mkdirs() }
        val permanentFile = File(outDir, fileName)
        sourceFile.copyTo(permanentFile, overwrite = true)
        return FileProvider.getUriForFile(
            applicationContext,
            "${packageName}.fileprovider",
            permanentFile
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.render_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.render_notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        progressPercent: Int,
        statusText: String
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RenderForegroundService::class.java).apply {
                action = ACTION_CANCEL_RENDER
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("HTML to MP4 ($progressPercent%)")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batal", cancelIntent)
            .build()
    }

    private fun showCompletionNotification(fileName: String) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Render MP4 Selesai")
            .setContentText("Tersimpan: $fileName")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notif)
    }

    private fun showErrorNotification(error: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Render MP4 Gagal")
            .setContentText(error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 2, notif)
    }

    override fun onDestroy() {
        currentRenderJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
