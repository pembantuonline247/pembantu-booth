package com.pembantu.tvbooth.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between the WebView JavaScript and native Android hardware/network APIs.
 * Exposed via @JavascriptInterface under the "AndroidBooth" namespace.
 */
class DeviceBridge(private val context: Context) {

    companion object {
        private const val TAG = "DeviceBridge"
        private const val NAME = "AndroidBooth"

        // Common IP camera ports + service discovery ports
        private val CAMERA_PORTS = intArrayOf(80, 554, 8080, 8899, 9000, 5541, 8554)
        // Common camera RTSP paths
        private val RTSP_PATHS = arrayOf(
            "/live", "/h264", "/h264ES", "/stream1", "/video1",
            "/cam/realmonitor?channel=1&subtype=0",
            "/onvif-media/media.amp?profile=profile_1_h264",
            "/live0", "/media/video1"
        )
    }

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var activeDiscoverers = mutableListOf<NsdManager.DiscoveryListener>()

    // IP camera streaming state
    private var streamingActive = AtomicBoolean(false)
    private var currentStreamUrl = ""

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
    // IP Camera Discovery
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun scanIPCameras(callbackId: String) {
        executor.execute {
            try {
                val localIP = getLocalIP()
                val parts = localIP.split(".")
                if (parts.size != 4) return@execute
                val subnet = "${parts[0]}.${parts[1]}.${parts[2]}"

                Log.d(TAG, "Scanning subnet $subnet.0/24 for IP cameras...")
                notifyJs("onCameraScanStart", """{"callbackId":"$callbackId"}""")

                // Scan hosts 1-254 for common camera ports
                for (host in 1..254) {
                    val ip = "$subnet.$host"
                    for (port in CAMERA_PORTS) {
                        try {
                            val socket = Socket()
                            socket.connect(InetSocketAddress(InetAddress.getByName(ip), port), 300)
                            socket.close()

                            // Port is open - try to identify as a camera
                            tryIdentifyCamera(ip, port, callbackId)
                        } catch (_: Exception) {
                            // Port closed, skip
                        }
                    }
                }

                notifyJs("onCameraScanEnd", """{"callbackId":"$callbackId"}""")
                Log.d(TAG, "IP camera scan complete")
            } catch (e: Exception) {
                Log.e(TAG, "scanIPCameras error", e)
                notifyJs("onCameraScanError", e.message ?: "Unknown")
            }
        }
    }

    private fun tryIdentifyCamera(ip: String, port: Int, callbackId: String) {
        try {
            // Try RTSP first
            for (path in RTSP_PATHS) {
                try {
                    // RTSP DESCRIBE probe
                    val rtspUrl = "rtsp://$ip:$port$path"
                    val probeSocket = Socket()
                    probeSocket.connect(InetSocketAddress(InetAddress.getByName(ip), port), 1000)
                    val cmd = "DESCRIBE $rtspUrl RTSP/1.0\r\nCSeq: 1\r\n\r\n"
                    probeSocket.getOutputStream().write(cmd.toByteArray())
                    probeSocket.soTimeout = 1000
                    val response = probeSocket.getInputStream().bufferedReader().readText()
                    probeSocket.close()

                    if (response.contains("200 OK") || response.contains("401 Unauthorized")) {
                        val needAuth = response.contains("401")
                        notifyJs("onCameraFound", JSONObject().apply {
                            put("ip", ip)
                            put("port", port)
                            put("type", "rtsp")
                            put("url", rtspUrl)
                            put("auth", needAuth)
                            put("name", "Camera @ $ip")
                            put("callbackId", callbackId)
                        }.toString())
                        return
                    }
                } catch (_: Exception) {}
            }

            // Try HTTP MJPEG
            try {
                val url = URL("http://$ip:$port/videostream.cgi")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val contentType = conn.contentType ?: ""
                if (contentType.contains("multipart/x-mixed-replace")) {
                    notifyJs("onCameraFound", JSONObject().apply {
                        put("ip", ip)
                        put("port", port)
                        put("type", "mjpeg")
                        put("url", "http://$ip:$port/videostream.cgi")
                        put("auth", false)
                        put("name", "Camera @ $ip")
                        put("callbackId", callbackId)
                    }.toString())
                    conn.disconnect()
                    return
                }
                conn.disconnect()
            } catch (_: Exception) {}

            // Try HTTP snapshot
            try {
                val snapshotPaths = arrayOf("/snapshot.cgi", "/image.jpg", "/capture", "/tmpfs/auto.jpg")
                for (snapPath in snapshotPaths) {
                    try {
                        val url = URL("http://$ip:$port$snapPath")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 1000
                        conn.readTimeout = 1000
                        if (conn.responseCode == 200) {
                            notifyJs("onCameraFound", JSONObject().apply {
                                put("ip", ip)
                                put("port", port)
                                put("type", "snapshot")
                                put("url", "http://$ip:$port$snapPath")
                                put("auth", false)
                                put("name", "Camera @ $ip")
                                put("callbackId", callbackId)
                            }.toString())
                            conn.disconnect()
                            return
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

        } catch (e: Exception) {
            Log.w(TAG, "Identify camera failed for $ip:$port", e)
        }
    }

    // ──────────────────────────────────────────────
    // IP Camera Streaming (MJPEG + Snapshot polling)
    // ──────────────────────────────────────────────

    @JavascriptInterface
    fun startIPCameraStream(ip: String, port: Int, url: String, type: String, fps: Int, callbackId: String) {
        // Stop any existing stream
        stopIPCameraStream()

        streamingActive.set(true)
        currentStreamUrl = url

        executor.execute {
            try {
                when (type) {
                    "mjpeg" -> streamMjpeg(url, callbackId, fps)
                    "snapshot" -> streamSnapshot(url, callbackId, fps)
                    "rtsp" -> streamSnapshot(url.replace("rtsp://", "http://").replace(":554", ":80").replace("/h264", "/snapshot.cgi"), callbackId, fps)
                    else -> streamSnapshot(url, callbackId, fps)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream error", e)
                notifyJs("onCameraStreamError", """{"error":"${e.message}","callbackId":"$callbackId"}""")
            }
        }
    }

    @JavascriptInterface
    fun stopIPCameraStream() {
        streamingActive.set(false)
        currentStreamUrl = ""
    }

    private fun streamMjpeg(mjpegUrl: String, callbackId: String, fps: Int) {
        try {
            val url = URL(mjpegUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 0 // No timeout for streaming
            val inputStream = conn.inputStream

            val boundaryStart = "--".toByteArray()
            val headerEnd = "\r\n\r\n".toByteArray()
            val buffer = ByteArray(4096)
            var frameCount = 0
            val maxFps = fps.coerceIn(1, 30)
            val frameDelay = 1000 / maxFps

            while (streamingActive.get()) {
                val frameStart = System.currentTimeMillis()

                // Read until we find the JPEG start marker
                val frameBytes = readMjpegFrame(inputStream, buffer)
                if (frameBytes == null) break

                // Send frame to JS
                val b64 = Base64.encodeToString(frameBytes, Base64.NO_WRAP)
                notifyJs("onCameraFrame", JSONObject().apply {
                    put("data", b64)
                    put("frame", frameCount++)
                    put("callbackId", callbackId)
                }.toString())

                // Throttle to requested FPS
                val elapsed = System.currentTimeMillis() - frameStart
                if (elapsed < frameDelay) {
                    Thread.sleep(frameDelay - elapsed)
                }
            }

            inputStream.close()
            conn.disconnect()
        } catch (e: Exception) {
            if (streamingActive.get()) {
                Log.e(TAG, "MJPEG stream error", e)
            }
        }
    }

    private fun readMjpegFrame(inputStream: InputStream, buffer: ByteArray): ByteArray? {
        try {
            // Find JPEG SOI marker (0xFF 0xD8)
            var prev = 0
            var found = false

            while (streamingActive.get()) {
                val b = inputStream.read()
                if (b == -1) return null
                if (prev == 0xFF && b == 0xD8) {
                    found = true
                    break
                }
                prev = b
            }
            if (!found) return null

            // Read the JPEG data until EOI marker (0xFF 0xD9)
            val jpegData = ByteArrayOutputStream()
            jpegData.write(0xFF)
            jpegData.write(0xD8)
            prev = 0

            while (streamingActive.get()) {
                val b = inputStream.read()
                if (b == -1) break
                jpegData.write(b)
                if (prev == 0xFF && b == 0xD9) {
                    return jpegData.toByteArray()
                }
                prev = b
            }

            return jpegData.toByteArray()
        } catch (e: Exception) {
            return null
        }
    }

    private fun streamSnapshot(snapshotUrl: String, callbackId: String, fps: Int) {
        val delay = 1000 / fps.coerceIn(1, 15) // Cap at 15fps for snapshot polling
        var frameCount = 0

        while (streamingActive.get()) {
            try {
                val start = System.currentTimeMillis()
                val url = URL(snapshotUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()

                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                notifyJs("onCameraFrame", JSONObject().apply {
                    put("data", b64)
                    put("frame", frameCount++)
                    put("callbackId", callbackId)
                }.toString())

                val elapsed = System.currentTimeMillis() - start
                if (elapsed < delay) {
                    Thread.sleep(delay - elapsed)
                }
            } catch (e: Exception) {
                if (streamingActive.get()) {
                    Thread.sleep(1000)
                }
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
    // Raw socket / device ping
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
