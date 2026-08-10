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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TtsPlaybackService : Service() {
    private lateinit var store: ReaderStore

    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var playing = false
    @Volatile private var currentTrack: AudioTrack? = null

    private val ttsLock = Any()
    private var tts: OfflineTts? = null

    override fun onCreate() {
        super.onCreate()
        store = ReaderStore(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
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
                ACTION_TOGGLE -> {
                    if (playing && !paused) {
                        pausePlayback()
                    } else if (playing) {
                        resumePlayback()
                    } else {
                        store.loadBook()?.let { startPlayback(it.offset) }
                    }
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
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand failed", t)
            playing = false
            paused = false
            broadcastState(store.loadBook()?.offset ?: 0, false, "Ошибка запуска озвучки: ${safeMessage(t)}")
            stopSelf()
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
                    val speed = store.loadBook()?.speed ?: 1.0f
                    val audio = synchronized(ttsLock) {
                        if (myGeneration != generation) null
                        else engine.generate(text = chunk.text, sid = 0, speed = speed)
                    } ?: break

                    if (myGeneration != generation) break
                    playSamples(audio.samples, audio.sampleRate, myGeneration)
                    if (myGeneration != generation) break

                    position = chunk.end
                    store.updateOffset(position)
                    broadcastState(position, true, null)
                    updateNotification("Читаю вслух", true)
                }

                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    broadcastState(store.loadBook()?.offset ?: position, false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TTS playback failed", t)
                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    val message = safeMessage(t)
                    broadcastState(
                        store.loadBook()?.offset ?: offset,
                        false,
                        "Ошибка озвучки: $message"
                    )
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, makeNotification("Ошибка озвучки", false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }, "NeuroReader-TTS").start()
    }

    private fun ensureTts(): OfflineTts = synchronized(ttsLock) {
        tts?.let { return@synchronized it }

        val modelDir = "tts/vits-piper-ru_RU-irina-medium"
        val modelAsset = "$modelDir/ru_RU-irina-medium.onnx"
        val tokensAsset = "$modelDir/tokens.txt"

        // Piper/espeak-ng needs its data directory on a real filesystem path.
        // The official sherpa-onnx Android sample copies this tree out of APK assets
        // before creating OfflineTts. Passing an asset-relative directory here can
        // make native espeak initialization abort on some devices.
        assets.open(modelAsset).use { }
        assets.open(tokensAsset).use { }
        val espeakDataDir = prepareEspeakData("$modelDir/espeak-ng-data")

        val vits = OfflineTtsVitsModelConfig(
            model = modelAsset,
            tokens = tokensAsset,
            dataDir = espeakDataDir.absolutePath,
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

        OfflineTts(assetManager = assets, config = config).also { engine ->
            val rate = engine.sampleRate()
            require(rate > 0) { "Некорректная частота TTS: $rate" }
            tts = engine
        }
    }

    private fun prepareEspeakData(assetPath: String): File {
        val runtimeRoot = File(filesDir, "tts-runtime/v1")
        val destination = File(runtimeRoot, "espeak-ng-data")
        val marker = File(runtimeRoot, ".ready")

        if (!marker.isFile || !destination.isDirectory) {
            runCatching { runtimeRoot.deleteRecursively() }
            destination.mkdirs()
            copyAssetTree(assetPath, destination)
            require(destination.walkTopDown().any { it.isFile }) {
                "Не удалось подготовить espeak-ng-data"
            }
            marker.parentFile?.mkdirs()
            marker.writeText("ok", Charsets.UTF_8)
        }
        return destination
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            return
        }

        destination.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private data class Chunk(val text: String, val end: Int)

    private fun nextChunk(full: String, start: Int): Chunk? {
        var from = start.coerceIn(0, full.length)
        while (from < full.length && full[from].isWhitespace()) from++
        if (from >= full.length) return null

        val soft = (from + 220).coerceAtMost(full.length)
        val hard = (from + 460).coerceAtMost(full.length)
        var end = soft

        if (soft < full.length) {
            var i = soft
            while (i < hard) {
                val c = full[i]
                if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') {
                    end = i + 1
                    break
                }
                i++
            }
            if (end == soft) {
                i = soft
                while (i > from + 80) {
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
        val chunkText = full.substring(from, end).trim()
        return if (chunkText.isEmpty()) null else Chunk(chunkText, end)
    }

    private fun playSamples(samples: FloatArray, sampleRate: Int, myGeneration: Int) {
        if (samples.isEmpty()) return
        require(sampleRate > 0) { "Некорректная частота аудио: $sampleRate" }

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = max(minBytes, 16_384)

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

        require(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack не инициализирован" }
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
                if (result < 0) throw IllegalStateException("AudioTrack.write: $result")
                if (result == 0) continue
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
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                return
            }
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
            Intent(this, TtsPlaybackService::class.java)
                .setAction(if (active) ACTION_PAUSE else ACTION_RESUME),
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
            putExtra(EXTRA_PAUSED, paused)
            if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    private fun safeMessage(t: Throwable): String {
        return t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }

    override fun onDestroy() {
        generation += 1
        stopTrack()
        synchronized(ttsLock) {
            runCatching { tts?.free() }
            tts = null
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NeuroReaderTTS"

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
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "neuroreader_tts"
        private const val NOTIFICATION_ID = 147
    }
}
