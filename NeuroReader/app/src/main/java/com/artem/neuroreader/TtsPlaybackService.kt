package com.artem.neuroreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlin.math.max

class TtsPlaybackService : Service() {
    private lateinit var store: ReaderStore

    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var playing = false
    @Volatile private var currentTrack: AudioTrack? = null
    private var tts: OfflineTts? = null

    override fun onCreate() {
        super.onCreate()
        store = ReaderStore(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_PLAY) {
            ACTION_PLAY -> {
                val saved = store.loadBook()
                if (saved != null) {
                    val offset = intent?.getIntExtra(EXTRA_OFFSET, saved.offset) ?: saved.offset
                    startPlayback(offset)
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_TOGGLE -> if (playing && !paused) pausePlayback() else if (playing) resumePlayback() else {
                store.loadBook()?.let { startPlayback(it.offset) }
            }
            ACTION_SEEK -> {
                val saved = store.loadBook()
                if (saved != null) {
                    val delta = intent?.getIntExtra(EXTRA_DELTA, 0) ?: 0
                    startPlayback((saved.offset + delta).coerceAtLeast(0))
                }
            }
            ACTION_STOP -> stopPlayback(true)
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(requestedOffset: Int) {
        val text = store.loadText() ?: return
        val offset = requestedOffset.coerceIn(0, text.length)
        store.updateOffset(offset)
        generation += 1
        val myGeneration = generation
        paused = false
        playing = true
        stopTrack()
        startForeground(NOTIFICATION_ID, makeNotification("Готовлю нейроозвучку…", true))
        broadcastState(offset, true, null)

        Thread({
            try {
                val engine = ensureTts()
                var position = offset
                while (myGeneration == generation && position < text.length) {
                    waitWhilePaused(myGeneration)
                    if (myGeneration != generation) break

                    val chunk = nextChunk(text, position) ?: break
                    val audio = engine.generate(
                        text = chunk.text,
                        sid = 0,
                        speed = store.loadBook()?.speed ?: 1.0f
                    )
                    if (myGeneration != generation) break

                    playSamples(audio.samples, audio.sampleRate, myGeneration)
                    if (myGeneration != generation) break

                    position = chunk.end
                    store.updateOffset(position)
                    broadcastState(position, true, null)
                }

                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    broadcastState(store.loadBook()?.offset ?: position, false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (t: Throwable) {
                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    val message = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    broadcastState(store.loadBook()?.offset ?: offset, false, "Ошибка озвучки: $message")
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, makeNotification("Ошибка озвучки", false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            }
        }, "NeuroReader-TTS").start()
    }

    private fun ensureTts(): OfflineTts {
        tts?.let { return it }
        val modelDir = "tts/vits-piper-ru_RU-irina-medium"
        val vits = OfflineTtsVitsModelConfig(
            model = "$modelDir/ru_RU-irina-medium.onnx",
            tokens = "$modelDir/tokens.txt",
            dataDir = "$modelDir/espeak-ng-data",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.25f
        )
        return OfflineTts(assetManager = assets, config = config).also { tts = it }
    }

    private data class Chunk(val text: String, val end: Int)

    private fun nextChunk(full: String, start: Int): Chunk? {
        var from = start.coerceIn(0, full.length)
        while (from < full.length && full[from].isWhitespace()) from++
        if (from >= full.length) return null

        val soft = (from + 240).coerceAtMost(full.length)
        val hard = (from + 520).coerceAtMost(full.length)
        var end = soft

        if (soft < full.length) {
            var i = soft
            while (i < hard) {
                val c = full[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n') {
                    end = i + 1
                    break
                }
                i++
            }
            if (end == soft) {
                i = soft
                while (i > from + 100) {
                    if (full[i - 1].isWhitespace()) {
                        end = i
                        break
                    }
                    i--
                }
            }
        } else {
            end = full.length
        }

        if (end <= from) end = hard
        return Chunk(full.substring(from, end).trim(), end)
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int, myGeneration: Int) {
        if (samples.isEmpty()) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = max(minBytes, 8192)
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        currentTrack = track
        try {
            track.play()
            var written = 0
            while (written < samples.size && myGeneration == generation) {
                if (paused) {
                    runCatching { track.pause() }
                    waitWhilePaused(myGeneration)
                    if (myGeneration != generation) break
                    runCatching { track.play() }
                }
                val count = minOf(4096, samples.size - written)
                val result = track.write(samples, written, count, AudioTrack.WRITE_BLOCKING)
                if (result <= 0) break
                written += result
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (currentTrack === track) currentTrack = null
        }
    }

    private fun waitWhilePaused(myGeneration: Int) {
        while (paused && myGeneration == generation) {
            try { Thread.sleep(80) } catch (_: InterruptedException) { return }
        }
    }

    private fun pausePlayback() {
        if (!playing) return
        paused = true
        runCatching { currentTrack?.pause() }
        updateNotification("Пауза", false)
        broadcastState(store.loadBook()?.offset ?: 0, true, null)
    }

    private fun resumePlayback() {
        if (!playing) {
            store.loadBook()?.let { startPlayback(it.offset) }
            return
        }
        paused = false
        runCatching { currentTrack?.play() }
        updateNotification("Читаю вслух", true)
        broadcastState(store.loadBook()?.offset ?: 0, true, null)
    }

    private fun stopPlayback(removeNotification: Boolean) {
        generation += 1
        paused = false
        playing = false
        stopTrack()
        if (removeNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState(store.loadBook()?.offset ?: 0, false, null)
        stopSelf()
    }

    private fun stopTrack() {
        val track = currentTrack
        currentTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Озвучка книг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление нейроозвучкой книги"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun makeNotification(text: String, active: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, TtsPlaybackService::class.java).setAction(if (active) ACTION_PAUSE else ACTION_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            12,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("НейроЧиталка")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .addAction(
                if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (active) "Пауза" else "Продолжить",
                toggleIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stopIntent)
            .build()
    }

    private fun updateNotification(text: String, active: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(text, active))
    }

    private fun broadcastState(offset: Int, isPlaying: Boolean, error: String?) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_OFFSET, offset)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    override fun onDestroy() {
        generation += 1
        stopTrack()
        runCatching { tts?.free() }
        tts = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.artem.neuroreader.PLAY"
        const val ACTION_PAUSE = "com.artem.neuroreader.PAUSE"
        const val ACTION_RESUME = "com.artem.neuroreader.RESUME"
        const val ACTION_TOGGLE = "com.artem.neuroreader.TOGGLE"
        const val ACTION_SEEK = "com.artem.neuroreader.SEEK"
        const val ACTION_STOP = "com.artem.neuroreader.STOP"
        const val ACTION_PROGRESS = "com.artem.neuroreader.PROGRESS"

        const val EXTRA_OFFSET = "offset"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "neuroreader_tts"
        private const val NOTIFICATION_ID = 147
    }
}
