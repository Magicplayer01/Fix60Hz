package com.example.fix60hz

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "fix60hz_settings"
        private const val KEY_TARGET_HZ = "target_hz"
        private const val DEFAULT_TARGET_HZ = 60f
        private const val UPDATE_INTERVAL_MS = 3_000L
        private const val STATS_FILE = "/data/system/fix60hz_stats"

        // Делаем таймаут мягче, чтобы не было ложных "не активен"
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
    }

    private data class ModuleStats(
        val active: Boolean,
        val targetHz: Int,
        val intercepts: Long,
        val rewrites: Long,
        val leaks: Long,
        val timestampMs: Long
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var tvModuleStatus: TextView
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvIntercepts: TextView
    private lateinit var tvLeaks: TextView
    private lateinit var tvRewrites: TextView
    private lateinit var tvStatsHint: TextView
    private lateinit var btn60: Button
    private lateinit var btn90: Button

    private val handler = Handler(Looper.getMainLooper())
    private var lastGoodStats: ModuleStats? = null
    private var lastReadError: String? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        @Suppress("WorldReadableFiles")
        prefs = try {
            getSharedPreferences(PREFS_NAME, MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        }

        bindViews()
        setupButtons()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun bindViews() {
        tvModuleStatus = findViewById(R.id.tvModuleStatus)
        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvIntercepts = findViewById(R.id.tvIntercepts)
        tvLeaks = findViewById(R.id.tvLeaks)
        tvRewrites = findViewById(R.id.tvRewrites)
        tvStatsHint = findViewById(R.id.tvStatsHint)
        btn60 = findViewById(R.id.btn60)
        btn90 = findViewById(R.id.btn90)
    }

    private fun setupButtons() {
        btn60.setOnClickListener { setTargetHz(60f) }
        btn90.setOnClickListener { setTargetHz(90f) }
    }

    private fun setTargetHz(hz: Float) {
        prefs.edit()
            .putFloat(KEY_TARGET_HZ, hz)
            .apply()

        updateUI()

        AlertDialog.Builder(this)
            .setTitle("Режим ${hz.toInt()} Гц установлен")
            .setMessage("Для применения изменений необходима перезагрузка.\n\nПерезагрузить сейчас?")
            .setPositiveButton("Перезагрузить") { _, _ ->
                rebootDevice()
            }
            .setNegativeButton("Позже") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Не забудьте перезагрузить телефон", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun rebootDevice() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
        } catch (_: Exception) {
            try {
                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                pm.reboot(null)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Не удалось перезагрузить. Перезагрузите вручную.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateUI() {
        val targetHz = prefs.getFloat(KEY_TARGET_HZ, DEFAULT_TARGET_HZ)
        tvCurrentMode.text = "Цель: ${targetHz.toInt()} Гц"

        if (targetHz == 60f) {
            btn60.isEnabled = false
            btn60.alpha = 1.0f
            btn90.isEnabled = true
            btn90.alpha = 0.6f
        } else {
            btn60.isEnabled = true
            btn60.alpha = 0.6f
            btn90.isEnabled = false
            btn90.alpha = 1.0f
        }
    }

    private fun updateStats() {
        val content = readStatsViaSu() ?: readStatsDirect()

        if (!content.isNullOrBlank()) {
            val stats = parseStats(content)
            if (stats != null) {
                lastGoodStats = stats
                renderStats(stats, fromCache = false)
                return
            } else {
                lastReadError = "Некорректный формат stats"
            }
        }

        val cached = lastGoodStats
        if (cached != null) {
            renderStats(cached, fromCache = true)
        } else {
            showInactiveStats(lastReadError ?: "Статистика недоступна")
        }
    }

    private fun readStatsViaSu(): String? {
        return try {
            lastReadError = null

            val process = ProcessBuilder("su", "-c", "cat $STATS_FILE")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                lastReadError = if (output.isNotBlank()) {
                    "su error: ${output.take(120)}"
                } else {
                    "Нет root-доступа к stats-файлу"
                }
                return null
            }

            val line = output
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.count { ch -> ch == '|' } >= 4 }

            if (line.isNullOrBlank()) {
                lastReadError = "Пустой stats-файл"
                null
            } else {
                line
            }
        } catch (t: Throwable) {
            lastReadError = "su failed: ${t.javaClass.simpleName}"
            null
        }
    }

    private fun readStatsDirect(): String? {
        return try {
            val file = File(STATS_FILE)
            if (!file.exists()) {
                lastReadError = "Файл stats не найден"
                return null
            }
            if (!file.canRead()) {
                lastReadError = "Файл stats недоступен без root"
                return null
            }

            val content = file.readText().trim()
            if (content.isBlank()) {
                lastReadError = "Файл stats пустой"
                null
            } else {
                content
            }
        } catch (t: Throwable) {
            lastReadError = "Direct read failed: ${t.javaClass.simpleName}"
            null
        }
    }

    private fun parseStats(content: String): ModuleStats? {
        return try {
            val parts = content.split('|')
            if (parts.size < 5) return null

            val active = parts[0] == "1"
            val targetHz = parts[1].toIntOrNull() ?: 60
            val intercepts = parts[2].toLongOrNull() ?: 0L
            val rewrites = parts[3].toLongOrNull() ?: 0L
            val leaks = parts[4].toLongOrNull() ?: 0L
            val timestampMs = if (parts.size >= 6) parts[5].toLongOrNull() ?: 0L else 0L

            ModuleStats(
                active = active,
                targetHz = targetHz,
                intercepts = intercepts,
                rewrites = rewrites,
                leaks = leaks,
                timestampMs = timestampMs
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderStats(stats: ModuleStats, fromCache: Boolean) {
        val now = System.currentTimeMillis()
        val hasHeartbeat = stats.timestampMs > 0L
        val ageMs = if (hasHeartbeat) now - stats.timestampMs else Long.MAX_VALUE
        val heartbeatFresh = !hasHeartbeat || (ageMs in 0..HEARTBEAT_TIMEOUT_MS)

        tvIntercepts.text = formatNumber(stats.intercepts)
        tvRewrites.text = formatNumber(stats.rewrites)
        tvLeaks.text = formatNumber(stats.leaks)

        tvLeaks.setTextColor(
            if (stats.leaks == 0L) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        )

        when {
            !stats.active -> {
                tvModuleStatus.text = "⚠️ Модуль не активен"
                tvModuleStatus.setTextColor(0xFFE65100.toInt())
                tvStatsHint.text = "Флаг active=0"
            }

            heartbeatFresh -> {
                tvModuleStatus.text = "✅ Модуль активен (${stats.targetHz} Гц)"
                tvModuleStatus.setTextColor(0xFF2E7D32.toInt())

                tvStatsHint.text = when {
                    !hasHeartbeat -> "Статистика получена"
                    fromCache -> "Показаны кэшированные данные"
                    else -> "Heartbeat: ${ageMs / 1000} сек назад"
                }
            }

            else -> {
                tvModuleStatus.text = "⚠️ Нет свежего heartbeat"
                tvModuleStatus.setTextColor(0xFFE65100.toInt())
                tvStatsHint.text = buildString {
                    append("Последнее обновление: ${ageMs / 1000} сек назад")
                    lastReadError?.let {
                        append(" · ")
                        append(it)
                    }
                }
            }
        }
    }

    private fun showInactiveStats(reason: String) {
        tvModuleStatus.text = "⚠️ Модуль не активен / stats недоступен"
        tvModuleStatus.setTextColor(0xFFE65100.toInt())
        tvIntercepts.text = "—"
        tvLeaks.text = "—"
        tvRewrites.text = "—"
        tvStatsHint.text = reason
    }

    private fun formatNumber(n: Long): String {
        return when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
            else -> n.toString()
        }
    }
}