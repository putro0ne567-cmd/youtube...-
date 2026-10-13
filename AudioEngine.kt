package com.stereodsp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.stereodsp.dsp.ChannelProcessor
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class PlayerState(
    val fileName: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val error: String? = null,
)

/**
 * Mendekode file audio (MediaCodec), memproses kanal L dan R secara terpisah
 * dengan [ChannelProcessor], lalu memutar lewat AudioTrack stereo 16-bit.
 */
class AudioEngine(private val context: Context) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile private var left = ChannelSettings()
    @Volatile private var right = ChannelSettings()
    @Volatile private var muteL = false
    @Volatile private var muteR = false
    @Volatile private var procL: ChannelProcessor? = null
    @Volatile private var procR: ChannelProcessor? = null

    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    @Volatile private var seekToMs = -1L

    fun setSettings(l: ChannelSettings, r: ChannelSettings, mutedL: Boolean, mutedR: Boolean) {
        left = l
        right = r
        muteL = mutedL
        muteR = mutedR
        procL?.update(l, mutedL)
        procR?.update(r, mutedR)
    }

    fun load(uri: Uri, name: String) {
        stop()
        stopRequested = false
        paused = false
        seekToMs = -1L
        _state.value = PlayerState(fileName = name)
        worker = Thread({ playback(uri, name) }, "dsp-audio").also { it.start() }
    }

    fun togglePause() {
        if (worker == null) return
        paused = !paused
    }

    fun seekTo(ms: Long) {
        if (worker != null) seekToMs = ms
    }

    fun stop() {
        stopRequested = true
        worker?.let {
            try {
                it.join(800)
            } catch (_: InterruptedException) {
            }
        }
        worker = null
    }

    fun release() = stop()

    private fun fail(message: String) {
        _state.update { it.copy(isPlaying = false, error = message) }
    }

    private fun playback(uri: Uri, name: String) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                fail("File tidak berisi trek audio")
                return
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                0L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pl = ChannelProcessor(sampleRate.toDouble()).also { it.update(left, muteL) }
            val pr = ChannelProcessor(sampleRate.toDouble()).also { it.update(right, muteR) }
            procL = pl
            procR = pr

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            _state.update { it.copy(isPlaying = true, durationMs = durationMs, error = null) }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var src = ShortArray(0)
            var l = FloatArray(0)
            var r = FloatArray(0)
            var pcm = ShortArray(0)
            var wasPaused = false
            var peakL = 0f
            var peakR = 0f

            while (!stopRequested && !outputDone) {
                val seek = seekToMs
                if (seek >= 0) {
                    seekToMs = -1L
                    extractor.seekTo(seek * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    track.flush()
                    pl.reset()
                    pr.reset()
                    inputDone = false
                    _state.update { it.copy(positionMs = seek) }
                }

                if (paused) {
                    if (!wasPaused) {
                        track.pause()
                        wasPaused = true
                        _state.update { it.copy(isPlaying = false) }
                    }
                    Thread.sleep(30)
                    continue
                }
                if (wasPaused) {
                    track.play()
                    wasPaused = false
                    _state.update { it.copy(isPlaying = true) }
                }

                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val total = sb.remaining()
                        val ch = max(channels, 1)
                        val frames = total / ch
                        if (src.size < total) src = ShortArray(total)
                        if (l.size < frames) {
                            l = FloatArray(frames)
                            r = FloatArray(frames)
                            pcm = ShortArray(frames * 2)
                        }
                        sb.get(src, 0, total)

                        for (i in 0 until frames) {
                            val a = src[i * ch] / 32768f
                            l[i] = a
                            r[i] = if (ch >= 2) src[i * ch + 1] / 32768f else a
                        }

                        pl.process(l, frames)
                        pr.process(r, frames)

                        var bl = 0f
                        var br = 0f
                        for (i in 0 until frames) {
                            val x = l[i]
                            val y = r[i]
                            bl = max(bl, abs(x))
                            br = max(br, abs(y))
                            pcm[2 * i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                            pcm[2 * i + 1] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        track.write(pcm, 0, frames * 2)

                        peakL = max(bl, peakL * 0.9f)
                        peakR = max(br, peakR * 0.9f)
                        val pos = info.presentationTimeUs / 1000
                        _state.update { it.copy(positionMs = pos, peakL = peakL, peakR = peakR) }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            if (outputDone && !stopRequested) {
                track.stop()
            }
        } catch (e: Exception) {
            fail("Gagal memutar: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { track?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            procL = null
            procR = null
            _state.update {
                it.copy(isPlaying = false, positionMs = 0, peakL = 0f, peakR = 0f)
            }
        }
    }
}
