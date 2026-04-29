package com.example.fix60hz

import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class MainHook : IXposedHookLoadPackage {
    companion object {
        private const val DEFAULT_TARGET_HZ = 60f
        private const val DEBUG_MODE = false
        private const val FLOAT_TOLERANCE = 0.01f
        private val SETTINGS_KEYS = setOf("peak_refresh_rate", "min_refresh_rate")
    }

    private val watchdogStarted = AtomicBoolean(false)

    @Volatile private var targetHz: Float = DEFAULT_TARGET_HZ

    @Volatile private var highRefreshThreshold: Float = DEFAULT_TARGET_HZ + FLOAT_TOLERANCE

    // ==================== ENTRY POINT ====================

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return
        if (lpparam.packageName != "android") return

        FixLog.init(DEBUG_MODE)
        FixLog.important("=== Fix60Hz v1.1.0 loaded === pkg=${lpparam.packageName} proc=${lpparam.processName}")

        loadTargetHz()
        FixLog.important("Target Hz: $targetHz")
        FixLog.currentTargetHz = targetHz

        hookSurfaceControlDesiredSpecs(lpparam)
        hookDisplayModeDirector(lpparam)
        hookVotingSystem(lpparam)
        hookVotesStorage(lpparam)
        hookMaxRefreshRate(lpparam)
        hookDisplayProperties(lpparam)
        hookSettingsApis(lpparam)
        setSystemProperties()
        startWatchdog(lpparam)

        FixLog.important("=== Fix60Hz hooks installed ===")
    }

    private fun loadTargetHz() {
        try {
            val prefs = XSharedPreferences("com.example.fix60hz", "fix60hz_settings")
            prefs.reload()
            if (prefs.file.canRead()) {
                targetHz = prefs.getFloat("target_hz", DEFAULT_TARGET_HZ)
                highRefreshThreshold = targetHz + FLOAT_TOLERANCE
                FixLog.important("Loaded target Hz from prefs: $targetHz", false)
            } else {
                targetHz = DEFAULT_TARGET_HZ
                highRefreshThreshold = DEFAULT_TARGET_HZ + FLOAT_TOLERANCE
                FixLog.important("Prefs not readable, using default: $targetHz", false)
            }
        } catch (t: Throwable) {
            targetHz = DEFAULT_TARGET_HZ
            highRefreshThreshold = DEFAULT_TARGET_HZ + FLOAT_TOLERANCE
            FixLog.important("Using default target Hz: $targetHz", false)
        }
    }

    // ==================== LEVEL 1: SurfaceControl ====================

    private fun hookSurfaceControlDesiredSpecs(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("SC.setDesiredDisplayModeSpecs") {
            val clazz = XposedHelpers.findClass("android.view.SurfaceControl", lpparam.classLoader)
            val method =
                clazz.declaredMethods.firstOrNull { it.name == "setDesiredDisplayModeSpecs" }
                    ?: run {
                        FixLog.important("SC.setDesiredDisplayModeSpecs not found", false)
                        return@runHook
                    }

            method.isAccessible = true
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            FixLog.onIntercept("SC")
                            val specs = param.args.getOrNull(1) ?: return
                            val changed = rewriteDesiredSpecs(specs)
                            if (changed) {
                                FixLog.onRewrite()
                                FixLog.rewrite("SC") { "SC REWRITE: $specs" }
                            } else {
                                FixLog.observed("SC.ok") { "SC OK: $specs" }
                            }
                            if (containsHighRefresh(specs)) {
                                FixLog.onLeak()
                                FixLog.leak { "SC LEAK: $specs" }
                                forceRewriteAllFields(specs)
                            }
                        } catch (t: Throwable) {
                            FixLog.onError()
                            FixLog.error("SC hook failed", t)
                        }
                    }
                },
            )
            FixLog.important("Hooked: SC.setDesiredDisplayModeSpecs", false)
        }
    }

    // ==================== LEVEL 2: DisplayModeDirector ====================

    private fun hookDisplayModeDirector(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("DisplayModeDirector") {
            val dmdClass =
                listOf(
                    "com.android.server.display.mode.DisplayModeDirector",
                    "com.android.server.display.DisplayModeDirector",
                ).firstNotNullOfOrNull {
                    XposedHelpers.findClassIfExists(it, lpparam.classLoader)
                } ?: run {
                    FixLog.important("DisplayModeDirector not found", false)
                    return@runHook
                }

            dmdClass.declaredMethods
                .filter { it.name == "getDesiredDisplayModeSpecs" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    FixLog.onIntercept("DMD")
                                    val result = param.result ?: return
                                    val changed = rewriteDesiredSpecs(result)
                                    if (changed) {
                                        FixLog.onRewrite()
                                        FixLog.rewrite("DMD") { "DMD REWRITE: $result" }
                                    } else {
                                        FixLog.observed("DMD.ok") { "DMD OK: $result" }
                                    }
                                    if (containsHighRefresh(result)) {
                                        FixLog.onLeak()
                                        FixLog.leak { "DMD LEAK: $result" }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("DMD hook failed", t)
                                }
                            }
                        },
                    )
                }

            dmdClass.declaredMethods
                .filter { it.name == "getDesiredDisplayModeSpecsWithInjectedFpsSettings" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    FixLog.onIntercept("DMD.injected")
                                    val result = param.result ?: return
                                    val changed = rewriteDesiredSpecs(result)
                                    if (changed) {
                                        FixLog.onRewrite()
                                        FixLog.rewrite("DMD.injected") { "DMD.injected REWRITE: $result" }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("DMD.injected hook failed", t)
                                }
                            }
                        },
                    )
                }

            FixLog.important("Hooked: DisplayModeDirector", false)
        }
    }

    // ==================== LEVEL 3: VoteSummary ====================

    private fun hookVotingSystem(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("VotingSystem") {
            val dmdClass =
                listOf(
                    "com.android.server.display.mode.DisplayModeDirector",
                    "com.android.server.display.DisplayModeDirector",
                ).firstNotNullOfOrNull {
                    XposedHelpers.findClassIfExists(it, lpparam.classLoader)
                } ?: return@runHook

            dmdClass.declaredMethods
                .filter { it.name == "summarizeVotes" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    FixLog.onIntercept("Vote")
                                    val voteSummary = param.args.lastOrNull() ?: return
                                    val changed = rewriteAnyRefreshRateFields(voteSummary, 0)
                                    if (changed) {
                                        FixLog.onRewrite()
                                        FixLog.rewrite("Vote") { "VoteSummary REWRITE" }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("Vote hook failed", t)
                                }
                            }
                        },
                    )
                }

            FixLog.important("Hooked: VoteSummary", false)
        }
    }

    // ==================== LEVEL 4: VotesStorage ====================

    private fun hookVotesStorage(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("VotesStorage") {
            val votesStorageClass =
                listOf(
                    "com.android.server.display.mode.VotesStorage",
                    "com.android.server.display.mode.DisplayModeDirector\$VotesStorage",
                    "com.android.server.display.DisplayModeDirector\$VotesStorage",
                ).firstNotNullOfOrNull {
                    XposedHelpers.findClassIfExists(it, lpparam.classLoader)
                } ?: run {
                    FixLog.important("VotesStorage not found", false)
                    return@runHook
                }

            votesStorageClass.declaredMethods.forEach { method ->
                val name = method.name.lowercase()
                if (name.contains("vote") &&
                    (name.contains("update") || name.contains("add") || name.contains("set"))
                ) {
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    FixLog.onIntercept("VotesStorage")
                                    var changed = false
                                    param.args?.forEach { arg ->
                                        if (arg != null &&
                                            !arg.javaClass.isPrimitive &&
                                            arg.javaClass != String::class.java
                                        ) {
                                            if (rewriteAnyRefreshRateFields(arg, 0)) {
                                                changed = true
                                            }
                                        }
                                    }
                                    if (changed) {
                                        FixLog.onRewrite()
                                        FixLog.rewrite("VotesStorage.${method.name}") {
                                            "VotesStorage.${method.name} vote rewritten"
                                        }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("VotesStorage.${method.name} hook failed", t)
                                }
                            }
                        },
                    )
                }
            }

            FixLog.important("Hooked: VotesStorage", false)
        }
    }

    // ==================== LEVEL 5: MaxRefreshRate ====================

    private fun hookMaxRefreshRate(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("MaxRefreshRate") {
            val dmdClass =
                listOf(
                    "com.android.server.display.mode.DisplayModeDirector",
                    "com.android.server.display.DisplayModeDirector",
                ).firstNotNullOfOrNull {
                    XposedHelpers.findClassIfExists(it, lpparam.classLoader)
                } ?: return@runHook

            dmdClass.declaredMethods
                .filter { it.name == "getMaxRefreshRateLocked" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val result = param.result as? Float ?: return
                                    if (isHighHz(result)) {
                                        FixLog.onIntercept("MaxRR")
                                        FixLog.onRewrite()
                                        param.result = targetHz
                                        FixLog.rewrite("MaxRR") { "MaxRefreshRate: $result -> $targetHz" }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("MaxRR hook failed", t)
                                }
                            }
                        },
                    )
                }

            FixLog.important("Hooked: getMaxRefreshRateLocked", false)
        }
    }

    // ==================== LEVEL 6: DisplayManagerService ====================

    private fun hookDisplayProperties(lpparam: XC_LoadPackage.LoadPackageParam) {
        runHook("DisplayProperties") {
            val dmsClass =
                XposedHelpers.findClassIfExists(
                    "com.android.server.display.DisplayManagerService", lpparam.classLoader,
                ) ?: run {
                    FixLog.important("DisplayManagerService not found", false)
                    return@runHook
                }

            dmsClass.declaredMethods
                .filter { it.name == "setDisplayPropertiesInternal" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    FixLog.onIntercept("DMS")
                                    var changed = false
                                    param.args?.forEachIndexed { index, arg ->
                                        when (arg) {
                                            is Float -> {
                                                if (isHighHz(arg)) {
                                                    param.args[index] = targetHz
                                                    changed = true
                                                    FixLog.onRewrite()
                                                    FixLog.rewrite("DMS.arg") {
                                                        "DMS arg[$index]: $arg -> $targetHz"
                                                    }
                                                }
                                            }
                                            is Double -> {
                                                if (arg > highRefreshThreshold.toDouble()) {
                                                    param.args[index] = targetHz.toDouble()
                                                    changed = true
                                                    FixLog.onRewrite()
                                                    FixLog.rewrite("DMS.arg") {
                                                        "DMS arg[$index]: $arg -> ${targetHz.toDouble()}"
                                                    }
                                                }
                                            }
                                            else -> {
                                                if (arg != null &&
                                                    !arg.javaClass.isPrimitive &&
                                                    arg.javaClass != String::class.java
                                                ) {
                                                    if (rewriteAnyRefreshRateFields(arg, 0)) {
                                                        changed = true
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (changed) {
                                        FixLog.rewrite("DMS.obj") { "DMS object rewritten" }
                                    }
                                } catch (t: Throwable) {
                                    FixLog.onError()
                                    FixLog.error("DMS failed", t)
                                }
                            }
                        },
                    )
                }

            FixLog.important("Hooked: DMS.setDisplayPropertiesInternal", false)
        }
    }

    // ==================== LEVEL 7: Settings APIs ====================

    private fun hookSettingsApis(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classes =
            listOf(
                "android.provider.Settings\$System",
                "android.provider.Settings\$Global",
                "android.provider.Settings\$Secure",
            )

        classes.forEach { className ->
            runHook("Settings:$className") {
                val clazz =
                    XposedHelpers.findClassIfExists(className, lpparam.classLoader)
                        ?: return@runHook

                clazz.declaredMethods
                    .filter {
                        it.name == "getInt" || it.name == "getIntForUser" ||
                            it.name == "getFloat" || it.name == "getFloatForUser" ||
                            it.name == "getString" || it.name == "getStringForUser"
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        val key = extractSettingKey(param.args) ?: return
                                        if (!isRefreshSettingKey(key)) return

                                        when (val result = param.result) {
                                            is Int -> {
                                                if (result > targetHz.toInt()) {
                                                    param.result = targetHz.toInt()
                                                    FixLog.onRewrite()
                                                    FixLog.rewrite("Settings.$key") {
                                                        "Settings $key: $result -> ${targetHz.toInt()}"
                                                    }
                                                }
                                            }
                                            is Float -> {
                                                if (isHighHz(result)) {
                                                    param.result = targetHz
                                                    FixLog.onRewrite()
                                                    FixLog.rewrite("Settings.$key") {
                                                        "Settings $key: $result -> $targetHz"
                                                    }
                                                }
                                            }
                                            is String -> {
                                                val parsed = result.toFloatOrNull()
                                                if (parsed != null && isHighHz(parsed)) {
                                                    param.result = targetHz.toInt().toString()
                                                    FixLog.onRewrite()
                                                    FixLog.rewrite("Settings.$key") {
                                                        "Settings $key: $result -> ${targetHz.toInt()}"
                                                    }
                                                }
                                            }
                                            else -> {}
                                        }
                                    } catch (t: Throwable) {
                                        FixLog.onError()
                                        FixLog.error("Settings hook failed", t)
                                    }
                                }
                            },
                        )
                    }

                FixLog.important("Hooked: $className", false)
            }
        }
    }

    // ==================== LEVEL 8: System Properties ====================

    private fun setSystemProperties() {
        val props =
            mapOf(
                "persist.sys.oplus.display.refreshrate" to targetHz.toInt().toString(),
                "persist.sys.sf.refresh_rate_default" to targetHz.toInt().toString(),
                "persist.sys.oplus.highrefreshrate" to if (targetHz > 60f) "1" else "0",
            )
        props.forEach { (key, value) ->
            try {
                XposedHelpers.callStaticMethod(
                    Class.forName("android.os.SystemProperties"),
                    "set",
                    key,
                    value,
                )
                FixLog.debug("props") { "Set: $key=$value" }
            } catch (t: Throwable) {
                FixLog.debug("props") { "Failed: $key (${t.message})" }
            }
        }
    }

    // ==================== LEVEL 9: Watchdog ====================

    private fun startWatchdog(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!watchdogStarted.compareAndSet(false, true)) return

        val watchdogThread = HandlerThread("Fix60Hz-Watchdog").also { it.start() }
        val handler = Handler(watchdogThread.looper)

        // Сразу пишем heartbeat, чтобы GUI не видел "дыру" после загрузки
        FixLog.updateHeartbeat()

        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    try {
                        forceSetHz(lpparam)
                    } catch (t: Throwable) {
                        FixLog.onError()
                        FixLog.error("Watchdog tick failed", t)
                    } finally {
                        // Heartbeat обновляется всегда, даже если forceSetHz() упал
                        FixLog.updateHeartbeat()
                    }
                    handler.postDelayed(this, 5_000)
                }
            },
            15_000,
        )

        FixLog.important("Watchdog started (5s interval)", false)
    }

    private fun forceSetHz(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val scClass = XposedHelpers.findClass("android.view.SurfaceControl", lpparam.classLoader)
            val getIdsMethod =
                scClass.declaredMethods
                    .firstOrNull { it.name == "getPhysicalDisplayIds" } ?: return
            getIdsMethod.isAccessible = true
            val displayIds = getIdsMethod.invoke(null) as? LongArray ?: return
            if (displayIds.isEmpty()) return

            val getTokenMethod =
                scClass.getDeclaredMethod(
                    "getPhysicalDisplayToken",
                    Long::class.javaPrimitiveType,
                )
            getTokenMethod.isAccessible = true
            val token = getTokenMethod.invoke(null, displayIds[0]) ?: return

            val getSpecsMethod =
                scClass.declaredMethods
                    .firstOrNull { it.name == "getDesiredDisplayModeSpecs" } ?: return
            getSpecsMethod.isAccessible = true
            val specs = getSpecsMethod.invoke(null, token) ?: return

            val changed = rewriteDesiredSpecs(specs)
            if (!changed) return

            val setSpecsMethod =
                scClass.declaredMethods
                    .firstOrNull { it.name == "setDesiredDisplayModeSpecs" } ?: return
            setSpecsMethod.isAccessible = true
            setSpecsMethod.invoke(null, token, specs)

            FixLog.onWatchdogFix()
            FixLog.rewrite("Watchdog") { "Watchdog forced ${targetHz}Hz: $specs" }
        } catch (t: Throwable) {
            FixLog.onError()
            FixLog.error("Watchdog failed", t)
        }
    }

    // ==================== CORE REWRITE ENGINE ====================

    private fun rewriteDesiredSpecs(specs: Any): Boolean {
        return try {
            rewriteAnyRefreshRateFields(specs, 0)
        } catch (t: Throwable) {
            FixLog.error("rewriteDesiredSpecs failed", t)
            false
        }
    }

    private fun rewriteAnyRefreshRateFields(
        obj: Any,
        depth: Int,
        visited: MutableSet<Int> = HashSet(),
    ): Boolean {
        if (depth > 5) return false

        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return false

        var changed = false

        obj.javaClass.declaredFields.forEach { field ->
            field.isAccessible = true
            try {
                when {
                    field.type == Float::class.javaPrimitiveType -> {
                        val name = field.name.lowercase()
                        val value = field.getFloat(obj)
                        when {
                            (name == "min" || name == "max") &&
                                !isTargetHz(value) &&
                                isRefreshRateClass(obj) -> {
                                field.setFloat(obj, targetHz)
                                changed = true
                            }
                            (
                                name.contains("refreshrate") || name.contains("fps") ||
                                    name.contains("basemode")
                            ) && isHighHz(value) -> {
                                field.setFloat(obj, targetHz)
                                changed = true
                            }
                        }
                    }
                    field.type == Double::class.javaPrimitiveType -> {
                        val name = field.name.lowercase()
                        val value = field.getDouble(obj)
                        when {
                            (name == "min" || name == "max") &&
                                !isTargetHz(value.toFloat()) &&
                                isRefreshRateClass(obj) -> {
                                field.setDouble(obj, targetHz.toDouble())
                                changed = true
                            }
                            (
                                name.contains("refreshrate") || name.contains("fps") ||
                                    name.contains("basemode")
                            ) && value > highRefreshThreshold.toDouble() -> {
                                field.setDouble(obj, targetHz.toDouble())
                                changed = true
                            }
                        }
                    }
                    !field.type.isPrimitive &&
                        field.type != String::class.java &&
                        !field.type.isEnum &&
                        !field.type.isArray -> {
                        val subObj = field.get(obj) ?: return@forEach
                        if (rewriteAnyRefreshRateFields(subObj, depth + 1, visited)) {
                            changed = true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return changed
    }

    private fun forceRewriteAllFields(specs: Any) {
        try {
            rewriteAnyRefreshRateFields(specs, 0)
        } catch (t: Throwable) {
            FixLog.error("forceRewriteAllFields failed", t)
        }
    }

    // ==================== HELPERS ====================

    private fun isTargetHz(value: Float): Boolean = abs(value - targetHz) <= FLOAT_TOLERANCE

    private fun isHighHz(value: Float): Boolean = value > highRefreshThreshold

    private fun containsHighRefresh(obj: Any): Boolean {
        return try {
            obj.javaClass.declaredFields.any { field ->
                if (field.type != Float::class.javaPrimitiveType &&
                    field.type != Double::class.javaPrimitiveType
                ) {
                    return@any false
                }

                field.isAccessible = true
                try {
                    when {
                        field.type == Float::class.javaPrimitiveType ->
                            isHighHz(field.getFloat(obj))
                        field.type == Double::class.javaPrimitiveType ->
                            field.getDouble(obj) > highRefreshThreshold.toDouble()
                        else -> false
                    }
                } catch (_: Throwable) {
                    false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isRefreshRateClass(obj: Any): Boolean {
        val name = obj.javaClass.name.lowercase()
        return name.contains("refreshrate") ||
            name.contains("displaymode") ||
            name.contains("votesummary") ||
            name.contains("vote") ||
            name.contains("specs")
    }

    private fun isRefreshSettingKey(key: String): Boolean = SETTINGS_KEYS.contains(key)

    private fun extractSettingKey(args: Array<out Any?>?): String? {
        if (args == null) return null
        return args.firstOrNull { it is String } as? String
    }

    private inline fun runHook(
        tag: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            FixLog.onError()
            FixLog.error("$tag hook setup failed", t)
        }
    }
}
