package com.artem.neuroreader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var store: ReaderStore
    private lateinit var titleView: TextView
    private lateinit var readerView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var seekBar: SeekBar
    private lateinit var speedButton: Button
    private var currentText: String = ""
    private var changingSeek = false
    private var receiverRegistered = false

    private val openBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBook(uri)
    }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TtsPlaybackService.ACTION_PROGRESS) return
            val offset = intent.getIntExtra(TtsPlaybackService.EXTRA_OFFSET, store.loadBook()?.offset ?: 0)
            val error = intent.getStringExtra(TtsPlaybackService.EXTRA_ERROR)
            updateProgress(offset, scrollToText = true)
            if (!error.isNullOrBlank()) Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (receiverRegistered) runCatching { unregisterReceiver(progressReceiver) }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val open = Button(this).apply {
            text = "＋ Книга"
            isAllCaps = false
            setOnClickListener { openBook.launch(arrayOf("*/*")) }
        }
        titleView = TextView(this).apply {
            text = "НейроЧиталка"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            setPadding(dp(10), 0, dp(4), 0)
        }
        header.addView(open, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        seekBar = SeekBar(this).apply {
            max = 10_000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) { changingSeek = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val offset = if (currentText.isEmpty()) 0 else ((bar?.progress ?: 0) / 10_000f * currentText.length).toInt()
                    store.updateOffset(offset)
                    changingSeek = false
                    updateProgress(offset, scrollToText = true)
                }
            })
        }
        root.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        readerView = TextView(this).apply {
            textSize = store.loadBook()?.fontSize ?: 20f
            setPadding(dp(12), dp(12), dp(12), dp(36))
            setLineSpacing(0f, 1.24f)
            setTextIsSelectable(true)
            text = "Нажмите «＋ Книга» и выберите EPUB, PDF, FB2, DOCX, RTF, TXT или HTML.\n\nКнига останется в памяти приложения, а голос продолжит с сохранённого места."
        }
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(readerView, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOnScrollChangeListener { _, _, scrollY, _, _ ->
                    if (currentText.isEmpty() || changingSeek) return@setOnScrollChangeListener
                    val layout = readerView.layout ?: return@setOnScrollChangeListener
                    val line = layout.getLineForVertical((scrollY + dp(10)).coerceAtLeast(0))
                    val offset = layout.getLineStart(line).coerceIn(0, currentText.length)
                    store.updateOffset(offset)
                    updateSeekOnly(offset)
                }
            }
        }
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        controls.addView(controlButton("A−") { changeFont(-2f) }, weighted())
        controls.addView(controlButton("A+") { changeFont(2f) }, weighted())
        controls.addView(controlButton("−") { seekBy(-1000) }, weighted())
        controls.addView(controlButton("▶ / ⏸") { togglePlayback() }, weighted(1.5f))
        controls.addView(controlButton("+") { seekBy(1000) }, weighted())
        speedButton = controlButton("1.0×") { cycleSpeed() }
        controls.addView(speedButton, weighted())
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun controlButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, dp(52), weight)

    private fun restoreBook() {
        val book = store.loadBook() ?: return
        val text = runCatching { File(book.textPath).readText(Charsets.UTF_8) }.getOrNull() ?: return
        showBook(book, text)
    }

    private fun showBook(book: ReaderStore.SavedBook, text: String) {
        currentText = text
        titleView.text = book.title
        readerView.textSize = book.fontSize
        readerView.text = text
        speedButton.text = String.format(java.util.Locale.US, "%.2g×", book.speed)
        updateProgress(book.offset.coerceIn(0, text.length), scrollToText = true)
    }

    private fun importBook(uri: Uri) {
        titleView.text = "Открываю книгу…"
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
                Toast.makeText(this@MainActivity, error.message ?: "Не удалось открыть книгу", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun togglePlayback() {
        if (currentText.isEmpty()) {
            Toast.makeText(this, "Сначала откройте книгу", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_TOGGLE))
    }

    private fun seekBy(delta: Int) {
        if (currentText.isEmpty()) return
        val old = store.loadBook()?.offset ?: 0
        val newOffset = (old + delta).coerceIn(0, currentText.length)
        store.updateOffset(newOffset)
        updateProgress(newOffset, scrollToText = true)
        startService(Intent(this, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PLAY
            putExtra(TtsPlaybackService.EXTRA_OFFSET, newOffset)
        })
    }

    private fun cycleSpeed() {
        val current = store.loadBook()?.speed ?: 1.0f
        val values = floatArrayOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f)
        val next = values.firstOrNull { it > current + 0.01f } ?: values.first()
        store.updateSpeed(next)
        speedButton.text = String.format(java.util.Locale.US, "%.2g×", next)
        Toast.makeText(this, "Скорость: ${speedButton.text}", Toast.LENGTH_SHORT).show()
    }

    private fun changeFont(delta: Float) {
        val current = store.loadBook()?.fontSize ?: readerView.textSize / resources.displayMetrics.scaledDensity
        val next = (current + delta).coerceIn(14f, 36f)
        store.updateFontSize(next)
        readerView.textSize = next
    }

    private fun updateProgress(offset: Int, scrollToText: Boolean) {
        val safe = offset.coerceIn(0, currentText.length.coerceAtLeast(0))
        updateSeekOnly(safe)
        if (!scrollToText || currentText.isEmpty()) return
        readerView.post {
            val layout = readerView.layout ?: return@post
            val line = layout.getLineForOffset(safe.coerceAtMost(readerView.text.length))
            scrollView.smoothScrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
        }
    }

    private fun updateSeekOnly(offset: Int) {
        if (changingSeek || currentText.isEmpty()) return
        seekBar.progress = ((offset.toDouble() / currentText.length.toDouble()) * 10_000.0).toInt().coerceIn(0, 10_000)
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
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 147)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
