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
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

    private val client: CameraClient by lazy {
        CameraClient.newBuilder(this)
            .setEnableGLES(true)
            .setRawImage(true)
            .setCameraStrategy(CameraUvcStrategy(this))
            .openDebug(false)
            .build()
    }

    private fun perm(p: String) =
        if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) "tak" else "NIE"

    private fun askUsbPermission(um: UsbManager, d: UsbDevice) {
        if (!asked.add(d.deviceName)) return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent("com.example.usbcam.USB_PERMISSION").setPackage(packageName)
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        um.requestPermission(d, pi)
    }

    private val statusPoll = object : Runnable {
        override fun run() {
            val status = root.findViewById<TextView>(R.id.status)
            if (client.isCameraOpened() == true) {
                status.visibility = View.GONE
                stuckSince = 0L
                recoveries = 0
            } else {
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

    override fun getCameraClient(): CameraClient? = client

    override fun getRootView(layoutInflater: LayoutInflater): View {
        root = layoutInflater.inflate(R.layout.activity_main, null)
        return root
    }

    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(this)

    override fun getCameraViewContainer(): ViewGroup = root.findViewById(R.id.cameraContainer)

    override fun initView() {
        super.initView()
        root.findViewById<Button>(R.id.btnShot).setOnClickListener { takePhoto() }
        root.findViewById<Button>(R.id.btnGallery).setOnClickListener { openGallery() }
        root.findViewById<TextView>(R.id.status).setOnClickListener {
            recoveries = 0
            recreate()
        }
        deleteGhostEntries()
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
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

    private fun openGallery() {
        try {
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }
    }

    private fun toast(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }
}
