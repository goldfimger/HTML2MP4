package com.htmltomp4.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.htmltomp4.converter.data.UserPreferences
import com.htmltomp4.converter.model.AudioBitrate
import com.htmltomp4.converter.model.AudioSampleRate
import com.htmltomp4.converter.model.AudioSourceMode
import com.htmltomp4.converter.model.RenderState
import com.htmltomp4.converter.model.ThemeMode
import com.htmltomp4.converter.model.VideoFps
import com.htmltomp4.converter.model.VideoResolution
import com.htmltomp4.converter.viewmodel.MainUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    userPrefs: UserPreferences,
    renderState: RenderState,
    onPickHtmlFile: () -> Unit,
    onHtmlContentChange: (String) -> Unit,
    onLoadSampleHtml: () -> Unit,
    onClearHtml: () -> Unit,
    onSelectResolution: (VideoResolution) -> Unit,
    onSelectFps: (VideoFps) -> Unit,
    onSelectVideoBitrate: (Int) -> Unit,
    onDurationChange: (String) -> Unit,
    onSelectAudioSource: (AudioSourceMode) -> Unit,
    onPickExternalAudio: () -> Unit,
    onSelectAudioSampleRate: (AudioSampleRate) -> Unit,
    onSelectAudioBitrate: (AudioBitrate) -> Unit,
    onPickOutputFolder: () -> Unit,
    onToggleTheme: () -> Unit,
    onStartRender: () -> Unit,
    onCancelRender: () -> Unit,
    onPlayVideo: (String) -> Unit,
    onDismissResult: () -> Unit
) {
    val isRendering = renderState is RenderState.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "HTML to MP4",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Offscreen Hardware Encoder",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    val themeIcon = when (userPrefs.themeMode) {
                        ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
                        ThemeMode.LIGHT -> Icons.Rounded.LightMode
                        ThemeMode.DARK -> Icons.Rounded.DarkMode
                    }
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            imageVector = themeIcon,
                            contentDescription = "Ganti Tema (${userPrefs.themeMode.label})"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Progress / Result Banner Section
            RenderStatusSection(
                renderState = renderState,
                validationError = uiState.validationError,
                onCancelRender = onCancelRender,
                onPlayVideo = onPlayVideo,
                onDismissResult = onDismissResult
            )

            // 2. HTML Input Section (No Live Preview to preserve Mali-G52 GPU & RAM)
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sumber HTML",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = uiState.htmlSourceName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = onLoadSampleHtml,
                                enabled = !isRendering
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Sample")
                            }
                            IconButton(
                                onClick = onClearHtml,
                                enabled = !isRendering && uiState.htmlContent.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Bersihkan HTML"
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onPickHtmlFile,
                        enabled = !isRendering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Pilih File .html dari Penyimpanan")
                    }

                    OutlinedTextField(
                        value = uiState.htmlContent,
                        onValueChange = onHtmlContentChange,
                        enabled = !isRendering,
                        label = { Text("Tempel Kode HTML") },
                        placeholder = { Text("<!DOCTYPE html><html>...</html>") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 12
                    )
                }
            }

            // 3. Video Tuning Section
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pengaturan Video (H.264)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Resolusi",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val items = VideoResolution.entries
                        items.forEachIndexed { index, res ->
                            SegmentedButton(
                                selected = uiState.resolution == res,
                                onClick = { if (!isRendering) onSelectResolution(res) },
                                enabled = !isRendering,
                                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                                label = { Text(res.label, maxLines = 1) }
                            )
                        }
                    }

                    Text(
                        text = "Frame Rate (FPS)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val items = VideoFps.entries
                        items.forEachIndexed { index, fps ->
                            SegmentedButton(
                                selected = uiState.fps == fps,
                                onClick = { if (!isRendering) onSelectFps(fps) },
                                enabled = !isRendering,
                                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                                label = { Text(fps.label, maxLines = 1) }
                            )
                        }
                    }

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Bitrate Video",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${uiState.videoBitrateMbps} Mbps",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = uiState.videoBitrateMbps.toFloat(),
                            onValueChange = { onSelectVideoBitrate(it.roundToInt()) },
                            valueRange = 2f..10f,
                            steps = 7,
                            enabled = !isRendering
                        )
                    }

                    OutlinedTextField(
                        value = uiState.durationSecondsText,
                        onValueChange = onDurationChange,
                        enabled = !isRendering,
                        label = { Text("Durasi Render (detik)") },
                        suffix = { Text("detik") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Audio Tuning Section
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Pengaturan Audio (AAC)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Sumber Audio",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioSourceOptionRow(
                            selected = uiState.audioSourceMode == AudioSourceMode.SILENT,
                            enabled = !isRendering,
                            icon = Icons.Rounded.VolumeOff,
                            title = "Hening (Tanpa Audio)",
                            subtitle = "Render tercepat & paling hemat RAM",
                            onClick = { onSelectAudioSource(AudioSourceMode.SILENT) }
                        )
                        AudioSourceOptionRow(
                            selected = uiState.audioSourceMode == AudioSourceMode.WEBVIEW_HTML,
                            enabled = !isRendering,
                            icon = Icons.Rounded.GraphicEq,
                            title = "Audio dari HTML / WebView",
                            subtitle = "Menangkap <audio>/<video> atau Web Audio API di HTML",
                            onClick = { onSelectAudioSource(AudioSourceMode.WEBVIEW_HTML) }
                        )
                        AudioSourceOptionRow(
                            selected = uiState.audioSourceMode == AudioSourceMode.EXTERNAL_FILE,
                            enabled = !isRendering,
                            icon = Icons.Rounded.AudioFile,
                            title = "File Audio Eksternal (MP3 / WAV)",
                            subtitle = uiState.externalAudioName ?: "Pilih file backsound dari penyimpanan",
                            onClick = { onSelectAudioSource(AudioSourceMode.EXTERNAL_FILE) }
                        )
                    }

                    if (uiState.audioSourceMode == AudioSourceMode.EXTERNAL_FILE) {
                        FilledTonalButton(
                            onClick = onPickExternalAudio,
                            enabled = !isRendering,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AudioFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = uiState.externalAudioName ?: "Pilih File MP3 / WAV",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (uiState.audioSourceMode != AudioSourceMode.SILENT) {
                        HorizontalDivider()

                        Text(
                            text = "Sample Rate",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val rates = AudioSampleRate.entries
                            rates.forEachIndexed { index, sr ->
                                SegmentedButton(
                                    selected = uiState.audioSampleRate == sr,
                                    onClick = { if (!isRendering) onSelectAudioSampleRate(sr) },
                                    enabled = !isRendering,
                                    shape = SegmentedButtonDefaults.itemShape(index, rates.size),
                                    label = { Text(sr.label, maxLines = 1) }
                                )
                            }
                        }

                        Text(
                            text = "Bitrate Audio",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val bitrates = AudioBitrate.entries
                            bitrates.forEachIndexed { index, br ->
                                SegmentedButton(
                                    selected = uiState.audioBitrate == br,
                                    onClick = { if (!isRendering) onSelectAudioBitrate(br) },
                                    enabled = !isRendering,
                                    shape = SegmentedButtonDefaults.itemShape(index, bitrates.size),
                                    label = { Text(br.label, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. SAF Storage Location Section
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Lokasi Penyimpanan Output",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = userPrefs.outputTreeLabel?.let { "Folder tersimpan: $it" }
                            ?: "Default: Movies/HtmlToMp4 (atau pilih folder khusus)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onPickOutputFolder,
                        enabled = !isRendering,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Pilih Folder Penyimpanan (SAF)")
                    }
                }
            }

            // 6. Primary Render Action Button
            if (isRendering) {
                OutlinedButton(
                    onClick = onCancelRender,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Batalkan Proses Render")
                }
            } else {
                Button(
                    onClick = onStartRender,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Mulai Render MP4 (Offscreen)")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RenderStatusSection(
    renderState: RenderState,
    validationError: String?,
    onCancelRender: () -> Unit,
    onPlayVideo: (String) -> Unit,
    onDismissResult: () -> Unit
) {
    if (!validationError.isNullOrBlank()) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                Text(
                    text = validationError,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    when (renderState) {
        RenderState.Idle -> Unit
        is RenderState.Running -> {
            val pct = (renderState.progress * 100).roundToInt().coerceIn(0, 100)
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = renderState.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { renderState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onCancelRender) {
                            Text("Batal")
                        }
                    }
                }
            }
        }
        is RenderState.Completed -> {
            val sizeMb = String.format(java.util.Locale.US, "%.2f MB", renderState.fileSizeBytes / (1024.0 * 1024.0))
            val sec = String.format(java.util.Locale.US, "%.1f dtk", renderState.elapsedMs / 1000.0)
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Render Selesai",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${renderState.fileName} · $sizeMb · $sec",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismissResult) {
                            Icon(Icons.Rounded.Close, contentDescription = "Tutup")
                        }
                    }
                    Button(
                        onClick = { onPlayVideo(renderState.outputUriString) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Play Video")
                    }
                }
            }
        }
        is RenderState.Failed -> {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                    Text(
                        text = "Gagal: ${renderState.errorMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissResult) {
                        Icon(Icons.Rounded.Close, contentDescription = "Tutup")
                    }
                }
            }
        }
        RenderState.Cancelled -> {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Render dibatalkan.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissResult) {
                        Text("Tutup")
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSourceOptionRow(
    selected: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        contentColor.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
