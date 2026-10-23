package com.example.usbcam

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.example.usbcam.databinding.ActivityMainBinding
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.ausbc.MultiCameraClient
import java.io.File

class MainActivity : CameraActivity() {
    private lateinit var b: ActivityMainBinding

    override fun getRootView(layoutInflater: LayoutInflater): View {
        b = ActivityMainBinding.inflate(layoutInflater)
        return b.root
    }

    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(this)

    override fun getCameraViewContainer(): ViewGroup = b.cameraContainer

    override fun initView() {
        super.initView()
        b.btnShot.setOnClickListener { takePhoto() }
        b.btnGallery.setOnClickListener { openGallery() }
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        runOnUiThread {
            when (code) {
                ICameraStateCallBack.State.OPENED -> b.status.visibility = View.GONE
                else -> {
                    b.status.text = if (code == ICameraStateCallBack.State.ERROR)
                        "Błąd kamery: $msg" else "Nie wykryto kamery OTG"
                    b.status.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun takePhoto() {
        if (!isCameraOpened()) {
            toast("Brak kamery"); return
        }
        val tmp = File(cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        captureImage(object : ICaptureCallBack {
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
