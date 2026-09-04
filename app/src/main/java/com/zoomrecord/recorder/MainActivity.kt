package com.zoomrecord.recorder

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    companion object { private const val CAPTURE_REQUEST = 2001; private const val AUDIO_REQUEST = 2002 }

    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != RecordingService.ACTION_STATUS) return
            val s = intent.getStringExtra(RecordingService.EXTRA_STATUS) ?: ""
            status.text = s
            start.isEnabled = !RecordingService.running
            stop.isEnabled = RecordingService.running
            val uri = intent.getStringExtra(RecordingService.EXTRA_URI)
            if (s == "Completed" && uri != null) {
                Toast.makeText(this@MainActivity, "Saved recording", Toast.LENGTH_LONG).show()
            }
            intent.getStringExtra(RecordingService.EXTRA_ERROR)?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(RecordingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (RecordingService.running) {
            updateUiState("Recording in progress")
        } else if (status.text.isEmpty()) {
            updateUiState("Ready")
        }
    }

    private fun updateUiState(text: String) {
        status.text = text
        start.isEnabled = !RecordingService.running
        stop.isEnabled = RecordingService.running
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Internal Screen Recorder"
            textSize = 25f
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "Records screen video and eligible internal playback audio.\nMicrophone is never used.\nThe source app must allow Android playback capture."
            textSize = 16f
            setPadding(0, 32, 0, 32)
        }
        status = TextView(this).apply { text = "Ready"; textSize = 18f }
        start = Button(this).apply { text = "Start recording"; setOnClickListener { begin() } }
        stop = Button(this).apply { text = "Stop recording"; isEnabled = false; setOnClickListener { stopRecording() } }
        root.addView(title)
        root.addView(info)
        root.addView(status)
        root.addView(start, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 })
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun begin() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST)
            status.text = "Allow audio permission, then tap Start again"
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission denied"
            return
        }
        val serviceIntent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        status.text = "Starting..."
        start.isEnabled = false
        stop.isEnabled = true
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
