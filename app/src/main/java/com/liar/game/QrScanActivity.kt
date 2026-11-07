package com.liar.game

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** عقد بسيط: يفتح الماسح ويعيد نص الرمز أو null عند الإلغاء. */
class QrScanContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, QrScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(QrScanActivity.EXTRA_TEXT) else null
}

/**
 * ماسح QR داخلي: CameraX + ML Kit (النموذج مدمج في التطبيق فيعمل بلا إنترنت).
 * تركيز مستمر تلقائي، لمس الشاشة للتركيز، ومصباح، وقراءة من كامل الصورة.
 */
class QrScanActivity : ComponentActivity() {
    companion object { const val EXTRA_TEXT = "qr_text" }

    private lateinit var preview: PreviewView
    private lateinit var torchBtn: TextView
    private var camera: Camera? = null
    private var torchOn = false
    private val done = AtomicBoolean(false)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "إذن الكاميرا مطلوب لمسح الرمز. يمكنك إدخال الرابط يدوياً.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(ScanOverlay(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val prompt = TextView(this).apply {
            text = "وجّه الكاميرا نحو رمز QR الخاص بالمضيف"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(prompt, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(150)
            leftMargin = dp(24); rightMargin = dp(24)
        })

        torchBtn = roundButton("🔦").apply {
            visibility = View.GONE
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                alpha = if (torchOn) 1f else 0.75f
            }
        }
        root.addView(torchBtn, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(60)
        })

        val closeBtn = roundButton("✕").apply { setOnClickListener { finish() } }
        root.addView(closeBtn, FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(40); marginEnd = dp(16)
        })

        // لمس الشاشة للتركيز على نقطة معيّنة
        preview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val point = preview.meteringPointFactory.createPoint(ev.x, ev.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun roundButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        alpha = 0.75f
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()); setStroke(dp(1), 0x66E3BF70) }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(720, 1280))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                camera = cam
                if (cam.cameraInfo.hasFlashUnit()) torchBtn.visibility = View.VISIBLE
            } catch (e: Exception) {
                Toast.makeText(this, "تعذّر تشغيل الكاميرا", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done.get()) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                val text = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (text != null && done.compareAndSet(false, true)) {
                    preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        scanner.close()
    }
}

/** طبقة إطار المسح: تعتيم حول مربع شفاف وزوايا ذهبية وخط مسح متحرك. */
private class ScanOverlay(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3BF70.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCE3BF70.toInt(); strokeWidth = 2 * d }
    private val path = Path()
    private val box = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = minOf(w, h) * 0.74f
        box.set((w - s) / 2, h * 0.40f - s / 2, (w + s) / 2, h * 0.40f + s / 2)
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRoundRect(box, 22 * d, 22 * d, Path.Direction.CW)
        c.drawPath(path, dim)

        val l = 36 * d
        val r = box
        c.drawLine(r.left, r.top + l, r.left, r.top, corner); c.drawLine(r.left, r.top, r.left + l, r.top, corner)
        c.drawLine(r.right - l, r.top, r.right, r.top, corner); c.drawLine(r.right, r.top, r.right, r.top + l, corner)
        c.drawLine(r.left, r.bottom - l, r.left, r.bottom, corner); c.drawLine(r.left, r.bottom, r.left + l, r.bottom, corner)
        c.drawLine(r.right - l, r.bottom, r.right, r.bottom, corner); c.drawLine(r.right, r.bottom, r.right, r.bottom - l, corner)

        val y = r.top + 14 * d + t * (s - 28 * d)
        c.drawLine(r.left + 16 * d, y, r.right - 16 * d, y, line)
    }
}
