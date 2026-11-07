package com.liar.game

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import android.util.Base64
import fi.iki.elonen.NanoWSD
import java.io.IOException
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private var gameView: WebView? = null
    // start = شاشة البداية، host = المضيف، guest = ضيف داخل التطبيق
    private var mode = "start"

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) joinFromText(text)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == "guest") confirmLeave() else finish()
            }
        })
        val link = intent?.data
        if (link == null || !joinFromUri(link)) showStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = intent.data ?: return
        if (mode == "host") {
            Toast.makeText(this, "أنت المضيف الآن، لا يمكن الانضمام إلى غرفة أخرى", Toast.LENGTH_LONG).show()
            return
        }
        joinFromUri(link)
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showStart() {
        destroyWeb()
        mode = "start"
        val prefs = getSharedPreferences("liar", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#09090B"))
            setPadding(px(24), px(24), px(24), px(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun label(text: String, size: Float, color: String) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(10) }
        }
        fun button(text: String, bg: String) = Button(this).apply {
            this.text = text; textSize = 18f; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor(bg))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56))
                .apply { topMargin = px(12) }
        }
        root.addView(label("🔫 الكذّاب", 36f, "#E6C783"))
        root.addView(label("اختر طريقة الدخول", 16f, "#AAA4A0"))
        val host = button("إنشاء غرفة", "#A52530").apply { setOnClickListener { startHost() } }
        val scan = button("انضمام بمسح رمز QR", "#3A3328").apply { setOnClickListener { startScan() } }
        root.addView(host)
        root.addView(scan)
        root.addView(label("أو أدخل عنوان المضيف", 14f, "#AAA4A0").apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = px(26)
        })
        val input = EditText(this).apply {
            hint = "192.168.1.5"
            setText(prefs.getString("lastHost", "") ?: "")
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#6B6560"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(6) }
        }
        root.addView(input)
        root.addView(button("دخول", "#3A3328").apply { setOnClickListener { joinFromText(input.text.toString()) } })
        val sv = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(sv)
    }

    private fun startScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("وجّه الكاميرا نحو رمز QR الخاص بالمضيف")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
        scanLauncher.launch(options)
    }

    private fun joinFromText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        joinFromUri(Uri.parse(if (t.contains("://")) t else "http://$t"))
    }

    /** يقبل http://IP:PORT (رمز QR أو إدخال يدوي) أو liar://join?ip=..&port=.. (زر الصفحة). */
    private fun joinFromUri(uri: Uri): Boolean {
        val host: String?
        val port: Int
        when (uri.scheme) {
            "liar" -> { host = uri.getQueryParameter("ip"); port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8080 }
            "http" -> { host = uri.host; port = if (uri.port == -1) 8080 else uri.port }
            else -> { host = null; port = 0 }
        }
        if (host == null || port !in 1..65535 || !isLocalIpv4(host)) {
            Toast.makeText(this, "عنوان غير صالح — يجب أن يكون المضيف على شبكة Wi-Fi نفسها", Toast.LENGTH_LONG).show()
            return false
        }
        startGuest(host, port)
        return true
    }

    // لا نحمّل في الـ WebView إلا عناوين الشبكة المحلية، حتى لا يفتح رمز QR خبيث موقعًا خارجيًا.
    private fun isLocalIpv4(h: String): Boolean {
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(h) ?: return false
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return false
        return o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)
    }

    private fun destroyWeb() {
        gameView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        gameView = null
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setMessage("مغادرة الغرفة والعودة إلى الشاشة الرئيسية؟")
            .setPositiveButton("مغادرة") { _, _ -> showStart() }
            .setNegativeButton("البقاء", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startGuest(host: String, port: Int) {
        destroyWeb()
        mode = "guest"
        getSharedPreferences("liar", MODE_PRIVATE).edit().putString("lastHost", "$host:$port").apply()
        val w = WebView(this); setContentView(w)
        gameView = w
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.userAgentString = w.settings.userAgentString + " LiarGameApp"
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.e("LiarGame-WebView", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "liar" && u.host == "home") { view.post { showStart() }; return true }
                val p = if (u.port == -1) 80 else u.port
                return !(u.host == host && p == port)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) view.post {
                    Toast.makeText(this@MainActivity, "تعذّر الاتصال بالمضيف", Toast.LENGTH_LONG).show()
                    showStart()
                }
            }
        }
        w.loadUrl("http://$host:$port/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startHost() {
        destroyWeb()
        mode = "host"
        val ip = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it.address.size == 4 }?.hostAddress ?: "127.0.0.1"
        val page = assets.open("index.html").bufferedReader().readText()
        val w = WebView(this); setContentView(w)
        gameView = w
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.e("LiarGame-WebView", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        w.addJavascriptInterface(QrBridge("http://$ip:8080") { runOnUiThread { stopHost() } }, "AndroidBridge")
        // WebSocket and page serving are kept alive by the foreground service.
        val serviceIntent = Intent(this, GameServerService::class.java).putExtra("page", page)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        val hostUrl = "http://127.0.0.1:8080/?host=1&ip=$ip"
        var retries = 0
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame && retries < 20) {
                    retries++
                    view.postDelayed({ view.loadUrl(hostUrl) }, 400)
                }
            }
        }
        w.loadUrl(hostUrl)
    }

    // يوقف الخادم والإشعار الدائم ويغلق التطبيق ويعود إلى واجهة الهاتف.
    private fun stopHost() {
        stopService(Intent(this, GameServerService::class.java))
        finishAndRemoveTask()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        gameView?.requestLayout()
    }
}

class QrBridge(private val url: String, private val onStop: () -> Unit = {}) {
    private fun qr(data: String): String = try {
        val matrix = MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 420, 420,
            mapOf(EncodeHintType.MARGIN to 1))
        val bitmap = Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888)
        for (x in 0 until 420) for (y in 0 until 420) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { "" }
    @JavascriptInterface fun getQrDataUrl(): String = qr(url)
    @JavascriptInterface fun getApkQrDataUrl(): String = qr("$url/app.apk")
    @JavascriptInterface fun stopGame() { onStop() }
}

class GameServerService : Service() {
    private var relay: Relay? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = "liar_game_server"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "خادم اللعبة", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("الكذّاب").setContentText("غرفة اللعبة متاحة للأصدقاء").setOngoing(true).build()
        startForeground(42, notification)
        val page = intent?.getStringExtra("page") ?: assets.open("index.html").bufferedReader().use { it.readText() }
        if (relay == null) relay = Relay(8080, page, applicationContext).also { try { it.start(0, false) } catch (_: IOException) {} }
        return START_STICKY
    }
    override fun onDestroy() { relay?.stop(); super.onDestroy() }
}

class Relay(port: Int, private val page: String, private val context: Context) : NanoWSD(port) {
    private val socks = CopyOnWriteArrayList<WS>()
    private val allowedMedia = setOf(
        "broken_butterfly.svg", "broken_butterfly_trigger.svg", "weapon_reference.webp",
        "cards/king.webp", "cards/queen.webp", "cards/ace.webp", "cards/joker.webp", "cards/back.webp", "cards/change.webp", "cards/storm.webp", "logo.svg", "logo-base.png", "emblem.webp", "gunshot.mp3"
    )
    override fun serveHttp(session: IHTTPSession): Response {
        val path = session.uri.substringBefore('?').removePrefix("/")
        if (path == "app.apk") {
            // يقدّم التطبيق المثبّت على هاتف المضيف نفسه، حتى يثبّته الضيوف بمسح رمز QR.
            return try {
                val f = java.io.File(context.applicationInfo.sourceDir)
                newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", f.inputStream(), f.length()).also {
                    it.addHeader("Content-Disposition", "attachment; filename=\"LiarGame.apk\"")
                    it.addHeader("Cache-Control", "no-store")
                }
            } catch (_: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
        if (path.startsWith("media/")) {
            val name = path.removePrefix("media/")
            if (name !in allowedMedia)
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            return try {
                val bytes = context.assets.open(name).use { it.readBytes() }
                val type = when { name.endsWith(".webp") -> "image/webp"; name.endsWith(".png") -> "image/png"; name.endsWith(".mp3") -> "audio/mpeg"; else -> "image/svg+xml" }
                newFixedLengthResponse(Response.Status.OK, type, bytes.inputStream(), bytes.size.toLong()).also {
                    it.addHeader("Cache-Control", "public, max-age=31536000, immutable")
                }
            } catch (_: IOException) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page).also {
            it.addHeader("Cache-Control", "no-store")
        }
    }
    override fun openWebSocket(h: IHTTPSession): WebSocket = WS(h)
    inner class WS(h: IHTTPSession) : WebSocket(h) {
        private val host = h.remoteIpAddress in setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        private var playerId: String? = null
        override fun onOpen() { socks.add(this) }
        override fun onClose(c: WebSocketFrame.CloseCode?, r: String?, i: Boolean) { socks.remove(this) }
        override fun onMessage(m: WebSocketFrame) {
            if (m.textPayload.length > 65536) return
            try {
                val data = JSONObject(m.textPayload)
                if (!host) {
                    if (data.has("s") || data.has("rej")) return
                    val action = data.optString("a")
                    if (action !in setOf("sync", "join", "spectate", "promote", "play", "liar", "setGun", "armPull", "cancelPull", "next", "swap", "stormPick", "stormShot")) return
                    if (action in setOf("join", "spectate")) {
                        val id = data.optString("id")
                        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) || id == "H") return
                        if (socks.any { it !== this && it.playerId == id }) return
                        if (playerId != null && playerId != id) return
                        playerId = id
                    }
                    if (action != "sync") {
                        // إعادة اتصال: نتبنّى معرّف اللاعب إن لم يستعمله اتصال آخر، وإلا كانت أزرار اللاعب تُهمَل بصمت.
                        if (playerId == null && action != "join" && action != "spectate") {
                            val claim = data.optString("id").ifEmpty { data.optString("by") }
                            if (claim.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && claim != "H" &&
                                socks.none { it !== this && it.playerId == claim }) playerId = claim
                        }
                        val id = playerId ?: return
                        if (data.has("id") && data.optString("id") != id) return
                        if (data.has("by") && data.optString("by") != id) return
                        data.remove("forced")
                    }
                }
                socks.filter { it !== this && (host || it.host) }.forEach {
                    try { it.send(data.toString()) } catch (_: IOException) {}
                }
            } catch (_: Exception) { /* Ignore malformed packets without ending the room. */ }
        }
        override fun onPong(p: WebSocketFrame?) {}
        override fun onException(e: IOException?) {}
    }
}
