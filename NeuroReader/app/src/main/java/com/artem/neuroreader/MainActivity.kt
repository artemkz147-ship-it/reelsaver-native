package com.artem.neuroreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var store: ReaderStore
    private lateinit var root: LinearLayout
    private lateinit var topChrome: LinearLayout
    private lateinit var bottomChrome: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageFrame: FrameLayout
    private lateinit var pageView: TextView
    private lateinit var pageInfo: TextView
    private lateinit var audioInfo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var speedButton: Button
    private lateinit var playButton: Button
    private lateinit var voiceButton: Button

    private var currentText = ""
    private var pages: List<Int> = listOf(0)
    private var currentPage = 0
    private var changingSeek = false
    private var receiverRegistered = false
    private var paginationJob: Job? = null
    private var restoreJob: Job? = null
    private var lastPageWidth = 0
    private var lastPageHeight = 0

    private var audioSessionActive = false
    private var audioPaused = false
    private var audioEngineReady = false
    private var highlightStart = -1
    private var highlightEnd = -1
    private var audioCursor = -1
    private var lastHighlightRenderAt = 0L
    private var lastPersistAt = 0L
    private var playRequestToken = 0L

    private var chromeVisible = true
    private var animatingPage = false
    private var downX = 0f
    private var downY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TtsPlaybackService.ACTION_PROGRESS) return

            val now = SystemClock.elapsedRealtime()
            val fallback = store.loadBook()?.offset ?: 0
            val offset = intent.getIntExtra(TtsPlaybackService.EXTRA_OFFSET, fallback)
                .coerceIn(0, currentText.length.coerceAtLeast(0))

            audioSessionActive = intent.getBooleanExtra(TtsPlaybackService.EXTRA_IS_PLAYING, false)
            audioPaused = intent.getBooleanExtra(TtsPlaybackService.EXTRA_PAUSED, false)
            audioEngineReady = intent.getBooleanExtra(TtsPlaybackService.EXTRA_READY, audioEngineReady)
            highlightStart = intent.getIntExtra(TtsPlaybackService.EXTRA_RANGE_START, -1)
            highlightEnd = intent.getIntExtra(TtsPlaybackService.EXTRA_RANGE_END, -1)
            audioCursor = if (audioSessionActive && audioEngineReady) offset else -1

            playButton.text = when {
                audioSessionActive && !audioEngineReady -> "…"
                audioSessionActive && !audioPaused -> "Ⅱ"
                else -> "▶"
            }
            audioInfo.text = when {
                audioSessionActive && !audioEngineReady -> "Готовлю офлайн-голос… первый запуск может занять несколько секунд"
                audioSessionActive && audioPaused -> "Пауза · текст и аудио стоят на одном месте"
                audioSessionActive -> "Аудио · синхронизация текста включена"
                else -> "Текст · ▶ продолжит с текущего места"
            }

            if (currentText.isNotEmpty()) {
                if (now - lastPersistAt >= 850L || !audioSessionActive) {
                    store.updateOffset(offset)
                    lastPersistAt = now
                }

                val newPage = findPageForOffset(offset)
                if (newPage != currentPage) {
                    currentPage = newPage
                    renderCurrentPage(saveOffset = false, chromeOffset = offset)
                    lastHighlightRenderAt = now
                } else if (audioEngineReady && now - lastHighlightRenderAt >= 240L) {
                    renderCurrentPage(saveOffset = false, chromeOffset = offset)
                    lastHighlightRenderAt = now
                } else {
                    updateChrome(offset)
                }
            }

            val error = intent.getStringExtra(TtsPlaybackService.EXTRA_ERROR)
            if (!error.isNullOrBlank()) {
                audioSessionActive = false
                audioPaused = false
                audioEngineReady = false
                playButton.text = "▶"
                audioInfo.text = "Озвучка остановлена · можно продолжить глазами"
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        store = ReaderStore(this)
        buildUi()
        registerProgressReceiver()
        requestNotificationPermission()
        restoreBookAsync()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        paginationJob?.cancel()
        restoreJob?.cancel()
        playRequestToken += 1
        if (receiverRegistered) runCatching { unregisterReceiver(progressReceiver) }
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(225, 221, 210))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemGestures()
            )
            view.setPadding(
                bars.left + dp(8),
                bars.top + dp(6),
                bars.right + dp(8),
                bars.bottom + dp(8)
            )
            insets
        }

        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(5))
        }

        val open = flatButton("Книга") { openBook.launch(arrayOf("*/*")) }
        titleView = TextView(this).apply {
            text = "НейроЧиталка"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(42, 40, 36))
            maxLines = 1
            setPadding(dp(10), 0, dp(4), 0)
        }
        topChrome.addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        topChrome.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topChrome)

        pageFrame = FrameLayout(this).apply {
            setPadding(dp(2), dp(1), dp(2), dp(1))
            isClickable = true
            isFocusable = true
        }

        pageView = TextView(this).apply {
            textSize = store.loadBook()?.fontSize ?: 20f
            setTextColor(Color.rgb(38, 36, 32))
            setPadding(dp(22), dp(20), dp(22), dp(20))
            setLineSpacing(0f, LINE_SPACING)
            gravity = Gravity.TOP or Gravity.START
            includeFontPadding = false
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 247, 237))
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(3).toFloat()
            text = "Откройте книгу.\n\nЛистайте свайпом влево и вправо. Тап по центру скрывает панели. Аудио и текст используют одну позицию."
        }

        pageFrame.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (currentText.isEmpty()) return@setOnTouchListener true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val swipeThreshold = maxOf(dp(48).toFloat(), view.width * 0.11f)
                    if (abs(dx) >= swipeThreshold && abs(dx) > abs(dy) * 1.12f) {
                        turnPage(if (dx < 0f) 1 else -1, animated = true)
                    } else {
                        when {
                            event.x < view.width * 0.25f -> turnPage(-1, animated = true)
                            event.x > view.width * 0.75f -> turnPage(1, animated = true)
                            else -> toggleChromeVisibility()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        pageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (currentText.isNotEmpty() && width > 0 && height > 0 &&
                (width != oldWidth || height != oldHeight || width != lastPageWidth || height != lastPageHeight)
            ) {
                lastPageWidth = width
                lastPageHeight = height
                recalculatePages(store.loadBook()?.offset ?: 0)
            }
        }

        pageFrame.addView(
            pageView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(pageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, 0)
        }

        pageInfo = TextView(this).apply {
            text = "—"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(87, 82, 72))
        }
        bottomChrome.addView(pageInfo)

        audioInfo = TextView(this).apply {
            text = "Текст · ▶ продолжит с текущего места"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(104, 93, 72))
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }
        bottomChrome.addView(audioInfo)

        seekBar = SeekBar(this).apply {
            max = 10_000
            setPadding(dp(4), 0, dp(4), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    changingSeek = true
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    changingSeek = false
                    if (currentText.isEmpty() || pages.isEmpty()) return
                    val target = (((bar?.progress ?: 0) / 10_000.0) * currentText.length)
                        .toInt().coerceIn(0, currentText.length)
                    currentPage = findPageForOffset(target)
                    clearHighlight()
                    store.updateOffset(target)
                    renderCurrentPage(saveOffset = false, chromeOffset = target)
                    handleManualPositionChange(target)
                }
            })
        }
        bottomChrome.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        val mainControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        mainControls.addView(controlButton("‹") { turnPage(-1, animated = true) }, weighted())
        mainControls.addView(controlButton("A−") { changeFont(-1.5f) }, weighted())
        playButton = controlButton("▶") { togglePlayback() }
        playButton.textSize = 20f
        mainControls.addView(playButton, weighted(1.25f))
        mainControls.addView(controlButton("A+") { changeFont(1.5f) }, weighted())
        mainControls.addView(controlButton("›") { turnPage(1, animated = true) }, weighted())
        bottomChrome.addView(mainControls)

        val audioControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        voiceButton = flatButton("Голос 1") { cycleNarratorVoice() }
        speedButton = flatButton("1.0×") { cycleSpeed() }
        audioControls.addView(voiceButton, weighted(1.4f, 40))
        audioControls.addView(speedButton, weighted(1f, 40))
        bottomChrome.addView(audioControls)

        root.addView(bottomChrome)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshSettingsButtons()
    }

    private fun flatButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), 0, dp(9), 0)
        setOnClickListener { action() }
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(1), 0, dp(1), 0)
        setOnClickListener { action() }
    }

    private fun weighted(weight: Float = 1f, heightDp: Int = 48) =
        LinearLayout.LayoutParams(0, dp(heightDp), weight)

    private fun restoreBookAsync() {
        val book = store.loadBook() ?: return
        titleView.text = book.title
        pageInfo.text = "Открываю последнюю страницу…"
        restoreJob?.cancel()
        restoreJob = lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { File(book.textPath).readText(Charsets.UTF_8) }.getOrNull()
            } ?: return@launch
            showBook(book, text)
        }
    }

    private fun showBook(book: ReaderStore.SavedBook, text: String) {
        currentText = text
        titleView.text = book.title
        pageView.textSize = book.fontSize
        refreshSettingsButtons()
        pageView.post { recalculatePages(book.offset.coerceIn(0, text.length)) }
    }

    private fun importBook(uri: Uri) {
        stopAudioSession()
        titleView.text = "Открываю книгу…"
        pageInfo.text = "Подготавливаю текст…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { BookParser(this@MainActivity).parse(uri) }
            }
            result.onSuccess { parsed ->
                val saved = withContext(Dispatchers.IO) { store.saveBook(parsed.title, parsed.text) }
                clearHighlight()
                showBook(saved, parsed.text)
                Toast.makeText(this@MainActivity, "Книга сохранена", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                titleView.text = store.loadBook()?.title ?: "НейроЧиталка"
                pageInfo.text = "—"
                Toast.makeText(this@MainActivity, error.message ?: "Не удалось открыть книгу", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun recalculatePages(requestedOffset: Int) {
        if (currentText.isEmpty()) return

        val width = pageView.width - pageView.paddingLeft - pageView.paddingRight
        val height = pageView.height - pageView.paddingTop - pageView.paddingBottom - dp(6)
        if (width <= dp(40) || height <= dp(60)) {
            pageView.post { recalculatePages(requestedOffset) }
            return
        }

        paginationJob?.cancel()
        val textSnapshot = currentText
        val paint = TextPaint(pageView.paint)
        pageInfo.text = "Готовлю страницы…"

        paginationJob = lifecycleScope.launch {
            val calculated = withContext(Dispatchers.Default) {
                calculatePageStarts(textSnapshot, paint, width, height)
            }
            if (textSnapshot != currentText) return@launch
            pages = calculated.ifEmpty { listOf(0) }
            currentPage = findPageForOffset(requestedOffset.coerceIn(0, currentText.length))
            renderCurrentPage(saveOffset = false, chromeOffset = requestedOffset)
        }
    }

    private fun calculatePageStarts(text: String, paint: TextPaint, width: Int, height: Int): List<Int> {
        if (text.isEmpty()) return listOf(0)
        val result = ArrayList<Int>()
        var start = 0

        while (start < text.length) {
            result.add(start)
            val probeEnd = (start + 4_200).coerceAtMost(text.length)
            val segment = text.substring(start, probeEnd)
            val layout = StaticLayout.Builder
                .obtain(segment, 0, segment.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, LINE_SPACING)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            var lastFitLine = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= height) lastFitLine = line else break
            }
            if (lastFitLine < 0) lastFitLine = 0

            var relativeEnd = layout.getLineEnd(lastFitLine)
            if (relativeEnd <= 0) relativeEnd = minOf(segment.length, 1)
            var next = (start + relativeEnd).coerceAtMost(text.length)

            if (next < text.length && next > start + 20) {
                var back = next
                while (back > start + 20 && !text[back - 1].isWhitespace()) back--
                if (back > start + 20) next = back
            }
            start = if (next > start) next else (start + 1).coerceAtMost(text.length)
        }
        return result
    }

    private fun renderCurrentPage(saveOffset: Boolean, chromeOffset: Int? = null) {
        if (currentText.isEmpty() || pages.isEmpty()) return
        currentPage = currentPage.coerceIn(0, pages.lastIndex)
        val start = pageStart(currentPage)
        val end = pageEnd(currentPage)
        val plain = currentText.substring(start, end)
        val spannable = SpannableString(plain)

        if (audioSessionActive && audioEngineReady && highlightStart >= 0 && highlightEnd > highlightStart) {
            val localStart = (highlightStart.coerceAtLeast(start) - start).coerceIn(0, plain.length)
            val localEnd = (highlightEnd.coerceAtMost(end) - start).coerceIn(0, plain.length)
            if (localEnd > localStart) {
                spannable.setSpan(
                    BackgroundColorSpan(Color.rgb(255, 238, 184)),
                    localStart,
                    localEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    localStart,
                    localEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (audioCursor > highlightStart) {
                val spokenEnd = (audioCursor.coerceAtMost(end) - start).coerceIn(localStart, plain.length)
                if (spokenEnd > localStart) {
                    spannable.setSpan(
                        ForegroundColorSpan(Color.rgb(151, 91, 20)),
                        localStart,
                        spokenEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        pageView.text = spannable
        if (saveOffset) store.updateOffset(start)
        updateChrome(chromeOffset ?: if (saveOffset) start else (store.loadBook()?.offset ?: start))
    }

    private fun turnPage(delta: Int, animated: Boolean) {
        if (currentText.isEmpty() || pages.isEmpty() || animatingPage) return
        val target = (currentPage + delta).coerceIn(0, pages.lastIndex)
        if (target == currentPage) return

        val commitTurn = {
            currentPage = target
            clearHighlight()
            val offset = pageStart(currentPage)
            store.updateOffset(offset)
            renderCurrentPage(saveOffset = false, chromeOffset = offset)
            handleManualPositionChange(offset)
        }

        if (!animated) {
            commitTurn()
            return
        }

        animatingPage = true
        val direction = if (delta > 0) -1f else 1f
        pageView.animate()
            .translationX(direction * dp(42))
            .alpha(0.22f)
            .setDuration(90L)
            .withEndAction {
                commitTurn()
                pageView.translationX = -direction * dp(34)
                pageView.alpha = 0.22f
                pageView.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(120L)
                    .withEndAction { animatingPage = false }
                    .start()
            }
            .start()
    }

    private fun handleManualPositionChange(offset: Int) {
        if (!audioSessionActive) return
        if (audioPaused) {
            stopAudioSession()
        } else {
            playFromOffset(offset)
        }
    }

    private fun pageStart(index: Int): Int = pages[index.coerceIn(0, pages.lastIndex)]

    private fun pageEnd(index: Int): Int {
        val safe = index.coerceIn(0, pages.lastIndex)
        return if (safe + 1 < pages.size) pages[safe + 1] else currentText.length
    }

    private fun findPageForOffset(offset: Int): Int {
        if (pages.isEmpty()) return 0
        val target = offset.coerceIn(0, currentText.length)
        var low = 0
        var high = pages.lastIndex
        var answer = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pages[mid] <= target) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer.coerceIn(0, pages.lastIndex)
    }

    private fun updateChrome(offset: Int) {
        if (currentText.isEmpty()) return
        val safe = offset.coerceIn(0, currentText.length)
        val percent = ((safe.toDouble() / currentText.length.toDouble()) * 100.0)
            .toInt().coerceIn(0, 100)
        pageInfo.text = "${currentPage + 1} / ${pages.size}   ·   $percent%"
        if (!changingSeek) {
            seekBar.progress = ((safe.toDouble() / currentText.length.toDouble()) * 10_000.0)
                .toInt().coerceIn(0, 10_000)
        }
    }

    private fun togglePlayback() {
        if (currentText.isEmpty()) {
            Toast.makeText(this, "Сначала откройте книгу", Toast.LENGTH_SHORT).show()
            return
        }
        when {
            audioSessionActive && !audioPaused -> {
                startService(Intent(this, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_PAUSE))
            }
            audioSessionActive && audioPaused -> {
                startService(Intent(this, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_RESUME))
            }
            else -> {
                val offset = store.loadBook()?.offset ?: pageStart(currentPage)
                playFromOffset(offset.coerceIn(0, currentText.length))
            }
        }
    }

    private fun playFromOffset(offset: Int) {
        val book = store.loadBook() ?: return
        playRequestToken += 1
        val token = playRequestToken

        audioSessionActive = true
        audioPaused = false
        audioEngineReady = false
        playButton.text = "…"
        audioInfo.text = "Готовлю офлайн-голос…"

        val intent = Intent(this, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PLAY
            putExtra(TtsPlaybackService.EXTRA_OFFSET, offset.coerceIn(0, currentText.length))
            putExtra(TtsPlaybackService.EXTRA_TEXT_PATH, book.textPath)
            putExtra(TtsPlaybackService.EXTRA_SPEED, book.speed)
            putExtra(TtsPlaybackService.EXTRA_NARRATOR_SID, book.narratorSid)
        }
        ContextCompat.startForegroundService(this, intent)

        mainHandler.postDelayed({
            if (token == playRequestToken && audioSessionActive && !audioEngineReady) {
                stopAudioSession()
                Toast.makeText(
                    this,
                    "Озвучка не запустилась. Читалка продолжает работать — попробуйте ▶ ещё раз.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, TTS_START_TIMEOUT_MS)
    }

    private fun stopAudioSession() {
        playRequestToken += 1
        runCatching {
            startService(Intent(this, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_STOP))
        }
        audioSessionActive = false
        audioPaused = false
        audioEngineReady = false
        clearHighlight()
        playButton.text = "▶"
        audioInfo.text = "Текст · ▶ продолжит с текущего места"
        if (currentText.isNotEmpty()) renderCurrentPage(saveOffset = false)
    }

    private fun cycleSpeed() {
        val current = store.loadBook()?.speed ?: 1.0f
        val values = floatArrayOf(0.75f, 0.9f, 1.0f, 1.1f, 1.2f, 1.35f, 1.5f)
        val next = values.firstOrNull { it > current + 0.01f } ?: values.first()
        store.updateSpeed(next)
        refreshSettingsButtons()
        if (audioSessionActive && !audioPaused) {
            playFromOffset(store.loadBook()?.offset ?: pageStart(currentPage))
        }
    }

    private fun cycleNarratorVoice() {
        val current = store.loadBook()?.narratorSid ?: 0
        store.updateNarratorSid((current + 1) % 10)
        refreshSettingsButtons()
        if (audioSessionActive && !audioPaused) {
            playFromOffset(store.loadBook()?.offset ?: pageStart(currentPage))
        }
    }

    private fun changeFont(delta: Float) {
        val current = store.loadBook()?.fontSize
            ?: (pageView.textSize / resources.displayMetrics.scaledDensity)
        val next = (current + delta).coerceIn(14f, 36f)
        val anchor = store.loadBook()?.offset ?: pageStart(currentPage)
        store.updateFontSize(next)
        pageView.textSize = next
        pageView.post { recalculatePages(anchor.coerceIn(0, currentText.length)) }
    }

    private fun refreshSettingsButtons() {
        val book = store.loadBook()
        val narrator = book?.narratorSid ?: 0
        val speed = book?.speed ?: 1.0f
        if (::voiceButton.isInitialized) voiceButton.text = "Голос ${narrator + 1}"
        if (::speedButton.isInitialized) speedButton.text = formatSpeed(speed)
    }

    private fun clearHighlight() {
        highlightStart = -1
        highlightEnd = -1
        audioCursor = -1
    }

    private fun toggleChromeVisibility() {
        chromeVisible = !chromeVisible
        val targetAlpha = if (chromeVisible) 1f else 0f
        val targetVisibility = if (chromeVisible) View.VISIBLE else View.INVISIBLE
        if (chromeVisible) {
            topChrome.visibility = View.VISIBLE
            bottomChrome.visibility = View.VISIBLE
        }
        topChrome.animate().alpha(targetAlpha).setDuration(140L).withEndAction {
            if (!chromeVisible) topChrome.visibility = targetVisibility
        }.start()
        bottomChrome.animate().alpha(targetAlpha).setDuration(140L).withEndAction {
            if (!chromeVisible) bottomChrome.visibility = targetVisibility
        }.start()
    }

    private fun formatSpeed(speed: Float): String =
        String.format(Locale.US, if (speed == 1.0f) "1.0×" else "%.2g×", speed)

    private fun handleIncoming(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { importBook(it) }
    }

    private fun registerProgressReceiver() {
        val filter = IntentFilter(TtsPlaybackService.ACTION_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(progressReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 147)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val LINE_SPACING = 1.27f
        private const val TTS_START_TIMEOUT_MS = 35_000L
    }
}
