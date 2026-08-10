package com.artem.neuroreader

import android.content.Context
import java.io.File

class ReaderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("reader_state", Context.MODE_PRIVATE)

    data class SavedBook(
        val title: String,
        val textPath: String,
        val offset: Int,
        val speed: Float,
        val fontSize: Float,
        val rolesEnabled: Boolean,
        val narratorSid: Int
    )

    fun saveBook(title: String, text: String): SavedBook {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val textFile = File(dir, "current-book.txt")
        textFile.writeText(text, Charsets.UTF_8)
        prefs.edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_TEXT_PATH, textFile.absolutePath)
            .putInt(KEY_OFFSET, 0)
            .putBoolean(KEY_ROLES, false)
            .apply()
        return loadBook()!!
    }

    fun loadBook(): SavedBook? {
        val path = prefs.getString(KEY_TEXT_PATH, null) ?: return null
        if (!File(path).isFile) return null
        return SavedBook(
            title = prefs.getString(KEY_TITLE, "Книга") ?: "Книга",
            textPath = path,
            offset = prefs.getInt(KEY_OFFSET, 0).coerceAtLeast(0),
            speed = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(0.65f, 1.8f),
            fontSize = prefs.getFloat(KEY_FONT_SIZE, 20f).coerceIn(14f, 36f),
            rolesEnabled = false,
            narratorSid = prefs.getInt(KEY_NARRATOR_SID, 0).coerceIn(0, 9)
        )
    }

    fun loadText(): String? {
        val book = loadBook() ?: return null
        return runCatching { File(book.textPath).readText(Charsets.UTF_8) }.getOrNull()
    }

    fun updateOffset(offset: Int) {
        prefs.edit().putInt(KEY_OFFSET, offset.coerceAtLeast(0)).apply()
    }

    fun updateSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, speed.coerceIn(0.65f, 1.8f)).apply()
    }

    fun updateFontSize(size: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE, size.coerceIn(14f, 36f)).apply()
    }

    fun updateRolesEnabled(enabled: Boolean) {
        // Role reading was intentionally removed in v4. Keep the method so older
        // UI code remains binary/source compatible while always using one narrator.
        prefs.edit().putBoolean(KEY_ROLES, false).apply()
    }

    fun updateNarratorSid(sid: Int) {
        prefs.edit().putInt(KEY_NARRATOR_SID, sid.coerceIn(0, 9)).apply()
    }

    companion object {
        private const val KEY_TITLE = "title"
        private const val KEY_TEXT_PATH = "text_path"
        private const val KEY_OFFSET = "offset"
        private const val KEY_SPEED = "speed"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_ROLES = "roles_enabled"
        private const val KEY_NARRATOR_SID = "narrator_sid"
    }
}
