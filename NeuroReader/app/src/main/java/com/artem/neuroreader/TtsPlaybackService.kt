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
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

class TtsPlaybackService : Service() {
    private lateinit var russianProsody: RussianProsody

    @Volatile private var generation = 0
    @Volatile private var paused = false
    @Volatile private var playing = false
    @Volatile private var engineReady = false
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentRangeStart = 0
    @Volatile private var currentRangeEnd = 0

    @Volatile private var activeTextPath: String? = null
    @Volatile private var activeSpeed = 1.0f
    @Volatile private var activeOffset = 0

    private val ttsLock = Any()
    private var tts: OfflineTts? = null

    override fun onCreate() {
        super.onCreate()
        russianProsody = RussianProsody(this)
        russianProsody.warmUpAsync()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action ?: ACTION_PLAY) {
                ACTION_PLAY -> {
                    val textPath = intent?.getStringExtra(EXTRA_TEXT_PATH) ?: activeTextPath
                    val offset = intent?.getIntExtra(EXTRA_OFFSET, activeOffset) ?: activeOffset
                    val speed = intent?.getFloatExtra(EXTRA_SPEED, activeSpeed) ?: activeSpeed
                    if (textPath.isNullOrBlank()) {
                        broadcastState(
                            offset = activeOffset,
                            isPlaying = false,
                            error = "Не найден текст книги для озвучки",
                            ready = false
                        )
                        stopSelf()
                    } else {
                        startPlayback(textPath, offset, speed)
                    }
                }

                ACTION_PAUSE -> pausePlayback()
                ACTION_RESUME -> resumePlayback()
                ACTION_TOGGLE -> {
                    if (playing && !paused) pausePlayback()
                    else if (playing) resumePlayback()
                    else activeTextPath?.let { startPlayback(it, activeOffset, activeSpeed) }
                }

                ACTION_SEEK -> {
                    val delta = intent?.getIntExtra(EXTRA_DELTA, 0) ?: 0
                    activeTextPath?.let {
                        startPlayback(it, (activeOffset + delta).coerceAtLeast(0), activeSpeed)
                    }
                }

                ACTION_STOP -> stopPlayback(removeNotification = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand failed", t)
            playing = false
            paused = false
            engineReady = false
            broadcastState(
                offset = activeOffset,
                isPlaying = false,
                error = "Ошибка запуска озвучки: ${safeMessage(t)}",
                ready = false
            )
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(
        textPath: String,
        requestedOffset: Int,
        speed: Float
    ) {
        activeTextPath = textPath
        activeSpeed = speed.coerceIn(0.72f, 1.55f)
        activeOffset = requestedOffset.coerceAtLeast(0)

        generation += 1
        val myGeneration = generation
        paused = false
        playing = true
        engineReady = false
        stopTrack()

        startForeground(
            NOTIFICATION_ID,
            makeNotification("Готовлю русский офлайн-голос…", active = true)
        )
        broadcastState(
            offset = activeOffset,
            isPlaying = true,
            error = null,
            ready = false
        )

        Thread({
            try {
                val text = File(textPath).readText(Charsets.UTF_8)
                if (text.isBlank()) throw IllegalStateException("Текст книги пуст")
                activeOffset = activeOffset.coerceIn(0, text.length)

                val engine = ensureTts()
                if (myGeneration != generation) return@Thread

                engineReady = true
                broadcastState(
                    offset = activeOffset,
                    isPlaying = true,
                    error = null,
                    ready = true
                )
                updateNotification("Читаю вслух · Piper", active = true)

                var position = activeOffset
                while (myGeneration == generation && position < text.length) {
                    waitWhilePaused(myGeneration)
                    if (myGeneration != generation) break

                    val utterance = nextUtterance(text, position) ?: break
                    currentRangeStart = utterance.start
                    currentRangeEnd = utterance.end

                    broadcastState(
                        offset = utterance.start,
                        isPlaying = true,
                        error = null,
                        rangeStart = utterance.start,
                        rangeEnd = utterance.end,
                        speaker = 0,
                        ready = true
                    )

                    val audio = synchronized(ttsLock) {
                        if (myGeneration != generation) null
                        else {
                            val effectiveSpeed = (activeSpeed * utterance.speedFactor)
                                .coerceIn(0.72f, 1.55f)
                            engine.generateWithConfig(
                                text = utterance.text,
                                config = GenerationConfig(
                                    silenceScale = utterance.silenceScale,
                                    speed = effectiveSpeed,
                                    sid = 0
                                )
                            )
                        }
                    } ?: break

                    if (myGeneration != generation) break
                    require(audio.sampleRate > 0) { "Неверная частота аудио" }
                    require(audio.samples.isNotEmpty()) { "Piper не вернул звук" }

                    playGeneratedAudio(
                        samples = audio.samples,
                        sampleRate = audio.sampleRate,
                        utterance = utterance,
                        myGeneration = myGeneration
                    )
                    if (myGeneration != generation) break

                    position = utterance.end
                    activeOffset = position
                    broadcastState(
                        offset = position,
                        isPlaying = true,
                        error = null,
                        rangeStart = utterance.start,
                        rangeEnd = utterance.end,
                        speaker = 0,
                        ready = true
                    )
                    playPause(utterance.pauseMs, myGeneration)
                }

                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    engineReady = true
                    broadcastState(
                        offset = activeOffset,
                        isPlaying = false,
                        error = null,
                        ready = true
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Piper playback failed", t)
                if (myGeneration == generation) {
                    playing = false
                    paused = false
                    engineReady = false
                    broadcastState(
                        offset = activeOffset,
                        isPlaying = false,
                        error = "Ошибка озвучки Piper: ${safeMessage(t)}",
                        ready = false
                    )
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, makeNotification("Ошибка озвучки", active = false))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }, "NeuroReader-Piper").start()
    }

    private fun ensureTts(): OfflineTts = synchronized(ttsLock) {
        tts?.let { return@synchronized it }

        val modelDir = "tts/vits-piper-ru_RU-irina-medium"
        val model = "$modelDir/ru_RU-irina-medium.onnx"
        val tokens = "$modelDir/tokens.txt"
        assets.open(model).use { require(it.read() >= 0) { "Нет модели Piper" } }
        assets.open(tokens).use { require(it.read() >= 0) { "Нет tokens.txt" } }

        val espeakDir = prepareEspeakData()
        val vits = OfflineTtsVitsModelConfig(
            model = model,
            tokens = tokens,
            dataDir = espeakDir.absolutePath,
            noiseScale = 0.62f,
            noiseScaleW = 0.78f,
            lengthScale = 1.0f
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 1,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1,
            silenceScale = 0.24f
        )

        OfflineTts(assetManager = assets, config = config).also { engine ->
            require(engine.sampleRate() > 0) { "Piper не инициализировался" }
            tts = engine
        }
    }

    private fun prepareEspeakData(): File {
        val source = "tts/vits-piper-ru_RU-irina-medium/espeak-ng-data"
        val root = File(noBackupFilesDir, "tts-runtime/piper-irina-v2")
        val target = File(root, "espeak-ng-data")
        val marker = File(root, ".ready")
        val phontab = File(target, "phontab")

        if (marker.isFile && phontab.isFile) return target

        runCatching { root.deleteRecursively() }
        target.mkdirs()
        copyAssetTree(source, target)
        require(phontab.isFile && phontab.length() > 0L) {
            "Не удалось подготовить espeak-ng-data"
        }
        marker.writeText("ok", Charsets.UTF_8)
        return target
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private data class Utterance(
        val start: Int,
        val end: Int,
        val text: String,
        val pauseMs: Int,
        val speedFactor: Float,
        val silenceScale: Float
    )

    private fun nextUtterance(full: String, start: Int): Utterance? {
        var from = start.coerceIn(0, full.length)
        while (from < full.length && full[from].isWhitespace()) from++
        if (from >= full.length) return null

        val hard = (from + 240).coerceAtMost(full.length)
        val minBoundary = (from + 24).coerceAtMost(hard)
        var end = hard
        var clauseBoundary = -1
        var i = minBoundary

        while (i < hard) {
            val c = full[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var candidate = i + 1
                while (candidate < hard && full[candidate] in charArrayOf('"', '»', '”', '’')) {
                    candidate++
                }
                end = candidate
                break
            }
            if (c == '\n' && i > from + 35) {
                end = i
                break
            }
            if ((c == ';' || c == ':') && i > from + 110 && clauseBoundary < 0) {
                clauseBoundary = i + 1
            }
            i++
        }

        if (end == hard && clauseBoundary > 0) end = clauseBoundary
        if (end == hard && hard < full.length) {
            var back = hard
            while (back > from + 70) {
                if (full[back - 1].isWhitespace()) {
                    end = back
                    break
                }
                back--
            }
        }
        if (end <= from) end = (from + 1).coerceAtMost(full.length)

        val raw = full.substring(from, end)
        val spoken = russianProsody.prepare(raw)
        if (spoken.isBlank()) return nextUtterance(full, end)

        val trimmed = raw.trimEnd()
        val after = full.substring(end, minOf(full.length, end + 4))
        val pauseMs = when {
            after.startsWith("\n\n") -> 520
            after.startsWith("\n") -> 320
            trimmed.endsWith("…") -> 390
            trimmed.endsWith("?") -> 300
            trimmed.endsWith("!") -> 270
            trimmed.endsWith(":") -> 205
            trimmed.endsWith(";") -> 185
            else -> 155
        }

        val commas = raw.count { it == ',' }
        val speedFactor = when {
            trimmed.endsWith("…") -> 0.90f
            trimmed.endsWith("?") -> 0.95f
            trimmed.endsWith("!") -> 1.01f
            raw.length < 42 -> 0.96f
            commas >= 3 -> 0.96f
            else -> 1.0f
        }
        val silenceScale = when {
            trimmed.endsWith("…") -> 0.36f
            trimmed.endsWith("?") -> 0.31f
            trimmed.endsWith("!") -> 0.28f
            commas >= 3 -> 0.30f
            else -> 0.25f
        }

        return Utterance(
            start = from,
            end = end,
            text = spoken,
            pauseMs = pauseMs,
            speedFactor = speedFactor,
            silenceScale = silenceScale
        )
    }

    private fun playGeneratedAudio(
        samples: FloatArray,
        sampleRate: Int,
        utterance: Utterance,
        myGeneration: Int
    ) {
        val track = createAudioTrack(sampleRate)
        currentTrack = track
        var sampleIndex = 0
        var lastProgressAt = 0L
        val pcm = ShortArray(2048)

        try {
            track.play()
            while (sampleIndex < samples.size && myGeneration == generation) {
                if (paused) {
                    runCatching { track.pause() }
                    waitWhilePaused(myGeneration)
                    if (myGeneration != generation) break
                    runCatching { track.play() }
                }

                val count = minOf(pcm.size, samples.size - sampleIndex)
                for (i in 0 until count) {
                    val value = samples[sampleIndex + i].coerceIn(-1.0f, 1.0f)
                    pcm[i] = (value * 32767.0f).toInt().toShort()
                }

                var written = 0
                while (written < count && myGeneration == generation) {
                    val result = track.write(
                        pcm,
                        written,
                        count - written,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (result < 0) throw IllegalStateException("AudioTrack.write: $result")
                    if (result == 0) continue
                    written += result
                }
                sampleIndex += written

                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 220L && samples.isNotEmpty()) {
                    val fraction = (sampleIndex.toDouble() / samples.size.toDouble()).coerceIn(0.0, 0.995)
                    val offset = (
                        utterance.start +
                            ((utterance.end - utterance.start) * fraction).toInt()
                        ).coerceIn(utterance.start, utterance.end)
                    activeOffset = offset
                    broadcastState(
                        offset = offset,
                        isPlaying = true,
                        error = null,
                        rangeStart = utterance.start,
                        rangeEnd = utterance.end,
                        speaker = 0,
                        ready = true
                    )
                    lastProgressAt = now
                }
            }
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
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBytes > 0) { "Телефон не поддержал PCM AudioTrack" }
        val bufferBytes = max(minBytes, 16_384)

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        require(track.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack не инициализирован"
        }
        return track
    }

    private fun playPause(milliseconds: Int, myGeneration: Int) {
        var remaining = milliseconds.coerceAtLeast(0)
        while (remaining > 0 && myGeneration == generation) {
            waitWhilePaused(myGeneration)
            if (myGeneration != generation) break
            val step = minOf(remaining, 35)
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
        updateNotification("Пауза · продолжить с этого места", active = false)
        broadcastState(
            offset = activeOffset,
            isPlaying = true,
            error = null,
            rangeStart = currentRangeStart,
            rangeEnd = currentRangeEnd,
            speaker = 0,
            ready = engineReady
        )
    }

    private fun resumePlayback() {
        if (!playing) {
            activeTextPath?.let { startPlayback(it, activeOffset, activeSpeed) }
            return
        }
        paused = false
        runCatching { currentTrack?.play() }
        updateNotification("Читаю вслух · Piper", active = true)
        broadcastState(
            offset = activeOffset,
            isPlaying = true,
            error = null,
            rangeStart = currentRangeStart,
            rangeEnd = currentRangeEnd,
            speaker = 0,
            ready = engineReady
        )
    }

    private fun stopPlayback(removeNotification: Boolean) {
        generation += 1
        paused = false
        playing = false
        engineReady = false
        stopTrack()
        if (removeNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastState(
            offset = activeOffset,
            isPlaying = false,
            error = null,
            ready = false
        )
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
                description = "Управление офлайн-озвучкой книги"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Стоп",
                stopIntent
            )
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
        speaker: Int = -1,
        ready: Boolean
    ) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_OFFSET, offset)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_PAUSED, paused)
            putExtra(EXTRA_RANGE_START, rangeStart)
            putExtra(EXTRA_RANGE_END, rangeEnd)
            putExtra(EXTRA_SPEAKER_ID, speaker)
            putExtra(EXTRA_READY, ready)
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
        const val EXTRA_RANGE_START = "range_start"
        const val EXTRA_RANGE_END = "range_end"
        const val EXTRA_SPEAKER_ID = "speaker_id"
        const val EXTRA_READY = "ready"
        const val EXTRA_ERROR = "error"

        const val EXTRA_TEXT_PATH = "text_path"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_NARRATOR_SID = "narrator_sid" // kept for old installed versions; Piper ignores it

        private const val CHANNEL_ID = "neuroreader_tts"
        private const val NOTIFICATION_ID = 147
    }
}
