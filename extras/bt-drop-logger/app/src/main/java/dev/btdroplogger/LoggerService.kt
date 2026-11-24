package dev.btdroplogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LoggerService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_MARK = "mark"
        private const val CHANNEL = "logger"
        private const val HEARTBEAT_MS = 30_000L

        @Volatile var running = false
        @Volatile var sessionDir: File? = null

        /** Last lines of the events log, for the on-screen view. */
        val recent = ArrayDeque<String>()
        fun recentText(): String = synchronized(recent) { recent.joinToString("\n") }

        /** Lines from the Bluetooth stack / LibrePods worth surfacing in events.txt. */
        private val interesting = Regex(
            "(?i)(disconnect|reason[:= ]|supervision|acl_(up|down)|bta_dm_acl|" +
                "btif_report_connection_state|btif_report_audio_state|A2DP (STARTED|STOPPED)|STR_CLOSE|" +
                "role.?switch|sniff.*(fail|error)|link.?loss|encryption.*(fail|change)|" +
                "AirPodsService|AirPodsParser|MediaController: (Send|Pause|Play)|" +
                "bt_stack.*(crash|abort)|Bluetooth.*(restart|crash|ANR)|FATAL|LeAudio.*(disconn|lost))"
        )
        private val noisy = Regex("(?i)(createSocket<psm>|Constructor \\d|Surface|Choreographer|HWUI)")
    }

    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var events: BufferedWriter? = null
    private var logcatProc: Process? = null
    private var logcatThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start()
            ACTION_STOP -> stop()
            ACTION_MARK -> event("######## USER MARKED A DROP HERE ########", alsoToLogcatFile = true)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Logging", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val mark = PendingIntent.getService(
            this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_MARK), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Logging Bluetooth")
            .setContentText("Tap \"Mark drop\" right after you hear a dropout")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Mark drop", mark).build())
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "session_$stamp").apply { mkdirs() }
        sessionDir = dir
        events = BufferedWriter(FileWriter(File(dir, "events.txt"), true))
        synchronized(recent) { recent.clear() }
        running = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "btdroplogger:log").apply { acquire() }

        event("=== Logging started. Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.DISPLAY}) ===")
        registerReceivers()
        snapshot("start")
        startLogcat(dir)
        main.post(heartbeat)
    }

    private fun stop() {
        if (running) {
            event("=== Logging stopped ===")
            running = false
            main.removeCallbacks(heartbeat)
            runCatching { unregisterReceiver(receiver) }
            logcatProc?.destroy()
            logcatProc = null
            io.execute { runCatching { events?.close() }; events = null }
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stop()
        super.onDestroy()
    }

    // ---------- events ----------

    private fun event(msg: String, alsoToLogcatFile: Boolean = false) {
        val line = "${ts.format(Date())}  $msg"
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > 300) recent.removeFirst()
        }
        io.execute {
            runCatching { events?.apply { write(line); newLine(); flush() } }
            if (alsoToLogcatFile) logcatWriter?.let { w ->
                synchronized(w) { runCatching { w.write("\n$line\n\n"); w.flush() } }
            }
        }
    }

    private fun deviceName(d: BluetoothDevice?): String =
        if (d == null) "?" else runCatching { "${d.name} [${d.address}]" }.getOrDefault(d.address ?: "?")

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> s.toString()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val t = i.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1)
                    event(">>> ACL CONNECTED    ${deviceName(dev)} transport=${transport(t)}")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val t = i.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1)
                    event("<<< ACL DISCONNECTED ${deviceName(dev)} transport=${transport(t)}  " +
                        "(look at the [BT] lines just above for the reason code)")
                    snapshot("disconnect")
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> event(
                    "A2DP ${stateName(i.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))} -> " +
                        "${stateName(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))}  ${deviceName(dev)}"
                )
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> event(
                    "A2DP audio " + when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothA2dp.STATE_PLAYING -> "PLAYING"
                        BluetoothA2dp.STATE_NOT_PLAYING -> "NOT PLAYING"
                        else -> "?"
                    } + "  ${deviceName(dev)}"
                )
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> event(
                    "HFP ${stateName(i.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))} -> " +
                        "${stateName(i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))}  ${deviceName(dev)}"
                )
                BluetoothAdapter.ACTION_STATE_CHANGED -> event(
                    "!!! Bluetooth adapter state -> " + when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> "OFF"
                        BluetoothAdapter.STATE_TURNING_ON -> "TURNING ON"
                        BluetoothAdapter.STATE_ON -> "ON"
                        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING OFF"
                        else -> "?"
                    }
                )
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> event("Audio becoming noisy (output route lost)")
                Intent.ACTION_SCREEN_ON -> event("Screen ON")
                Intent.ACTION_SCREEN_OFF -> event("Screen OFF")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> event(
                    "Battery saver " + if (getSystemService(PowerManager::class.java).isPowerSaveMode) "ON" else "OFF"
                )
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> event(
                    "Doze " + if (getSystemService(PowerManager::class.java).isDeviceIdleMode) "ENTERED" else "EXITED"
                )
            }
        }
    }

    private fun transport(t: Int) = when (t) {
        BluetoothDevice.TRANSPORT_BREDR -> "Classic"
        BluetoothDevice.TRANSPORT_LE -> "LE"
        else -> t.toString()
    }

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        registerReceiver(receiver, f, RECEIVER_EXPORTED)
    }

    // ---------- heartbeat ----------

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            val am = getSystemService(AudioManager::class.java)
            val bm = getSystemService(BluetoothManager::class.java)
            val connected = runCatching {
                bm.adapter.bondedDevices.filter { isConnected(it) }.joinToString { deviceName(it) }
            }.getOrDefault("?")
            val batt = getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            event("heartbeat: music=${if (am.isMusicActive) "playing" else "idle"} " +
                "connected=[${connected.ifEmpty { "none" }}] phoneBattery=$batt%")
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun isConnected(d: BluetoothDevice): Boolean =
        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)

    // ---------- root: logcat + dumpsys ----------

    @Volatile private var logcatWriter: BufferedWriter? = null

    private fun startLogcat(dir: File) {
        val out = BufferedWriter(FileWriter(File(dir, "logcat_full.txt"), true))
        logcatWriter = out
        logcatThread = Thread {
            try {
                // -T 1: start from now, don't dump old history. All buffers, with timestamps.
                val p = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "logcat -b main,system,events,radio,crash -v threadtime -T 1")
                )
                logcatProc = p
                event("Root logcat started")
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        synchronized(out) { out.write(l); out.newLine() }
                        if (interesting.containsMatchIn(l) && !noisy.containsMatchIn(l)) {
                            val why = HciReasons.describe(l)
                            event("[BT] " + l.trim() + (if (why != null) "\n                    ==> $why" else ""))
                        }
                    }
                }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                if (running) event("!!! logcat ended (exit $code). Root denied? Grant root to BT Drop Logger and restart.")
            } catch (e: Exception) {
                event("!!! Could not start root logcat: ${e.message}. Only Android broadcasts will be logged.")
            } finally {
                synchronized(out) { runCatching { out.flush(); out.close() } }
                logcatWriter = null
            }
        }.apply { isDaemon = true; start() }

        // Flush the full log every few seconds so nothing is lost if the app is killed.
        main.post(object : Runnable {
            override fun run() {
                val w = logcatWriter ?: return
                io.execute { synchronized(w) { runCatching { w.flush() } } }
                if (running) main.postDelayed(this, 3000)
            }
        })
    }

    private fun snapshot(label: String) {
        val dir = sessionDir ?: return
        Thread {
            runCatching {
                val name = "dumpsys_${label}_${SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())}.txt"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys bluetooth_manager"))
                File(dir, name).outputStream().use { p.inputStream.copyTo(it) }
                p.waitFor()
                event("saved $name")
            }
        }.start()
    }
}
