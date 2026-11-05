package com.liar.game

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    private var server: Relay? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val page = assets.open("index.html").bufferedReader().readText()
        server = Relay(8080, page).also { it.start(0, false) }
        val ip = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it.isSiteLocalAddress && it.address.size == 4 }?.hostAddress ?: "?"
        val w = WebView(this)
        setContentView(w)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.loadUrl("http://127.0.0.1:8080/?host=1&ip=$ip")
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}

class Relay(port: Int, private val page: String) : NanoWSD(port) {
    private val socks = CopyOnWriteArrayList<WS>()

    override fun serveHttp(session: IHTTPSession): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)

    override fun openWebSocket(h: IHTTPSession): WebSocket = WS(h)

    inner class WS(h: IHTTPSession) : WebSocket(h) {
        override fun onOpen() { socks.add(this) }
        override fun onClose(c: WebSocketFrame.CloseCode?, r: String?, i: Boolean) { socks.remove(this) }
        override fun onMessage(m: WebSocketFrame) {
            val t = m.textPayload
            socks.filter { it !== this }.forEach { try { it.send(t) } catch (_: IOException) {} }
        }
        override fun onPong(p: WebSocketFrame?) {}
        override fun onException(e: IOException?) {}
    }
}
