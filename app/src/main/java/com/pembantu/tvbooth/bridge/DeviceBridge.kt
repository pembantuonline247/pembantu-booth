package com.pembantu.tvbooth.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Bridge between the WebView JavaScript and native Android hardware/network APIs.
 * Exposed via @JavascriptInterface under the "AndroidBooth" namespace.
 */
class DeviceBridge(private val context: Context) {

    companion object {
        private const val TAG = "DeviceBridge"
        private const val NAME = "AndroidBooth"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryCallback: ((String) -> Unit)? = null
    private var nsdManager: NsdManager? = null
    private var activeDiscoverers = mutableListOf<NsdManager.DiscoveryListener>()

    /** Returns the JS-exposed name. */
    fun getJsName(): String = NAME

    // ──────────────────────────────────────────────
    // Network & device info
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun getLocalIP(): String {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifi.connectionInfo.ipAddress
            "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIP error", e)
            "0.0.0.0"
        }
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        return JSONObject().apply {
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("androidVersion", android.os.Build.VERSION.RELEASE)
            put("localIP", getLocalIP())
        }.toString()
    }

    // ──────────────────────────────────────────────
    // mDNS / NSD device discovery
    // ──────────────────────────────────────────────

    /**
     * Start discovering devices on the WiFi network.
     * @param serviceType mDNS service type, e.g. "_ipp._tcp" (printer), "_http._tcp" (lights)
     * @param callbackId  ID returned to JS callback
     */
    @JavascriptInterface
    fun startDiscovery(serviceType: String, callbackId: String) {
        executor.execute {
            try {
                val nsd = context.applicationContext
                    .getSystemService(Context.NSD_SERVICE) as NsdManager

                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        Log.d(TAG, "Discovery started: $regType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                        // Resolve to get IP + port
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Resolve failed: ${info.serviceName} error=$errorCode")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val device = JSONObject().apply {
                                    put("name", info.serviceName)
                                    put("type", info.serviceType)
                                    put("host", info.host.hostAddress)
                                    put("port", info.port)
                                    put("callbackId", callbackId)
                                }
                                notifyJs("onDeviceFound", device.toString())
                            }
                        })
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                        val device = JSONObject().apply {
                            put("name", serviceInfo.serviceName)
                            put("callbackId", callbackId)
                        }
                        notifyJs("onDeviceLost", device.toString())
                    }

                    override fun onDiscoveryStopped(regType: String) {
                        Log.d(TAG, "Discovery stopped: $regType")
                        notifyJs("onDiscoveryStopped", callbackId)
                    }

                    override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                        Log.e(TAG, "Start discovery failed: $regType error=$errorCode")
                        notifyJs("onDiscoveryError", "Start failed: $errorCode")
                    }

                    override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                        Log.e(TAG, "Stop discovery failed: $regType error=$errorCode")
                    }
                }

                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                activeDiscoverers.add(listener)
                nsdManager = nsd

            } catch (e: Exception) {
                Log.e(TAG, "startDiscovery error", e)
                notifyJs("onDiscoveryError", e.message ?: "Unknown error")
            }
        }
    }

    @JavascriptInterface
    fun stopDiscovery() {
        executor.execute {
            try {
                nsdManager?.let { mgr ->
                    activeDiscoverers.forEach { listener ->
                        try { mgr.stopServiceDiscovery(listener) } catch (_: Exception) {}
                    }
                }
                activeDiscoverers.clear()
            } catch (e: Exception) {
                Log.e(TAG, "stopDiscovery error", e)
            }
        }
    }

    // ──────────────────────────────────────────────
    // HTTP control (smart lights, REST devices)
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun httpRequest(url: String, method: String, body: String, callbackId: String) {
        executor.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method.uppercase()
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doInput = true

                if (body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val writer = OutputStreamWriter(conn.outputStream)
                    writer.write(body)
                    writer.flush()
                    writer.close()
                }

                val code = conn.responseCode
                val response = conn.inputStream.bufferedReader().readText()

                notifyJs("onHttpResponse", JSONObject().apply {
                    put("url", url)
                    put("statusCode", code)
                    put("body", response)
                    put("callbackId", callbackId)
                }.toString())

                conn.disconnect()
            } catch (e: Exception) {
                notifyJs("onHttpError", JSONObject().apply {
                    put("url", url)
                    put("error", e.message ?: "Unknown")
                    put("callbackId", callbackId)
                }.toString())
            }
        }
    }

    // ──────────────────────────────────────────────
    // Raw socket / IP camera helper
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun pingDevice(host: String, port: Int, callbackId: String) {
        executor.execute {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetAddress.getByName(host).let {
                    java.net.InetSocketAddress(it, port)
                }, 2000)
                socket.close()
                val ms = System.currentTimeMillis() - start
                notifyJs("onPingResult", JSONObject().apply {
                    put("host", host)
                    put("port", port)
                    put("reachable", true)
                    put("latencyMs", ms)
                    put("callbackId", callbackId)
                }.toString())
            } catch (e: Exception) {
                notifyJs("onPingResult", JSONObject().apply {
                    put("host", host)
                    put("port", port)
                    put("reachable", false)
                    put("latencyMs", -1)
                    put("callbackId", callbackId)
                }.toString())
            }
        }
    }

    // ──────────────────────────────────────────────
    // Print helper
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun printRaw(host: String, port: Int, data: String, callbackId: String) {
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(InetAddress.getByName(host).let {
                    java.net.InetSocketAddress(it, port)
                }, 3000)
                val out: OutputStream = socket.getOutputStream()
                out.write(data.toByteArray())
                out.flush()
                socket.close()
                notifyJs("onPrintResult", JSONObject().apply {
                    put("host", host)
                    put("port", port)
                    put("success", true)
                    put("callbackId", callbackId)
                }.toString())
            } catch (e: Exception) {
                notifyJs("onPrintResult", JSONObject().apply {
                    put("host", host)
                    put("port", port)
                    put("success", false)
                    put("error", e.message ?: "Unknown")
                    put("callbackId", callbackId)
                }.toString())
            }
        }
    }

    // ──────────────────────────────────────────────
    // WLED / smart light presets
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun wledSetColor(host: String, port: Int, r: Int, g: Int, b: Int, callbackId: String) {
        val body = JSONObject().apply {
            put("on", true)
            put("bri", 255)
            put("seg", JSONArray().put(
                JSONObject().apply {
                    put("col", JSONArray().put(JSONArray().put(r).put(g).put(b)))
                }
            ))
        }.toString()
        httpRequest("http://$host:$port/json/state", "POST", body, callbackId)
    }

    @JavascriptInterface
    fun wledSetBrightness(host: String, port: Int, brightness: Int, callbackId: String) {
        val body = JSONObject().apply {
            put("bri", brightness.coerceIn(0, 255))
        }.toString()
        httpRequest("http://$host:$port/json/state", "POST", body, callbackId)
    }

    // ──────────────────────────────────────────────
    // Internal: notify JS
    // ──────────────────────────────────────────────

    private var pendingCallback: ((String, String) -> Unit)? = null

    /** Register a callback so the WebView can dispatch bridge events. */
    fun setDispatchCallback(cb: (String, String) -> Unit) {
        pendingCallback = cb
    }

    private fun notifyJs(event: String, data: String) {
        mainHandler.post {
            pendingCallback?.invoke(event, data)
        }
    }
}
