package com.example.usbcam

import android.Manifest
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

class MainActivity : CameraActivity() {
    companion object {
        // przetrwa odswiezenie aktywnosci (recreate), zeby nie robic petli
        private var recoveries = 0
        private const val STUCK_MS = 6000L
        private const val MAX_RECOVERIES = 3
    }

    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())
    private val asked = HashSet<String>()
    private var stuckSince = 0L

    // rozdzielczosci
    private val camView by lazy { AspectRatioTextureView(this) }
    private val prefs by lazy { getSharedPreferences("usbcam", Context.MODE_PRIVATE) }
    private var sizes: List<Pair<Int, Int>> = emptyList()
    private var sizeIdx = -1
    private var setupDone = false
    private var resBusy = false

    private val client: CameraClient by lazy {
        CameraClient.newBuilder(this)
            .setEnableGLES(true)
            .setRawImage(true)
            .setCameraStrategy(CameraUvcStrategy(this))
            .openDebug(false)
            .build()
    }

    // ---------- uprawnienia ----------

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)) {
            recreate()
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun perm(p: String) = if (granted(p)) "tak" else "NIE"

    private fun requestMissingPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter { !granted(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    // ---------- USB ----------

    private fun askUsbPermission(um: UsbManager, d: UsbDevice) {
        if (!asked.add(d.deviceName)) return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent("com.example.usbcam.USB_PERMISSION").setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        um.requestPermission(d, pi)
    }

    private fun usbKey(): String {
        val um = getSystemService(Context.USB_SERVICE) as UsbManager
        val d = um.deviceList.values.firstOrNull()
        return if (d != null) "res_${d.vendorId}_${d.productId}" else "res_default"
    }

    // ---------- rozdzielczosc i proporcje ----------

    private fun parseSize(s: String?): Pair<Int, Int>? {
        val p = s?.split("x") ?: return null
        if (p.size != 2) return null
        val w = p[0].toIntOrNull() ?: return null
        val h = p[1].toIntOrNull() ?: return null
        return w to h
    }

    private fun applyAspect(w: Int, h: Int) {
        // wysrodkowanie obrazu z zachowaniem proporcji (czarne pasy zamiast rozciagania)
        camView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        camView.setAspectRatio(w, h)
    }

    private fun showSize(s: Pair<Int, Int>) {
        root.findViewById<Button>(R.id.btnRes).text = "${s.first}x${s.second}"
    }

    private fun setupResolution() {
        val raw = try { client.getAllPreviewSizes() } catch (e: Exception) { null }
        val list = raw?.map { it.width to it.height }
            ?.filter { it.first > 0 && it.second > 0 }
            ?.distinct()
            ?.sortedWith(compareBy<Pair<Int, Int>>({ it.first * it.second }, { it.first }))
            ?: emptyList()
        if (list.isEmpty()) return // kamera jeszcze nie podala listy - probujemy ponownie

        val def = 640 to 480
        val saved = parseSize(prefs.getString(usbKey(), null))
        var apply = false
        val chosen: Pair<Int, Int> = when {
            saved != null && list.contains(saved) -> { apply = saved != def; saved }
            list.contains(def) -> def
            else -> {
                apply = true
                list.minByOrNull { Math.abs(it.first * it.second - 640 * 480) } ?: list[0]
            }
        }
        sizes = list
        sizeIdx = list.indexOf(chosen)
        setupDone = true
        if (apply) {
            try { client.updateResolution(chosen.first, chosen.second) } catch (e: Exception) { }
        }
        showSize(chosen)
        root.findViewById<Button>(R.id.btnRes).visibility =
            if (list.size > 1) View.VISIBLE else View.GONE
        handler.postDelayed({ applyAspect(chosen.first, chosen.second) }, 400)
    }

    private fun nextResolution() {
        if (resBusy || sizes.size < 2) return
        resBusy = true
        sizeIdx = (sizeIdx + 1) % sizes.size
        val s = sizes[sizeIdx]
        try { client.updateResolution(s.first, s.second) } catch (e: Exception) { }
        prefs.edit().putString(usbKey(), "${s.first}x${s.second}").apply()
        showSize(s)
        handler.postDelayed({ applyAspect(s.first, s.second) }, 400)
        handler.postDelayed({ resBusy = false }, 1500)
    }

    private fun resetResolution() {
        prefs.edit().remove(usbKey()).apply()
        toast("Przywrocono domyslna rozdzielczosc")
        recreate()
    }

    // ---------- efekt migawki ----------

    private val hideFrozen = Runnable {
        root.findViewById<ImageView>(R.id.frozen).visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    private fun vibrateShutter() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(40)
            }
        } catch (e: Exception) {
            // brak wibracji - pomijamy
        }
    }

    private fun shutterEffect() {
        val frozen = root.findViewById<ImageView>(R.id.frozen)
        val flash = root.findViewById<View>(R.id.flash)
        // zamrozenie: kopia aktualnej klatki podgladu
        try {
            val bmp = camView.bitmap
            if (bmp != null) {
                frozen.setImageBitmap(bmp)
                frozen.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // bez zamrozenia - zostaje sam rozblysk
        }
        // delikatny bialy rozblysk
        flash.animate().cancel()
        flash.alpha = 0.7f
        flash.visibility = View.VISIBLE
        flash.animate().alpha(0f).setDuration(150).withEndAction {
            flash.visibility = View.GONE
        }.start()
        vibrateShutter()
        handler.removeCallbacks(hideFrozen)
        handler.postDelayed(hideFrozen, 450)
    }

    // ---------- petla stanu ----------

    private val statusPoll = object : Runnable {
        override fun run() {
            val status = root.findViewById<TextView>(R.id.status)
            if (client.isCameraOpened() == true) {
                status.visibility = View.GONE
                stuckSince = 0L
                recoveries = 0
                if (!setupDone) setupResolution()
            } else {
                setupDone = false
                sizes = emptyList()
                root.findViewById<Button>(R.id.btnRes).visibility = View.GONE

                val um = getSystemService(Context.USB_SERVICE) as UsbManager
                val devs = um.deviceList.values
                val sb = StringBuilder()
                if (devs.isEmpty()) {
                    asked.clear()
                    stuckSince = 0L
                    recoveries = 0
                    sb.append("Nie wykryto kamery OTG\n(Android nie widzi zadnego urzadzenia USB)\n")
                } else {
                    sb.append("Android widzi urzadzenia USB:\n")
                    var allAllowed = true
                    for (d in devs) {
                        val has = um.hasPermission(d)
                        sb.append("${d.productName ?: d.deviceName}\n")
                        sb.append("VID=${d.vendorId} PID=${d.productId} klasa=${d.deviceClass}\n")
                        sb.append("dostep dla aplikacji: ${if (has) "tak" else "NIE"}\n")
                        if (!has) {
                            allAllowed = false
                            askUsbPermission(um, d)
                        }
                    }
                    if (allAllowed) {
                        val now = System.currentTimeMillis()
                        if (stuckSince == 0L) {
                            stuckSince = now
                        } else if (now - stuckSince > STUCK_MS && recoveries < MAX_RECOVERIES) {
                            recoveries++
                            stuckSince = 0L
                            recreate()
                            return
                        }
                    } else {
                        stuckSince = 0L
                        sb.append("\nJesli pojawi sie okienko - kliknij OK.\n")
                    }
                    sb.append("\nObraz sie nie pojawia? Dotknij ekranu, aby odswiezyc.\n")
                }
                sb.append("\nUprawnienia: aparat=${perm(Manifest.permission.CAMERA)}")
                sb.append(" mikrofon=${perm(Manifest.permission.RECORD_AUDIO)}")
                sb.append("\nAndroid ${Build.VERSION.RELEASE}, ${Build.MODEL}")
                status.text = sb.toString()
                status.textSize = 14f
                status.translationZ = 20f
                status.visibility = View.VISIBLE
            }
            handler.postDelayed(this, 700)
        }
    }

    // ---------- ekran ----------

    override fun getCameraClient(): CameraClient? = client

    override fun getRootView(layoutInflater: LayoutInflater): View {
        root = layoutInflater.inflate(R.layout.activity_main, null)
        return root
    }

    override fun getCameraView(): IAspectRatio = camView

    override fun getCameraViewContainer(): ViewGroup = root.findViewById(R.id.cameraContainer)

    override fun initView() {
        super.initView()
        root.findViewById<Button>(R.id.btnShot).setOnClickListener { takePhoto() }
        root.findViewById<Button>(R.id.btnGallery).setOnClickListener { openGallery() }
        root.findViewById<Button>(R.id.btnRes).apply {
            setOnClickListener { nextResolution() }
            setOnLongClickListener { resetResolution(); true }
        }
        root.findViewById<TextView>(R.id.status).setOnClickListener {
            recoveries = 0
            recreate()
        }
        deleteGhostEntries()
        handler.post { requestMissingPermissions() }
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // usuwa puste wpisy w galerii zakladane przez biblioteke
    private fun deleteGhostEntries() {
        try {
            contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("%usbcam_cache%")
            )
        } catch (e: Exception) {
            // brak uprawnien do usuniecia - pomijamy
        }
    }

    private fun takePhoto() {
        if (client.isCameraOpened() != true) {
            toast("Brak kamery"); return
        }
        shutterEffect()
        val tmp = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        client.captureImage(object : ICaptureCallBack {
            override fun onBegin() {}
            override fun onError(error: String?) = toast("Blad: $error")
            override fun onComplete(path: String?) {
                if (path == null) return
                saveToGallery(File(path))
                toast("Zapisano w galerii")
                deleteGhostEntries()
                handler.postDelayed({ deleteGhostEntries() }, 1500)
            }
        }, tmp.absolutePath)
    }

    private fun saveToGallery(src: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "USBCAM_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UsbCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        src.delete()
    }

    // galeria w osobnym zadaniu - nasza aplikacja zostaje na ekranie kamery
    private fun openGallery() {
        try {
            val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            val i = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
}
