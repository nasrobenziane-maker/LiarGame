package com.liar.game

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Color
import android.os.*
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import android.util.Base64
import fi.iki.elonen.NanoWSD
import java.io.IOException

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ip = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it.address.size == 4 }?.hostAddress ?: "127.0.0.1"
        val page = assets.open("index.html").bufferedReader().readText()
        val w = WebView(this); setContentView(w)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.e("LiarGame-WebView", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        w.addJavascriptInterface(QrBridge("http://$ip:8080"), "AndroidBridge")
        // WebSocket and page serving are kept alive by the foreground service.
        val serviceIntent = Intent(this, GameServerService::class.java).putExtra("page", page)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        val hostUrl = "http://127.0.0.1:8080/?host=1&ip=$ip"
        var retries = 0
        w.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame && retries < 20) {
                    retries++
                    view.postDelayed({ view.loadUrl(hostUrl) }, 400)
                }
            }
        }
        w.loadUrl(hostUrl)
    }
}

class QrBridge(private val url: String) {
    @JavascriptInterface fun getQrDataUrl(): String = try {
        val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 420, 420,
            mapOf(EncodeHintType.MARGIN to 1))
        val bitmap = Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888)
        for (x in 0 until 420) for (y in 0 until 420) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { "" }
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
        if (relay == null && page != null) relay = Relay(8080, page).also { try { it.start(0, false) } catch (_: IOException) {} }
        return START_STICKY
    }
    override fun onDestroy() { relay?.stop(); super.onDestroy() }
}

class Relay(port: Int, private val page: String) : NanoWSD(port) {
    private val socks = CopyOnWriteArrayList<WS>()
    override fun serveHttp(session: IHTTPSession): Response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
    override fun openWebSocket(h: IHTTPSession): WebSocket = WS(h)
    inner class WS(h: IHTTPSession) : WebSocket(h) {
        override fun onOpen() { socks.add(this) }
        override fun onClose(c: WebSocketFrame.CloseCode?, r: String?, i: Boolean) { socks.remove(this) }
        override fun onMessage(m: WebSocketFrame) { socks.filter { it !== this }.forEach { try { it.send(m.textPayload) } catch (_: IOException) {} } }
        override fun onPong(p: WebSocketFrame?) {}
        override fun onException(e: IOException?) {}
    }
}
