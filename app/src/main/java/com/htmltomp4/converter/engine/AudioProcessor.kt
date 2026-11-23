package com.htmltomp4.converter.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Low-level hardware-friendly Audio Decoder, Linear Resampler, AAC Encoder,
 * and MP4 Muxer optimized for minimal RAM footprint on Exynos 850.
 */
object AudioProcessor {

    private const val AAC_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val TARGET_CHANNELS = 2
    private const val TIMEOUT_US = 10_000L

    /**
     * Decodes an external audio URI (MP3, WAV, M4A, OGG) into an AAC (.m4a) file
     * trimmed or looped to exact [durationSeconds].
     */
    suspend fun encodeUriToAac(
        context: Context,
        sourceUri: Uri,
        outputAacFile: File,
        targetSampleRate: Int,
        targetBitrate: Int,
        durationSeconds: Int
    ): Boolean {
        val tempPcm = File(context.cacheDir, "decoded_ext_${System.currentTimeMillis()}.pcm")
        return try {
            val extracted = decodeMediaToPcmFile(
                context = context,
                sourceUri = sourceUri,
                sourceFile = null,
                pcmOutFile = tempPcm,
                targetSampleRate = targetSampleRate,
                maxDurationSeconds = durationSeconds
            )
            if (!extracted || tempPcm.length() == 0L) {
                false
            } else {
                encodePcmFileToAac(
                    pcmFile = tempPcm,
                    outputAacFile = outputAacFile,
                    sampleRate = targetSampleRate,
                    bitrate = targetBitrate,
                    durationSeconds = durationSeconds,
                    loopIfShorter = true
                )
            }
        } finally {
            tempPcm.delete()
        }
    }

    /**
     * Resolves an embedded HTML media URL (`data:audio/...;base64,...` or `http(s)://...`)
     * into a temporary file, decodes it, and encodes it into an AAC (.m4a) file.
     */
    suspend fun encodeHtmlMediaSourceToAac(
        context: Context,
        sourceUrl: String,
        outputAacFile: File,
        targetSampleRate: Int,
        targetBitrate: Int,
        durationSeconds: Int
    ): Boolean {
        val tempSource = File(context.cacheDir, "html_src_${System.currentTimeMillis()}.bin")
        val tempPcm = File(context.cacheDir, "html_pcm_${System.currentTimeMillis()}.pcm")
        return try {
            val downloaded = materializeSourceUrl(context, sourceUrl, tempSource)
            if (!downloaded || tempSource.length() == 0L) return false

            val decoded = decodeMediaToPcmFile(
                context = context,
                sourceUri = null,
                sourceFile = tempSource,
                pcmOutFile = tempPcm,
                targetSampleRate = targetSampleRate,
                maxDurationSeconds = durationSeconds
            )
            if (!decoded || tempPcm.length() == 0L) return false

            encodePcmFileToAac(
                pcmFile = tempPcm,
                outputAacFile = outputAacFile,
                sampleRate = targetSampleRate,
                bitrate = targetBitrate,
                durationSeconds = durationSeconds,
                loopIfShorter = false
            )
        } finally {
            tempSource.delete()
            tempPcm.delete()
        }
    }

    /**
     * Encodes raw 16-bit stereo PCM captured from WebView WebAudio API bridge into AAC (.m4a).
     */
    suspend fun encodeCapturedWebAudioPcmToAac(
        pcmFile: File,
        outputAacFile: File,
        sampleRate: Int,
        bitrate: Int,
        durationSeconds: Int
    ): Boolean {
        if (!pcmFile.exists() || pcmFile.length() == 0L) return false
        return encodePcmFileToAac(
            pcmFile = pcmFile,
            outputAacFile = outputAacFile,
            sampleRate = sampleRate,
            bitrate = bitrate,
            durationSeconds = durationSeconds,
            loopIfShorter = false
        )
    }

    /**
     * Remuxes compressed H.264 video MP4 and AAC M4A into a single synchronized MP4 container
     * with zero video/audio re-encoding.
     */
    suspend fun muxVideoAndAudio(
        videoMp4File: File,
        audioAacFile: File,
        finalMp4File: File
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoMp4File.absolutePath)
            audioExtractor.setDataSource(audioAacFile.absolutePath)

            val videoTrackIdx = findTrackIndex(videoExtractor, "video/")
            val audioTrackIdx = findTrackIndex(audioExtractor, "audio/")
            require(videoTrackIdx >= 0) { "Track video tidak ditemukan pada file sementara" }

            videoExtractor.selectTrack(videoTrackIdx)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)

            muxer = MediaMuxer(finalMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxVideoTrack = muxer.addTrack(videoFormat)

            val muxAudioTrack = if (audioTrackIdx >= 0) {
                audioExtractor.selectTrack(audioTrackIdx)
                val audioFormat = audioExtractor.getTrackFormat(audioTrackIdx)
                muxer.addTrack(audioFormat)
            } else {
                -1
            }

            muxer.start()

            // Reuse a single direct buffer (512 KB) to prevent GC spikes on Exynos 850
            val buffer = ByteBuffer.allocateDirect(512 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            var maxVideoPtsUs = 0L
            while (true) {
                coroutineContext.ensureActive()
                val sampleSize = videoExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = mapSampleFlags(videoExtractor.sampleFlags)
                if (bufferInfo.presentationTimeUs > maxVideoPtsUs) {
                    maxVideoPtsUs = bufferInfo.presentationTimeUs
                }

                muxer.writeSampleData(muxVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            if (muxAudioTrack >= 0) {
                while (true) {
                    coroutineContext.ensureActive()
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val sampleTime = audioExtractor.sampleTime
                    if (maxVideoPtsUs > 0 && sampleTime > maxVideoPtsUs + 50_000L) {
                        break
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime
                    bufferInfo.flags = mapSampleFlags(audioExtractor.sampleFlags)

                    muxer.writeSampleData(muxAudioTrack, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun mapSampleFlags(extractorFlags: Int): Int {
        var codecFlags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return codecFlags
    }

    private suspend fun materializeSourceUrl(
        context: Context,
        sourceUrl: String,
        destFile: File
    ): Boolean {
        return try {
            when {
                sourceUrl.startsWith("data:", ignoreCase = true) -> {
                    val commaIndex = sourceUrl.indexOf(',')
                    if (commaIndex <= 0) return false
                    val header = sourceUrl.substring(0, commaIndex)
                    val payload = sourceUrl.substring(commaIndex + 1)
                    if (header.contains(";base64", ignoreCase = true)) {
                        val bytes = Base64.decode(payload, Base64.DEFAULT)
                        FileOutputStream(destFile).use { it.write(bytes) }
                        true
                    } else {
                        false
                    }
                }
                sourceUrl.startsWith("content://", ignoreCase = true) -> {
                    context.contentResolver.openInputStream(Uri.parse(sourceUrl))?.use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    } != null
                }
                sourceUrl.startsWith("file://", ignoreCase = true) -> {
                    val path = Uri.parse(sourceUrl).path ?: return false
                    val src = File(path)
                    if (!src.exists()) return false
                    src.inputStream().use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                    true
                }
                sourceUrl.startsWith("http://", ignoreCase = true) ||
                    sourceUrl.startsWith("https://", ignoreCase = true) -> {
                    val conn = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 15_000
                        instanceFollowRedirects = true
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            conn.inputStream.use { input ->
                                FileOutputStream(destFile).use { output -> input.copyTo(output) }
                            }
                            true
                        } else {
                            false
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun decodeMediaToPcmFile(
        context: Context,
        sourceUri: Uri?,
        sourceFile: File?,
        pcmOutFile: File,
        targetSampleRate: Int,
        maxDurationSeconds: Int
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val maxSamplesPerChannel = targetSampleRate.toLong() * maxDurationSeconds

        return try {
            if (sourceUri != null) {
                extractor.setDataSource(context, sourceUri, null)
            } else if (sourceFile != null) {
                extractor.setDataSource(sourceFile.absolutePath)
            } else {
                return false
            }

            val audioTrackIndex = findTrackIndex(extractor, "audio/")
            if (audioTrackIndex < 0) return false
            extractor.selectTrack(audioTrackIndex)

            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false

            var srcSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else targetSampleRate

            var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else TARGET_CHANNELS

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var writtenFrames = 0L

            BufferedOutputStream(FileOutputStream(pcmOutFile), 32 * 1024).use { bos ->
                val outByteArray = ByteArray(16384)
                while (!outputDone) {
                    coroutineContext.ensureActive()

                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, sampleSize, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = decoder.outputFormat
                            if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = decoder.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)

                                val shortCount = bufferInfo.size / 2
                                val srcShorts = ShortArray(shortCount)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(srcShorts)

                                val resampled = resampleAndNormalizeToStereo(
                                    input = srcShorts,
                                    srcChannels = srcChannels.coerceAtLeast(1),
                                    srcSampleRate = srcSampleRate.coerceAtLeast(8000),
                                    targetSampleRate = targetSampleRate
                                )

                                val frameCount = resampled.size / TARGET_CHANNELS
                                val remainingAllowed = (maxSamplesPerChannel - writtenFrames).coerceAtLeast(0L)
                                val framesToWrite = min(frameCount.toLong(), remainingAllowed).toInt()

                                if (framesToWrite > 0) {
                                    writeShortsLittleEndian(resampled, framesToWrite * TARGET_CHANNELS, bos, outByteArray)
                                    writtenFrames += framesToWrite
                                }
                                if (writtenFrames >= maxSamplesPerChannel) {
                                    outputDone = true
                                }
                            }

                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            writtenFrames > 0L
        } catch (_: Exception) {
            false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun encodePcmFileToAac(
        pcmFile: File,
        outputAacFile: File,
        sampleRate: Int,
        bitrate: Int,
        durationSeconds: Int,
        loopIfShorter: Boolean
    ): Boolean {
        val bytesPerSample = 2 * TARGET_CHANNELS // 4 bytes per stereo 16-bit frame
        val totalTargetBytes = sampleRate.toLong() * durationSeconds * bytesPerSample
        val pcmBytesAvailable = pcmFile.length()
        if (pcmBytesAvailable <= 0L) return false

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        return try {
            val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, TARGET_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            encoder = MediaCodec.createEncoderByType(AAC_MIME)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputAacFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val chunk = ByteArray(4096)
            var totalBytesFed = 0L
            var inputDone = false
            var outputDone = false

            var bis = BufferedInputStream(FileInputStream(pcmFile), 16384)
            try {
                while (!outputDone) {
                    coroutineContext.ensureActive()

                    if (!inputDone) {
                        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val maxToRead = min(
                                    min(inBuf.remaining().toLong(), chunk.size.toLong()),
                                    (totalTargetBytes - totalBytesFed).coerceAtLeast(0L)
                                ).toInt()

                                if (maxToRead <= 0) {
                                    encoder.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    var read = bis.read(chunk, 0, maxToRead)
                                    if (read < 0) {
                                        if (loopIfShorter && totalBytesFed < totalTargetBytes) {
                                            bis.close()
                                            bis = BufferedInputStream(FileInputStream(pcmFile), 16384)
                                            read = bis.read(chunk, 0, maxToRead)
                                        }
                                    }

                                    if (read <= 0) {
                                        // Pad remainder with silence up to exact duration if HTML audio ended early
                                        if (!loopIfShorter && totalBytesFed < totalTargetBytes) {
                                            chunk.fill(0, 0, maxToRead)
                                            inBuf.put(chunk, 0, maxToRead)
                                            val ptsUs = (totalBytesFed / bytesPerSample) * 1_000_000L / sampleRate
                                            totalBytesFed += maxToRead
                                            encoder.queueInputBuffer(inIdx, 0, maxToRead, ptsUs, 0)
                                        } else {
                                            encoder.queueInputBuffer(
                                                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            inputDone = true
                                        }
                                    } else {
                                        inBuf.put(chunk, 0, read)
                                        val ptsUs = (totalBytesFed / bytesPerSample) * 1_000_000L / sampleRate
                                        totalBytesFed += read
                                        encoder.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                                    }
                                }
                            }
                        }
                    }

                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                muxerStarted
                            ) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { bis.close() }
            }

            if (muxerStarted) {
                muxer.stop()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    private fun resampleAndNormalizeToStereo(
        input: ShortArray,
        srcChannels: Int,
        srcSampleRate: Int,
        targetSampleRate: Int
    ): ShortArray {
        val srcFrames = input.size / srcChannels
        if (srcFrames <= 0) return ShortArray(0)

        if (srcSampleRate == targetSampleRate && srcChannels == TARGET_CHANNELS) {
            return input
        }

        val ratio = srcSampleRate.toDouble() / targetSampleRate.toDouble()
        val dstFrames = ((srcFrames.toDouble() / ratio).toInt()).coerceAtLeast(1)
        val output = ShortArray(dstFrames * TARGET_CHANNELS)

        for (i in 0 until dstFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, srcFrames - 1)
            val frac = (srcPos - idx0).toFloat()

            val left0: Float
            val right0: Float
            val left1: Float
            val right1: Float

            if (srcChannels == 1) {
                left0 = input[idx0].toFloat()
                right0 = left0
                left1 = input[idx1].toFloat()
                right1 = left1
            } else {
                left0 = input[idx0 * srcChannels].toFloat()
                right0 = input[idx0 * srcChannels + 1].toFloat()
                left1 = input[idx1 * srcChannels].toFloat()
                right1 = input[idx1 * srcChannels + 1].toFloat()
            }

            val l = (left0 + (left1 - left0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val r = (right0 + (right1 - right0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            output[i * 2] = l.toShort()
            output[i * 2 + 1] = r.toShort()
        }
        return output
    }

    private fun writeShortsLittleEndian(
        shorts: ShortArray,
        count: Int,
        bos: BufferedOutputStream,
        scratch: ByteArray
    ) {
        var offset = 0
        while (offset < count) {
            val batch = min(count - offset, scratch.size / 2)
            for (i in 0 until batch) {
                val s = shorts[offset + i].toInt()
                scratch[i * 2] = (s and 0xFF).toByte()
                scratch[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            bos.write(scratch, 0, batch * 2)
            offset += batch
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }
}
