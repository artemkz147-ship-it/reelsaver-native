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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlin.math.max

class TtsPlaybackService : Service() {
    private lateinit var store: ReaderStore
    private lateinit var russianProsody: RussianProsody

    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var playing = false
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentRangeStart = 0
    @Volatile private var currentRangeEnd = 0
    @Volatile private var currentSpeaker = 0

    private val ttsLock = Any()
    private var tts: OfflineTts? = null

    override fun onCreate() {
        super.onCreate()
        store = ReaderStore(this)
        russianProsody = RussianProsody(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action ?: ACTION_PLAY) {
                ACTION_PLAY -> {
                    store.loadBook()?.let { saved ->
                        val offset = intent?.getIntExtra(EXTRA_OFFSET, saved.offset) ?: saved.offset
                        startPlayback(offset)
                    }
                }
                ACTION_PAUSE -> pausePlayback()
                ACTION_RESUME -> resumePlayback()
                ACTION_TOGGLE -> {
                    if (playing && !paused) pausePlayback()
                    else if (playing) resumePlayback()
                    else store.loadBook()?.let { startPlayback(it.offset) }
                }
                ACTION_SEEK -> {
                    store.loadBook()?.let { saved ->
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

        startForeground(NOTIFICATION_ID, makeNotification("Запускаю живое чтение…", true))
        broadcastState(offset, true, null)

        Thread({
            try {
                val engine = ensureTts()
                val narratorSid = store.loadBook()?.narratorSid ?: 0
                var position = offset

                while (myGeneration == generation && position < text.length) {
                    waitWhilePaused(myGeneration)
                    if (myGeneration != generation) break

                    val utterance = nextUtterance(text, position, narratorSid) ?: break
                    currentRangeStart = utterance.start
                    currentRangeEnd = utterance.end
                    currentSpeaker = narratorSid

                    broadcastState(
                        utterance.start,
                        true,
                        null,
                        utterance.start,
                        utterance.end,
                        narratorSid
                    )
                    updateNotification("Живое чтение · ударения и паузы", true)

                    val baseSpeed = store.loadBook()?.speed ?: 1.0f
                    synchronized(ttsLock) {
                        if (myGeneration == generation) {
                            streamUtterance(engine, utterance, baseSpeed, myGeneration)
                        }
                    }
                    if (myGeneration != generation) break

                    position = utterance.end
                    store.updateOffset(position)
                    broadcastState(
                        position,
                        true,
                        null,
                        utterance.start,
                        utterance.end,
                        narratorSid
                    )
                    playPause(utterance.pauseMs, myGeneration)
                }

                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    val finalOffset = store.loadBook()?.offset ?: position
                    broadcastState(finalOffset, false, null)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TTS playback failed", t)
                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    val message = safeMessage(t)
                    broadcastState(store.loadBook()?.offset ?: offset, false, "Ошибка озвучки: $message")
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, makeNotification("Ошибка озвучки", false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }, "NeuroReader-Supertonic").start()
    }

    private fun ensureTts(): OfflineTts = synchronized(ttsLock) {
        tts?.let { return@synchronized it }

        val modelDir = "tts/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        val required = listOf(
            "$modelDir/duration_predictor.int8.onnx",
            "$modelDir/text_encoder.int8.onnx",
            "$modelDir/vector_estimator.int8.onnx",
            "$modelDir/vocoder.int8.onnx",
            "$modelDir/tts.json",
            "$modelDir/unicode_indexer.bin",
            "$modelDir/voice.bin"
        )
        required.forEach { path ->
            assets.open(path).use { input -> require(input.read() >= 0) { "Нет файла модели: $path" } }
        }

        val supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = "$modelDir/duration_predictor.int8.onnx",
            textEncoder = "$modelDir/text_encoder.int8.onnx",
            vectorEstimator = "$modelDir/vector_estimator.int8.onnx",
            vocoder = "$modelDir/vocoder.int8.onnx",
            ttsJson = "$modelDir/tts.json",
            unicodeIndexer = "$modelDir/unicode_indexer.bin",
            voiceStyle = "$modelDir/voice.bin"
        )

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = supertonic,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.30f
        )

        OfflineTts(assetManager = assets, config = config).also { engine ->
            require(engine.sampleRate() > 0) { "Неверная частота Supertonic" }
            require(engine.numSpeakers() >= 1) { "В модели нет голоса" }
            tts = engine
        }
    }

    private data class Utterance(
        val start: Int,
        val end: Int,
        val text: String,
        val sid: Int,
        val pauseMs: Int,
        val speedFactor: Float,
        val silenceScale: Float
    )

    private fun nextUtterance(full: String, start: Int, narratorSid: Int): Utterance? {
        var from = start.coerceIn(0, full.length)
        while (from < full.length && full[from].isWhitespace()) from++
        if (from >= full.length) return null

        val hard = (from + 620).coerceAtMost(full.length)
        val minBoundary = (from + 45).coerceAtMost(hard)
        var end = hard
        var softClause = -1
        var i = minBoundary

        while (i < hard) {
            val c = full[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var candidate = i + 1
                while (candidate < hard && full[candidate] in charArrayOf('"', '»', '”', '’')) candidate++
                end = candidate
                break
            }
            if (c == '\n' && i > from + 60) {
                end = i
                break
            }
            if ((c == ';' || c == ':') && i > from + 180 && softClause < 0) {
                softClause = i + 1
            }
            i++
        }

        if (end == hard && softClause > 0) end = softClause
        if (end == hard && hard < full.length) {
            var back = hard
            while (back > from + 140) {
                if (full[back - 1].isWhitespace()) {
                    end = back
                    break
                }
                back--
            }
        }
        if (end <= from) end = (from + 1).coerceAtMost(full.length)

        val raw = full.substring(from, end)
        val paragraphBefore = from > 1 && full.substring((from - 3).coerceAtLeast(0), from).count { it == '\n' } >= 2
        var spoken = russianProsody.prepare(raw)
        if (spoken.isBlank()) return nextUtterance(full, end, narratorSid)

        // Supertonic 3 supports expression tags. A breath only at a new paragraph
        // sounds much more natural than injecting it into every sentence.
        if (paragraphBefore && spoken.length > 35) spoken = "<breath> $spoken"

        val trimmed = raw.trimEnd()
        val after = full.substring(end, minOf(full.length, end + 4))
        val pauseMs = when {
            after.startsWith("\n\n") -> 520
            after.startsWith("\n") -> 330
            trimmed.endsWith("…") -> 390
            trimmed.endsWith("?") -> 300
            trimmed.endsWith("!") -> 270
            trimmed.endsWith(":") -> 200
            trimmed.endsWith(";") -> 185
            else -> 155
        }

        val commas = raw.count { it == ',' }
        val speedFactor = when {
            trimmed.endsWith("…") -> 0.90f
            trimmed.endsWith("?") -> 0.94f
            trimmed.endsWith("!") -> 1.02f
            raw.length < 55 -> 0.95f
            commas >= 3 -> 0.96f
            else -> 1.0f
        }
        val silenceScale = when {
            trimmed.endsWith("…") -> 0.42f
            trimmed.endsWith("?") -> 0.36f
            trimmed.endsWith("!") -> 0.32f
            commas >= 3 -> 0.34f
            else -> 0.29f
        }

        return Utterance(
            start = from,
            end = end,
            text = spoken,
            sid = narratorSid,
            pauseMs = pauseMs,
            speedFactor = speedFactor,
            silenceScale = silenceScale
        )
    }

    private fun streamUtterance(
        engine: OfflineTts,
        utterance: Utterance,
        baseSpeed: Float,
        myGeneration: Int
    ) {
        val sampleRate = engine.sampleRate()
        require(sampleRate > 0) { "Некорректная частота аудио: $sampleRate" }
        val track = createAudioTrack(sampleRate)
        currentTrack = track
        var samplesWritten = 0L
        var lastProgressAt = 0L
        var lastSavedAt = 0L

        val effectiveSpeed = (baseSpeed * utterance.speedFactor).coerceIn(0.70f, 1.60f)
        val visibleLength = utterance.text.replace(Regex("<[^>]+>"), "").length.coerceAtLeast(1)
        val estimatedSeconds = max(0.75, visibleLength.toDouble() / (13.2 * effectiveSpeed))
        val estimatedSamples = max(sampleRate.toDouble() * estimatedSeconds, sampleRate * 0.5)

        val config = GenerationConfig(
            silenceScale = utterance.silenceScale,
            speed = effectiveSpeed,
            sid = utterance.sid,
            numSteps = 10,
            extra = mapOf("lang" to "ru")
        )

        try {
            track.play()
            val callback: (FloatArray) -> Int = callback@{ samples ->
                if (myGeneration != generation) return@callback 0
                waitWhilePaused(myGeneration)
                if (myGeneration != generation) return@callback 0

                val writtenNow = writeSamples(track, samples, myGeneration)
                samplesWritten += writtenNow.toLong().coerceAtLeast(0L)

                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 90L) {
                    val fraction = (samplesWritten / estimatedSamples).coerceIn(0.0, 0.985)
                    val offset = (utterance.start + ((utterance.end - utterance.start) * fraction).toInt())
                        .coerceIn(utterance.start, utterance.end)
                    broadcastState(offset, true, null, utterance.start, utterance.end, utterance.sid)
                    lastProgressAt = now
                    if (now - lastSavedAt >= 450L) {
                        store.updateOffset(offset)
                        lastSavedAt = now
                    }
                }
                1
            }

            engine.generateWithConfigAndCallback(
                text = utterance.text,
                config = config,
                callback = callback
            )
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (currentTrack === track) currentTrack = null
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = max(minBytes, 24_576)
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
        return track
    }

    private fun writeSamples(track: AudioTrack, samples: FloatArray, myGeneration: Int): Int {
        var written = 0
        while (written < samples.size && myGeneration == generation) {
            if (paused) {
                runCatching { track.pause() }
                waitWhilePaused(myGeneration)
                if (myGeneration != generation) break
                runCatching { track.play() }
            }
            val count = minOf(3072, samples.size - written)
            val result = track.write(samples, written, count, AudioTrack.WRITE_BLOCKING)
            if (result < 0) throw IllegalStateException("AudioTrack.write: $result")
            if (result == 0) continue
            written += result
        }
        return written
    }

    private fun playPause(milliseconds: Int, myGeneration: Int) {
        var remaining = milliseconds.coerceAtLeast(0)
        while (remaining > 0 && myGeneration == generation) {
            waitWhilePaused(myGeneration)
            if (myGeneration != generation) break
            val step = minOf(remaining, 30)
            try {
                Thread.sleep(step.toLong())
            } catch (_: InterruptedException) {
                return
            }
            remaining -= step
        }
    }

    private fun waitWhilePaused(myGeneration: Int) {
        while (paused && myGeneration == generation) {
            try {
                Thread.sleep(70)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pausePlayback() {
        if (!playing) return
        paused = true
        runCatching { currentTrack?.pause() }
        updateNotification("Пауза · продолжить с этого места", false)
        broadcastState(
            store.loadBook()?.offset ?: 0,
            true,
            null,
            currentRangeStart,
            currentRangeEnd,
            currentSpeaker
        )
    }

    private fun resumePlayback() {
        if (!playing) {
            store.loadBook()?.let { startPlayback(it.offset) }
            return
        }
        paused = false
        runCatching { currentTrack?.play() }
        updateNotification("Живое чтение", true)
        broadcastState(
            store.loadBook()?.offset ?: 0,
            true,
            null,
            currentRangeStart,
            currentRangeEnd,
            currentSpeaker
        )
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
        val track = currentTrack ?: return
        currentTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Озвучка книг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Живое чтение книги с синхронизацией текста"
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

    private fun broadcastState(
        offset: Int,
        isPlaying: Boolean,
        error: String?,
        rangeStart: Int = -1,
        rangeEnd: Int = -1,
        speaker: Int = -1
    ) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_OFFSET, offset)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_PAUSED, paused)
            putExtra(EXTRA_RANGE_START, rangeStart)
            putExtra(EXTRA_RANGE_END, rangeEnd)
            putExtra(EXTRA_SPEAKER_ID, speaker)
            if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    private fun safeMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

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
        const val EXTRA_RANGE_START = "range_start"
        const val EXTRA_RANGE_END = "range_end"
        const val EXTRA_SPEAKER_ID = "speaker_id"
        const val EXTRA_ERROR = "error"

        private const val CHANNEL_ID = "neuroreader_tts"
        private const val NOTIFICATION_ID = 147
    }
}
