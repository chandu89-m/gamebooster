package com.example.gamebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.role.RoleManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import rikka.shizuku.Shizuku

fun onOff(b: Boolean) = if (b) "on" else "off"

object Session {
    @Volatile var active = false
    @Volatile var pkg = ""
    @Volatile var rejectCalls = true
    @Volatile var killNotifs = true
    @Volatile var batterySaver = true
}

object Terminal {
    @Volatile private var rootCache: Boolean? = null

    fun ready() = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun isRoot(): Boolean {
        rootCache?.let { return it }
        val ok = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor() == 0 && out.contains("uid=0")
        }.getOrDefault(false)
        rootCache = ok
        return ok
    }

    fun exec(cmd: String): String {
        if (isRoot()) {
            return runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val out = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor(); out
            }.getOrDefault("")
        }
        if (!ready()) return ""
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", java.lang.String[].class, java.lang.String[].class, java.lang.String::class.java)
            method.isAccessible = true
            val p = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(); out
        }.getOrDefault("")
    }
}

object DeviceProfile {
    val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
    val socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "unknown"
    val isMtk by lazy {
        socManufacturer.contains("mediatek", true) ||
        File("/proc/gpufreq").exists() ||
        File("/sys/kernel/ged").exists()
    }
    val isLava by lazy {
        Build.MANUFACTURER.equals("lava", true) || Build.BRAND.equals("lava", true)
    }
}

data class SysSnapshot(
    val fps: Int = 0,
    val cpuUsage: Float = 0f,
    val cpuTempC: Float? = null,
    val gpuTempC: Float? = null,
    val socTempC: Float? = null,
    val batteryTempC: Float? = null,
    val gpuUsage: Float? = null,
    val thermalStatus: Int = -1,
    val cpuGovernor: String? = null
)

class SystemMonitor(
    private val ctx: android.content.Context,
    private val pkg: String
) : Thread() {
    @Volatile var snapshot = SysSnapshot()
    @Volatile var running = true
    private var lastCpuStat: Map<String, LongArray> = emptyMap()
    private var lastGfxFrames = -1
    private var lastGfxTime = 0L

    override fun run() {
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            val cpu = readCpuStat()
            val perCore = computeUsage(lastCpuStat, cpu)
            lastCpuStat = cpu
            val overall = if (perCore.isEmpty()) 0f else perCore.average().toFloat()

            val gfx = readGfxFrames()
            val t1 = SystemClock.elapsedRealtime()
            var fps = snapshot.fps
            if (gfx >= 0 && lastGfxFrames >= 0 && t1 > lastGfxTime) {
                fps = (((gfx - lastGfxFrames) * 1000L) / (t1 - lastGfxTime)).toInt().coerceIn(0, 240)
            }
            if (gfx >= 0) { lastGfxFrames = gfx; lastGfxTime = t1 }

            val temps = readTemps()
            val gpu = readGpuUsage()
            val thermal = runCatching {
                ctx.getSystemService(PowerManager::class.java).currentThermalStatus
            }.getOrDefault(-1)

            snapshot = SysSnapshot(
                fps = fps, cpuUsage = overall,
                cpuTempC = temps.first, gpuTempC = temps.second,
                socTempC = temps.third, batteryTempC = temps.fourth,
                gpuUsage = gpu, thermalStatus = thermal,
                cpuGovernor = readPath("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            )
            val spent = SystemClock.elapsedRealtime() - t0
            try { sleep((1000 - spent).coerceAtLeast(200)) } catch (_: InterruptedException) { break }
        }
    }

    private fun readCpuStat(): Map<String, LongArray> {
        val text = readPath("/proc/stat") ?: return emptyMap()
        return text.lineSequence().filter { it.startsWith("cpu") }.mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val vals = p.drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
            if (vals.size >= 4) p[0] to vals else null
        }.toMap()
    }

    private fun computeUsage(prev: Map<String, LongArray>, cur: Map<String, LongArray>): List<Float> {
        if (prev.isEmpty()) return emptyList()
        return cur.mapNotNull { (n, v) ->
            if (n == "cpu") return@mapNotNull null
            val p = prev[n] ?: return@mapNotNull null
            val curIdle = v.getOrElse(3) { 0L } + v.getOrElse(4) { 0L }
            val curTot = v.sum()
            val preIdle = p.getOrElse(3) { 0L } + p.getOrElse(4) { 0L }
            val preTot = p.sum()
            val dt = curTot - preTot
            val di = curIdle - preIdle
            if (dt <= 0) 0f else ((dt - di).toFloat() / dt).coerceIn(0f, 1f)
        }
    }

    private fun readGpuUsage(): Float? {
        if (DeviceProfile.isMtk) {
            listOf(
                "/sys/kernel/ged/hal/gpu_utilization",
                "/sys/module/ged/parameters/gpu_loading",
                "/sys/kernel/ged/hal/gpu_loading",
                "/sys/class/misc/mali0/device/utilization"
            ).forEach { p ->
                readPath(p)?.let { raw -> parseGed(raw)?.let { return it } }
            }
        }
        return null
    }

    private fun parseGed(raw: String): Float? {
        val tok = raw.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        return when {
            tok.size >= 2 && tok[1] > 0f -> (tok[0] / tok[1]).coerceIn(0f, 1f)
            tok.size == 1 -> {
                val n = tok[0]
                if (n <= 1f) n else if (n <= 100f) n / 100f else (n / 1000f).coerceIn(0f, 1f)
            }
            else -> null
        }
    }

    private fun readTemps(): Quadruple {
        var cpu: Float? = null
        var gpu: Float? = null
        var soc: Float? = null
        val zones = File("/sys/class/thermal")
            .listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
        for (z in zones) {
            val type = readPath("${z.absolutePath}/type")?.lowercase() ?: continue
            val t = parseTemp(readPath("${z.absolutePath}/temp") ?: continue) ?: continue
            when {
                cpu == null && type.matches(Regex("(mtktscpu|cpu|cluster|big|little).*")) -> cpu = t
                gpu == null && type.matches(Regex("(mtktsgpu|gpu).*")) -> gpu = t
                soc == null && type.matches(Regex("(soc|tsens|mtktsap).*")) -> soc = t
            }
        }
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batt = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }?.div(10f)
        return Quadruple(cpu, gpu, soc, batt)
    }

    private fun parseTemp(raw: String): Float? {
        val v = raw.trim().toFloatOrNull() ?: return null
        if (v < 5f || v > 150f) {
            val m = v / 1000f
            return if (m in 5f..150f) m else null
        }
        return v
    }

    private fun readGfxFrames(): Int {
        if (!Terminal.ready() && !Terminal.isRoot()) return -1
        val out = Terminal.exec("dumpsys gfxinfo $pkg | grep 'Total frames rendered'")
        return Regex("(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun readPath(path: String): String? = try {
        File(path).readText().trim()
    } catch (_: Exception) {
        if (Terminal.ready() || Terminal.isRoot()) Terminal.exec("cat $path 2>/dev/null").trim().ifEmpty { null }
        else null
    }
}

data class Quadruple(val first: Float?, val second: Float?, val third: Float?, val fourth: Float?)

enum class PerfMode(val label: String, val gameMode: Int) {
    SAVER("Saver", 1), BALANCED("Balanced", 2), BEAST("Beast", 3)
}

object PerfController {
    fun apply(mode: PerfMode, pkg: String): List<String> {
        val log = mutableListOf<String>()
        if (hasGameService())
            sh("cmd game mode $pkg ${mode.gameMode}", log, "game_mode=${mode.gameMode}")
        else log += "game_mode: unavailable on this Lava build"

        when (mode) {
            PerfMode.SAVER -> {
                sh("settings put global low_power 1", log, "battery_saver=on")
                sh("settings put system min_refresh_rate 60", log, "refresh=60")
                setGovernor("powersave", log)
            }
            PerfMode.BALANCED -> {
                sh("settings put global low_power 0", log, "battery_saver=off")
                sh("settings put system min_refresh_rate 60", log, "refresh_min=60")
                sh("settings put system peak_refresh_rate 120", log, "refresh_max=120")
                setGovernor("schedutil", log)
            }
            PerfMode.BEAST -> {
                sh("settings put global low_power 0", log, "battery_saver=off")
                sh("settings put system min_refresh_rate 120", log, "refresh=120")
                sh("settings put system peak_refresh_rate 120", log, "refresh_max=120")
                sh("am kill-all", log, "bg_cleared")
                setGovernor("performance", log)
                if (Terminal.isRoot()) {
                    pinMaxCpuFreq(log)
                    sh("cmd thermalservice override-status 0", log, "thermal_override")
                    sh("for z in /sys/class/thermal/thermal_zone*; do " +
                       "echo disabled > \$z/mode 2>/dev/null; done", log, "thermal_zones")
                } else log += "root_off: freq pin + thermal bypass skipped"
            }
        }
        return log
    }

    fun hasGameService(): Boolean =
        (Terminal.ready() || Terminal.isRoot()) &&
        Terminal.exec("service check game").contains("found", true)

    private fun setGovernor(gov: String, log: MutableList<String>) =
        sh("for p in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do " +
           "[ -e \"\$p\" ] && echo $gov > \"\$p\" 2>/dev/null; done", log, "cpu_gov=$gov")

    private fun pinMaxCpuFreq(log: MutableList<String>) =
        sh("for d in /sys/devices/system/cpu/cpufreq/policy*; do " +
           "m=\$(cat \$d/cpuinfo_max_freq 2>/dev/null); " +
           "[ -n \"\$m\" ] && echo \$m > \$d/scaling_min_freq 2>/dev/null; " +
           "[ -n \"\$m\" ] && echo \$m > \$d/scaling_max_freq 2>/dev/null; done",
           log, "cpu_pin_max")

    private fun sh(cmd: String, log: MutableList<String>, tag: String) {
        if (!Terminal.ready() && !Terminal.isRoot()) { log += "$tag: skipped"; return }
        val out = Terminal.exec(cmd).trim()
        log += if (out.isBlank()) "$tag: ok" else "$tag: ${out.take(60)}"
    }
}

class BoosterService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var hud: View
    private lateinit var l1: TextView
    private lateinit var l2: TextView
    private lateinit var l3: TextView
    private lateinit var modeLine: TextView
    private lateinit var monitor: SystemMonitor
    private val handler = Handler(Looper.getMainLooper())
    private var currentMode = PerfMode.BALANCED
    private var lastFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var watchdog: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
        Session.active = true
        monitor = SystemMonitor(this, Session.pkg).also { it.start() }
        addHud()
        setMode(PerfMode.BALANCED)
    }

    private fun addHud() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
            setPadding(28, 16, 28, 16)
        }
        val mono = Typeface.MONOSPACE
        l1 = small(mono); l2 = small(mono); l3 = small(mono); modeLine = small(mono)
        root.addView(l1); root.addView(l2); root.addView(l3); root.addView(modeLine)
        root.addView(row(PerfMode.values().map { m -> button(m.label) { setMode(m) } }))
        root.addView(row(listOf(
            button("Reject") { Session.rejectCalls = !Session.rejectCalls; refreshModeLine() },
            button("DND") { toggleDnd() },
            button("Batt") { toggleBatterySaver() },
            button("Boost") { Terminal.exec("am kill-all"); toast("BG cleared") },
            button("Pop") { Terminal.exec("am start --windowingMode 3 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${Session.pkg}") },
            button("Split") { Terminal.exec("am start --windowingMode 4 -p ${Session.pkg}") },
            button("Exit") { stopSelf() }
        )))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 100 }
        wm.addView(root, lp)
        hud = root
        handler.post(ticker)
    }

    private fun small(tf: Typeface) = TextView(this).apply {
        setTextColor(Color.parseColor("#B2FF59")); textSize = 12f; typeface = tf; text = "—"
    }
    private fun row(children: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { addView(it) }
    }
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; minWidth = 0; minimumWidth = 0
        setPadding(16, 4, 16, 4); setOnClickListener { onClick() }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = monitor.snapshot
            l1.text = "FPS ${s.fps.toString().padStart(3)} | CPU ${pct(s.cpuUsage)} | GPU ${s.gpuUsage?.let(::pct) ?: " —"}"
            l2.text = "CPU ${t(s.cpuTempC)} | GPU ${t(s.gpuTempC)} | SOC ${t(s.socTempC)} | BAT ${t(s.batteryTempC)}"
            l3.text = "GOV ${s.cpuGovernor ?: "—"} | THERM ${therm(s.thermalStatus)}"
            refreshModeLine()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refreshModeLine() {
        val p = if (Terminal.isRoot()) "root" else if (Terminal.ready()) "shizuku" else "off"
        modeLine.text = "MODE ${currentMode.label} | reject=${onOff(Session.rejectCalls)} | priv=$p"
    }
    private fun pct(f: Float) = "%3d%%".format((f * 100).toInt())
    private fun t(v: Float?) = v?.let { "%2d°C".format(it.toInt()) } ?: " —°C"
    private fun therm(s: Int) = when (s) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERG"; 6 -> "SHUTDOWN"; else -> "—"
    }

    private fun setMode(mode: PerfMode) {
        currentMode = mode
        val log = PerfController.apply(mode, Session.pkg)
        Log.i("PerfCtrl", log.joinToString(" | "))
        watchdog?.let { handler.removeCallbacks(it) }
        if (mode == PerfMode.BEAST && Terminal.isRoot()) {
            val r = object : Runnable {
                override fun run() {
                    PerfController.apply(PerfMode.BEAST, Session.pkg)
                    handler.postDelayed(this, 30_000)
                }
            }
            watchdog = r; handler.postDelayed(r, 30_000)
        }
        toast("${mode.label} applied"); refreshModeLine()
    }

    private fun toggleDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        }
        nm.setInterruptionFilter(
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    private fun toggleBatterySaver() {
        val on = Terminal.exec("settings get global low_power").trim()
        Terminal.exec("settings put global low_power ${if (on == "1") 0 else 1}")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        monitor.running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { PerfController.apply(PerfMode.BALANCED, Session.pkg) }
        runCatching { wm.removeView(hud) }
        Session.active = false
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            "booster", "Booster", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "booster")
            .setContentTitle("Game Booster active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build()
    }
}

class CallScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val reject = Session.active && Session.rejectCalls
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(reject).setRejectCall(reject)
            .setSilenceCall(reject).setSkipCallLog(false)
            .setSkipNotification(false).build())
    }
}

class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (Session.active && Session.killNotifs && sbn.packageName != Session.pkg) {
            runCatching { cancelNotification(sbn.key) }
        }
    }
}

class MainActivity : AppCompatActivity() {
    private val P_OVERLAY = 1001
    private val P_SCREEN = 1002
    private val P_DND = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 64)
        }
        scroll.addView(root); setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Game Booster"; textSize = 26f; setTextColor(0xFFFFEB3B.toInt())
        })
        root.addView(btn("1. Overlay permission") {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")), P_OVERLAY)
        })
        root.addView(btn("2. Call screening role") {
            val rm = getSystemService(RoleManager::class.java)
            if (!rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING))
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), P_SCREEN)
        })
        root.addView(btn("3. DND access") {
            startActivityForResult(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), P_DND)
        })
        root.addView(btn("4. Request Shizuku") {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(0)
            else Toast.makeText(this, "Install Shizuku first, then start it", Toast.LENGTH_LONG).show()
        })
        root.addView(TextView(this).apply {
            text = "\nPick a game:"; setTextColor(0xFFB2FF59.toInt()); setPadding(0, 32, 0, 16)
        })
        loadGames().forEach { (label, pkg) -> root.addView(btn(label) { launch(pkg) }) }
    }

    private fun loadGames(): List<Pair<String, String>> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { packageManager.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first.lowercase() }

    private fun launch(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay first", Toast.LENGTH_SHORT).show(); return
        }
        Session.pkg = pkg
        startForegroundService(Intent(this, BoosterService::class.java))
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}
