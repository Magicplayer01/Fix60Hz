package com.example.fix60hz

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object FixLog {
    private const val TAG = "Fix60Hz"
    private const val STATS_FILE = "/data/system/fix60hz_stats"

    enum class Mode { DEBUG, RELEASE }

    @Volatile
    var mode: Mode = Mode.RELEASE

    private const val REWRITE_INTERVAL_MS = 1_000L
    private const val OBSERVED_INTERVAL_MS = 5_000L
    private const val SUMMARY_INTERVAL_MS = 60_000L

    private class Bucket {
        val lastLogMs = AtomicLong(0L)
        val suppressed = AtomicLong(0L)
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    // Window stats (сбрасываются каждую минуту)
    private val windowStartMs = AtomicLong(SystemClock.elapsedRealtime())
    private val intercepts = AtomicLong(0L)
    private val rewrites = AtomicLong(0L)
    private val leaks = AtomicLong(0L)
    private val watchdogFixes = AtomicLong(0L)
    private val errors = AtomicLong(0L)
    private val perHookIntercepts = ConcurrentHashMap<String, AtomicLong>()

    // Total stats (накапливаются, пишутся в файл)
    val totalIntercepts = AtomicLong(0L)
    val totalRewrites = AtomicLong(0L)
    val totalLeaks = AtomicLong(0L)

    // Summary ticker
    private val summaryStarted = AtomicBoolean(false)
    private var summaryThread: HandlerThread? = null
    private var summaryHandler: Handler? = null

    // Target Hz для записи в файл
    @Volatile
    var currentTargetHz: Float = 60f

    fun init(debugMode: Boolean = false) {
        mode = if (debugMode) Mode.DEBUG else Mode.RELEASE
        startSummaryTicker()
        writeStatsFile()
    }

    // Stats API
    fun onIntercept(hook: String) {
        intercepts.incrementAndGet()
        totalIntercepts.incrementAndGet()
        perHookIntercepts.computeIfAbsent(hook) { AtomicLong(0L) }.incrementAndGet()
    }

    fun onRewrite() {
        rewrites.incrementAndGet()
        totalRewrites.incrementAndGet()
    }

    fun onLeak() {
        leaks.incrementAndGet()
        totalLeaks.incrementAndGet()
    }

    fun onWatchdogFix() = watchdogFixes.incrementAndGet()

    fun onError() = errors.incrementAndGet()

    // Вызывается из watchdog каждые 5 секунд — обновляет timestamp в файле
    fun updateHeartbeat() {
        writeStatsFile()
    }

    // Logging API
    fun rewrite(
        key: String,
        msg: () -> String,
    ) {
        limited("rw:$key", REWRITE_INTERVAL_MS, Log.INFO, mode == Mode.DEBUG, msg)
    }

    fun observed(
        key: String,
        msg: () -> String,
    ) {
        if (mode != Mode.DEBUG) return
        limited("obs:$key", OBSERVED_INTERVAL_MS, Log.DEBUG, false, msg)
    }

    fun leak(msg: () -> String) {
        always(Log.WARN, true, msg)
    }

    fun error(
        message: String,
        t: Throwable? = null,
    ) {
        val text =
            if (t != null) {
                "$message: ${t.javaClass.simpleName}: ${t.message}"
            } else {
                message
            }
        emit(Log.ERROR, text, true)
        t?.let { XposedBridge.log(it) }
    }

    fun important(
        msg: String,
        toXposed: Boolean = true,
    ) {
        emit(Log.INFO, msg, toXposed)
    }

    fun debug(
        key: String,
        msg: () -> String,
    ) {
        if (mode != Mode.DEBUG) return
        limited("dbg:$key", OBSERVED_INTERVAL_MS, Log.DEBUG, false, msg)
    }

    // Internal
    private fun limited(
        key: String,
        minIntervalMs: Long,
        level: Int,
        toXposed: Boolean,
        msg: () -> String,
    ) {
        val bucket = buckets.computeIfAbsent(key) { Bucket() }
        val now = SystemClock.elapsedRealtime()

        while (true) {
            val prev = bucket.lastLogMs.get()
            if (now - prev < minIntervalMs) {
                bucket.suppressed.incrementAndGet()
                return
            }
            if (bucket.lastLogMs.compareAndSet(prev, now)) {
                val suppressed = bucket.suppressed.getAndSet(0L)
                val text = if (suppressed > 0L) "${msg()} [+$suppressed]" else msg()
                emit(level, text, toXposed)
                return
            }
        }
    }

    private fun always(
        level: Int,
        toXposed: Boolean,
        msg: () -> String,
    ) {
        emit(level, msg(), toXposed)
    }

    private fun emit(
        level: Int,
        text: String,
        toXposed: Boolean,
    ) {
        when (level) {
            Log.DEBUG -> Log.d(TAG, text)
            Log.INFO -> Log.i(TAG, text)
            Log.WARN -> Log.w(TAG, text)
            Log.ERROR -> Log.e(TAG, text)
            else -> Log.println(level, TAG, text)
        }
        if (toXposed) {
            XposedBridge.log("$TAG: $text")
        }
    }

    private fun startSummaryTicker() {
        if (!summaryStarted.compareAndSet(false, true)) return

        summaryThread = HandlerThread("Fix60Hz-Log").apply { start() }
        summaryHandler = Handler(summaryThread!!.looper)

        summaryHandler?.postDelayed(
            object : Runnable {
                override fun run() {
                    try {
                        printSummary()
                        writeStatsFile()
                    } catch (t: Throwable) {
                        error("summary failed", t)
                    } finally {
                        summaryHandler?.postDelayed(this, SUMMARY_INTERVAL_MS)
                    }
                }
            },
            SUMMARY_INTERVAL_MS,
        )
    }

    private fun printSummary() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - windowStartMs.getAndSet(now)
        val elapsedSec = elapsedMs.coerceAtLeast(1L) / 1000.0

        val totalI = intercepts.getAndSet(0L)
        val totalR = rewrites.getAndSet(0L)
        val totalL = leaks.getAndSet(0L)
        val totalW = watchdogFixes.getAndSet(0L)
        val totalE = errors.getAndSet(0L)

        val topHooks =
            perHookIntercepts.entries
                .map { it.key to it.value.getAndSet(0L) }
                .filter { it.second > 0L }
                .sortedByDescending { it.second }
                .take(5)

        val topText =
            if (topHooks.isEmpty()) {
                ""
            } else {
                topHooks.joinToString(", ", " hooks=[", "]") { "${it.first}=${it.second}" }
            }

        val msg =
            String.format(
                Locale.US,
                "STATS %.0fs: intercepts=%d rewrites=%d leaks=%d watchdog=%d errors=%d (%.1f/s)%s",
                elapsedSec, totalI, totalR, totalL, totalW, totalE,
                totalI / elapsedSec, topText,
            )

        emit(Log.INFO, msg, mode == Mode.DEBUG)
    }

    /**
     * Формат: active|targetHz|intercepts|rewrites|leaks|timestamp
     * Пример: 1|60|1234|56|0|1714412345678
     *
     * timestamp — System.currentTimeMillis()
     * GUI проверяет: если (now - timestamp) > 15000ms — модуль неактивен
     */
    private fun writeStatsFile() {
        try {
            val now = System.currentTimeMillis()
            val data = "1|${currentTargetHz.toInt()}|${totalIntercepts.get()}|${totalRewrites.get()}|${totalLeaks.get()}|$now"
            val file = File(STATS_FILE)
            file.writeText(data)
            file.setReadable(true, false) // чтение без root если возможно
        } catch (_: Throwable) {
            // не критично
        }
    }
}
