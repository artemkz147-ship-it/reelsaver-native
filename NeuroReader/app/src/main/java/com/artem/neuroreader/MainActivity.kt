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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
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

class MainActivity : AppCompatActivity() {
    private lateinit var store: ReaderStore
    private lateinit var root: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var pageView: TextView
    private lateinit var pageInfo: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var speedButton: Button
    private lateinit var playButton: Button

    private var currentText: String = ""
    private var pages: List<Int> = listOf(0)
    private var currentPage = 0
    private var changingSeek = false
    private var receiverRegistered = false
    private var paginationJob: Job? = null
    private var lastPageWidth = 0
    private var lastPageHeight = 0
    private var audioSessionActive = false
    private var audioPaused = false

    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TtsPlaybackService.ACTION_PROGRESS) return

            val fallback = store.loadBook()?.offset ?: 0
            val offset = intent.getIntExtra(TtsPlaybackService.EXTRA_OFFSET, fallback)
                .coerceIn(0, currentText.length.coerceAtLeast(0))
            audioSessionActive = intent.getBooleanExtra(TtsPlaybackService.EXTRA_IS_PLAYING, false)
            audioPaused = intent.getBooleanExtra(TtsPlaybackService.EXTRA_PAUSED, false)
            playButton.text = if (audioSessionActive && !audioPaused) "Ⅱ" else "▶"

            if (currentText.isNotEmpty()) {
                store.updateOffset(offset)
                val newPage = findPageForOffset(offset)
                if (newPage != currentPage) {
                    currentPage = newPage
                    renderCurrentPage(saveOffset = false)
                }
                updateChrome(offset)
            }

            val error = intent.getStringExtra(TtsPlaybackService.EXTRA_ERROR)
            if (!error.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        store = ReaderStore(this)
        buildUi()
        registerProgressReceiver()
        requestNotificationPermission()
        restoreBook()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        paginationJob?.cancel()
        if (receiverRegistered) runCatching { unregisterReceiver(progressReceiver) }
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(226, 222, 211))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                bars.left + dp(8),
                bars.top + dp(6),
                bars.right + dp(8),
                bars.bottom + dp(8)
            )
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }

        val open = Button(this).apply {
            text = "＋ Книга"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { openBook.launch(arrayOf("*/*")) }
        }

        titleView = TextView(this).apply {
            text = "НейроЧиталка"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(45, 43, 39))
            maxLines = 2
            setPadding(dp(10), 0, dp(4), 0)
        }

        header.addView(
            open,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(header)

        val pageFrame = FrameLayout(this).apply {
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }

        pageView = TextView(this).apply {
            textSize = store.loadBook()?.fontSize ?: 20f
            setTextColor(Color.rgb(39, 37, 33))
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setLineSpacing(0f, 1.24f)
            gravity = Gravity.TOP or Gravity.START
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(Color.rgb(248, 244, 232))
                cornerRadius = dp(14).toFloat()
            }
            elevation = dp(3).toFloat()
            text = "Нажмите «＋ Книга» и выберите книгу.\n\nТекст будет показан постранично, а место чтения сохранится автоматически."
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP && currentText.isNotEmpty()) {
                    when {
                        event.x < view.width * 0.34f -> turnPage(-1)
                        event.x > view.width * 0.66f -> turnPage(1)
                    }
                }
                true
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

        pageInfo = TextView(this).apply {
            text = "—"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(82, 78, 69))
            setPadding(dp(4), dp(2), dp(4), 0)
        }
        root.addView(pageInfo)

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
                        .toInt()
                        .coerceIn(0, currentText.length)
                    currentPage = findPageForOffset(target)
                    renderCurrentPage(saveOffset = true)
                    if (audioSessionActive) playFromOffset(pageStart(currentPage))
                }
            })
        }
        root.addView(
            seekBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(1))
        }

        controls.addView(controlButton("A−") { changeFont(-2f) }, weighted())
        controls.addView(controlButton("‹") { turnPage(-1) }, weighted())
        playButton = controlButton("▶") { togglePlayback() }
        controls.addView(playButton, weighted(1.15f))
        controls.addView(controlButton("›") { turnPage(1) }, weighted())
        controls.addView(controlButton("A+") { changeFont(2f) }, weighted())
        speedButton = controlButton("1.0×") { cycleSpeed() }
        controls.addView(speedButton, weighted(1.05f))
        root.addView(
            controls,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(1), 0, dp(1), 0)
        setOnClickListener { action() }
    }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, dp(50), weight)

    private fun restoreBook() {
        val book = store.loadBook() ?: return
        val text = runCatching { File(book.textPath).readText(Charsets.UTF_8) }.getOrNull() ?: return
        showBook(book, text)
    }

    private fun showBook(book: ReaderStore.SavedBook, text: String) {
        currentText = text
        titleView.text = book.title
        pageView.textSize = book.fontSize
        speedButton.text = formatSpeed(book.speed)
        pageView.post { recalculatePages(book.offset.coerceIn(0, text.length)) }
    }

    private fun importBook(uri: Uri) {
        titleView.text = "Открываю книгу…"
        pageInfo.text = "Подготавливаю текст…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { BookParser(this@MainActivity).parse(uri) }
            }
            result.onSuccess { parsed ->
                val saved = withContext(Dispatchers.IO) { store.saveBook(parsed.title, parsed.text) }
                showBook(saved, parsed.text)
                Toast.makeText(this@MainActivity, "Книга сохранена", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                titleView.text = store.loadBook()?.title ?: "НейроЧиталка"
                pageInfo.text = "—"
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "Не удалось открыть книгу",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun recalculatePages(requestedOffset: Int) {
        if (currentText.isEmpty()) return

        val width = pageView.width - pageView.paddingLeft - pageView.paddingRight
        val height = pageView.height - pageView.paddingTop - pageView.paddingBottom
        if (width <= dp(40) || height <= dp(60)) {
            pageView.post { recalculatePages(requestedOffset) }
            return
        }

        paginationJob?.cancel()
        val textSnapshot = currentText
        val paint = TextPaint(pageView.paint)
        pageInfo.text = "Разбиваю на страницы…"

        paginationJob = lifecycleScope.launch {
            val calculated = withContext(Dispatchers.Default) {
                calculatePageStarts(textSnapshot, paint, width, height)
            }
            if (textSnapshot !== currentText && textSnapshot != currentText) return@launch
            pages = if (calculated.isEmpty()) listOf(0) else calculated
            currentPage = findPageForOffset(requestedOffset.coerceIn(0, currentText.length))
            renderCurrentPage(saveOffset = false)
            updateChrome(requestedOffset.coerceIn(0, currentText.length))
        }
    }

    private fun calculatePageStarts(
        text: String,
        paint: TextPaint,
        width: Int,
        height: Int
    ): List<Int> {
        if (text.isEmpty()) return listOf(0)
        val result = ArrayList<Int>()
        var start = 0

        while (start < text.length) {
            result.add(start)
            val probeEnd = (start + 12_000).coerceAtMost(text.length)
            val segment = text.substring(start, probeEnd)
            val layout = StaticLayout.Builder
                .obtain(segment, 0, segment.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.24f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .build()

            var lastFitLine = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= height) lastFitLine = line else break
            }
            if (lastFitLine < 0) lastFitLine = 0

            var relativeEnd = layout.getLineEnd(lastFitLine)
            if (relativeEnd <= 0) relativeEnd = minOf(segment.length, 1)
            val next = (start + relativeEnd).coerceAtMost(text.length)
            start = if (next > start) next else (start + 1).coerceAtMost(text.length)
        }
        return result
    }

    private fun renderCurrentPage(saveOffset: Boolean) {
        if (currentText.isEmpty() || pages.isEmpty()) return
        currentPage = currentPage.coerceIn(0, pages.lastIndex)
        val start = pageStart(currentPage)
        val end = pageEnd(currentPage)
        pageView.text = currentText.substring(start, end)
        pageView.scrollTo(0, 0)
        if (saveOffset) store.updateOffset(start)
        updateChrome(if (saveOffset) start else (store.loadBook()?.offset ?: start))
    }

    private fun turnPage(delta: Int) {
        if (currentText.isEmpty() || pages.isEmpty()) return
        val next = (currentPage + delta).coerceIn(0, pages.lastIndex)
        if (next == currentPage) return
        currentPage = next
        renderCurrentPage(saveOffset = true)
        if (audioSessionActive) playFromOffset(pageStart(currentPage))
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
        val percent = ((safe.toDouble() / currentText.length.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        pageInfo.text = "${currentPage + 1} / ${pages.size}   •   $percent%"
        if (!changingSeek) {
            seekBar.progress = ((safe.toDouble() / currentText.length.toDouble()) * 10_000.0)
                .toInt()
                .coerceIn(0, 10_000)
        }
    }

    private fun togglePlayback() {
        if (currentText.isEmpty()) {
            Toast.makeText(this, "Сначала откройте книгу", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, TtsPlaybackService::class.java)
            .setAction(TtsPlaybackService.ACTION_TOGGLE)
        startTtsService(intent)
    }

    private fun playFromOffset(offset: Int) {
        val intent = Intent(this, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PLAY
            putExtra(TtsPlaybackService.EXTRA_OFFSET, offset.coerceIn(0, currentText.length))
        }
        startTtsService(intent)
    }

    private fun startTtsService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun cycleSpeed() {
        val current = store.loadBook()?.speed ?: 1.0f
        val values = floatArrayOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f)
        val next = values.firstOrNull { it > current + 0.01f } ?: values.first()
        store.updateSpeed(next)
        speedButton.text = formatSpeed(next)
        if (audioSessionActive) playFromOffset(store.loadBook()?.offset ?: pageStart(currentPage))
    }

    private fun formatSpeed(speed: Float): String = String.format(Locale.US, "%.2g×", speed)

    private fun changeFont(delta: Float) {
        val book = store.loadBook()
        val current = book?.fontSize ?: pageView.textSize / resources.displayMetrics.scaledDensity
        val next = (current + delta).coerceIn(14f, 36f)
        val offset = book?.offset ?: pageStart(currentPage)
        store.updateFontSize(next)
        pageView.textSize = next
        recalculatePages(offset)
    }

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
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 147)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
