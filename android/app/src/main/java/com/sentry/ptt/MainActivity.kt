package com.sentry.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sentry.ptt.bridge.WebAppInterface
import com.sentry.ptt.radio.RadioPacket

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pttService: SentryPttService? = null
    private var isBound = false
    private var isVolumeKeyDown = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SentryPttService.LocalBinder
            pttService = binder.getService()
            isBound = true

            // Attach listeners
            pttService?.onPacketReceivedListener = { packet ->
                runOnUiThread {
                    when (packet.type) {
                        RadioPacket.TYPE_PTT_START -> {
                            val padded = String.format("%03d", packet.channel)
                            webView.evaluateJavascript(
                                "if(window.onNativeState) window.onNativeState('receiving', '${packet.callsign}', '$padded');", null
                            )
                        }
                        RadioPacket.TYPE_PTT_END -> {
                            webView.evaluateJavascript(
                                "if(window.onNativeState) window.onNativeState('standby', '', '');", null
                            )
                        }
                    }
                }
            }

            pttService?.onStatusChangedListener = { status ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onNativeStatus) window.onNativeStatus('$status');", null
                    )
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            pttService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        initWebView()
        startAndBindService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            initWebView()
            startAndBindService()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface { pttService }, "AndroidBridge")
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun startAndBindService() {
        val intent = Intent(this, SentryPttService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // Intercept physical volume down key for hardware PTT!
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && !isVolumeKeyDown) {
            isVolumeKeyDown = true
            pttService?.startTransmitting()
            webView.evaluateJavascript("if(window.onNativePtt) window.onNativePtt(true);", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isVolumeKeyDown) {
            isVolumeKeyDown = false
            pttService?.stopTransmitting()
            webView.evaluateJavascript("if(window.onNativePtt) window.onNativePtt(false);", null)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
