package com.example.usbcam

import android.content.ContentValues
import android.content.Intent
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
import com.jiangdg.ausbc.CameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUvcStrategy
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

class MainActivity : CameraActivity() {
    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())

    private val client: CameraClient by lazy {
        CameraClient.newBuilder(this)
            .setEnableGLES(true)
            .setRawImage(true)
            .setCameraStrategy(CameraUvcStrategy(this))
            .openDebug(false)
            .build()
    }

    private val statusPoll = object : Runnable {
        override fun run() {
            val status = root.findViewById<TextView>(R.id.status)
            status.visibility = if (client.isCameraOpened() == true) View.GONE else View.VISIBLE
            handler.postDelayed(this, 500)
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
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusPoll)
        super.onDestroy()
    }

    private fun takePhoto() {
        if (client.isCameraOpened() != true) {
            toast("Brak kamery"); return
        }
        val tmp = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        client.captureImage(object : ICaptureCallBack {
            override fun onBegin() {}
            override fun onError(error: String?) = toast("Błąd: $error")
            override fun onComplete(path: String?) {
                if (path == null) return
                saveToGallery(File(path))
                toast("Zapisano w galerii")
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
