package com.pembantu.tvbooth

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pembantu.tvbooth.bridge.DeviceBridge

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PembantuBooth"
        private const val BOOTH_URL = "https://booth.pembantu.online/"
        private const val PERMISSION_REQUEST_CODE = 100

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var deviceBridge: DeviceBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable remote debugging via chrome://inspect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        deviceBridge = DeviceBridge(applicationContext)

        if (hasPermissions()) {
            initWebView()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!hasPermissions()) {
                Log.w(TAG, "Some permissions denied — camera/mic/discovery may not work")
            }
            initWebView()
        }
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val myWebView = findViewById<WebView>(R.id.webView)

        with(myWebView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            setNeedInitialFocus(true)
        }

        // TV / D-pad navigation support
        myWebView.isFocusable = true
        myWebView.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            myWebView.setDefaultFocusHighlightEnabled(true)
        }
        myWebView.requestFocusFromTouch()
        myWebView.requestFocus(View.FOCUS_DOWN)

        // --- Auto-grant WebRTC camera/mic ---
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d(TAG, "WebRTC grant: ${request.origin}")
                request.grant(request.resources)
            }
        }

        myWebView.webViewClient = WebViewClient()

        // ─── Inject native bridge into JavaScript ───
        myWebView.addJavascriptInterface(
            BridgeJsInterface(deviceBridge),
            deviceBridge.getJsName()
        )

        // Bridge dispatch: native → JS
        deviceBridge.setDispatchCallback { event, data ->
            myWebView.post {
                myWebView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('$event', { detail: $data }));",
                    null
                )
            }
        }

        Log.d(TAG, "Loading: $BOOTH_URL")
        myWebView.loadUrl(BOOTH_URL)
    }

    override fun onDestroy() {
        deviceBridge.stopDiscovery()
        super.onDestroy()
    }
}

/**
 * Wrapper class for @JavascriptInterface — WebView requires the annotated
 * class to be public and non-inner for strict mode compatibility.
 */
class BridgeJsInterface(private val bridge: DeviceBridge) {

    @JavascriptInterface
    fun getLocalIP(): String = bridge.getLocalIP()

    @JavascriptInterface
    fun getDeviceInfo(): String = bridge.getDeviceInfo()

    @JavascriptInterface
    fun startDiscovery(serviceType: String, callbackId: String) =
        bridge.startDiscovery(serviceType, callbackId)

    @JavascriptInterface
    fun stopDiscovery() = bridge.stopDiscovery()

    @JavascriptInterface
    fun httpRequest(url: String, method: String, body: String, callbackId: String) =
        bridge.httpRequest(url, method, body, callbackId)

    @JavascriptInterface
    fun pingDevice(host: String, port: Int, callbackId: String) =
        bridge.pingDevice(host, port, callbackId)

    @JavascriptInterface
    fun printRaw(host: String, port: Int, data: String, callbackId: String) =
        bridge.printRaw(host, port, data, callbackId)

    @JavascriptInterface
    fun wledSetColor(host: String, port: Int, r: Int, g: Int, b: Int, callbackId: String) =
        bridge.wledSetColor(host, port, r, g, b, callbackId)

    @JavascriptInterface
    fun wledSetBrightness(host: String, port: Int, brightness: Int, callbackId: String) =
        bridge.wledSetBrightness(host, port, brightness, callbackId)

    @JavascriptInterface
    fun scanIPCameras(callbackId: String) =
        bridge.scanIPCameras(callbackId)

    @JavascriptInterface
    fun startIPCameraStream(ip: String, port: Int, url: String, type: String, fps: Int, callbackId: String) =
        bridge.startIPCameraStream(ip, port, url, type, fps, callbackId)

    @JavascriptInterface
    fun stopIPCameraStream() = bridge.stopIPCameraStream()
}
