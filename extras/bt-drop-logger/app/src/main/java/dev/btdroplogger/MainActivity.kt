package dev.btdroplogger

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        startStop = findViewById(R.id.startStop)

        startStop.setOnClickListener {
            if (LoggerService.running) send(LoggerService.ACTION_STOP) else startLogging()
        }
        findViewById<Button>(R.id.mark).setOnClickListener {
            if (LoggerService.running) {
                send(LoggerService.ACTION_MARK)
                Toast.makeText(this, "Marked", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.export).setOnClickListener { export() }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val on = LoggerService.running
            startStop.text = if (on) "Stop" else "Start"
            status.text = if (on) "Logging to ${LoggerService.sessionDir?.name}" else "Not logging"
            val atBottom = !scroll.canScrollVertically(1)
            log.text = LoggerService.recentText()
            if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            main.postDelayed(this, 1000)
        }
    }

    private fun startLogging() {
        val needed = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_START))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            startLogging()
        } else {
            Toast.makeText(this, "Nearby devices permission is needed", Toast.LENGTH_LONG).show()
        }
    }

    private fun send(action: String) =
        startService(Intent(this, LoggerService::class.java).setAction(action))

    /** Zip the newest session into Downloads/BtDropLogger so it can be shared. */
    private fun export() {
        val sessions = getExternalFilesDir(null)?.listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
        val dir = sessions?.maxByOrNull { it.name }
        if (dir == null) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val msg = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "bt-drop-${dir.name}.zip")
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/BtDropLogger")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { os ->
                    ZipOutputStream(os).use { zip ->
                        dir.listFiles()!!.sortedBy(File::getName).forEach { f ->
                            zip.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                "Saved to Download/BtDropLogger/bt-drop-${dir.name}.zip"
            }.getOrElse { "Export failed: ${it.message}" }
            main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }.start()
    }
}
