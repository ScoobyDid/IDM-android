package com.example.idm.webChromeClient

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.idm.MainActivity
import io.sentry.Sentry
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MyWebChromeClient(
    private val activityContext: Context,
    private val activity: MainActivity,
    private val permissionLauncher: ActivityResultLauncher<Array<String>>,
    private val fileChooserLauncher: ActivityResultLauncher<Intent>,
    private var onCameraPhotoUriGenerated: (Uri) -> Unit,
    private var onFilePathCallbackAssigned: (ValueCallback<Array<Uri>>?) -> Unit
) : WebChromeClient() {
    private var geolocationCallback: GeolocationPermissions.Callback? = null
    private var geolocationOrigin: String? = null
    private var tempCameraPhotoUri: Uri? = null





    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)

        if (newProgress >= 84) {
            if (activity.keepSplashOnScreen.get()) {
                activity.keepSplashOnScreen.set(false)
            }
        }
    }





    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        // store the callback and origin for later use
        this.geolocationCallback = callback
        this.geolocationOrigin = origin

        if (ContextCompat.checkSelfPermission(
                activityContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            callback.invoke(origin, true, false) // allow, not retain
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }





    // this method is called when the permission request result is received
    fun onLocationPermissionsResult(granted: Boolean) {
        geolocationCallback?.let { callback ->
            geolocationOrigin?.let { origin ->
                callback.invoke(origin, granted, false) // allow, not retain
            }
        }
        geolocationCallback = null
        geolocationOrigin = null
    }





    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // notify activity to clear any previous callback first
        onFilePathCallbackAssigned(null) // this tells the activity to clear its stored callback
        onFilePathCallbackAssigned(filePathCallback) // this tells the activity to store the new callback

        val acceptTypes = fileChooserParams?.acceptTypes?.filterNotNull()?.filter { it.isNotBlank() }?.joinToString(",") ?: "*/*"
        val captureEnabled = fileChooserParams?.isCaptureEnabled == true

        val intents = mutableListOf<Intent>()

        // intent for Camera
        if (captureEnabled || fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE || fileChooserParams?.mode == FileChooserParams.MODE_OPEN) {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            var photoFile: File? = null
            try {
                photoFile = createImageFile()
                tempCameraPhotoUri = FileProvider.getUriForFile(
                    activityContext,
                    "${activityContext.applicationContext.packageName}.provider",
                    photoFile
                )
                onCameraPhotoUriGenerated(tempCameraPhotoUri!!) // pass URI to activity
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, tempCameraPhotoUri)
            } catch (ex: IOException) {
                Sentry.captureException(ex) { scope -> scope.setTag("WebChromeClient", "ImageFileCreation") }
                Log.e("MyWebChromeClient", "Unable to create image file for camera", ex)
                Toast.makeText(activityContext, "Could not create image file for camera.", Toast.LENGTH_LONG).show()
                tempCameraPhotoUri = null
            }
            if (photoFile != null && tempCameraPhotoUri != null) {
                intents.add(takePictureIntent)
            }
        }

        // intent for gallery/file chooser
        if (!captureEnabled) {
            val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (acceptTypes.isEmpty() || acceptTypes == "*/*") "image/*" else acceptTypes
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            intents.add(getContentIntent)
        }

        val chooserIntent: Intent
        if (intents.isEmpty()) {
            Log.w("MyWebChromeClient", "No suitable intents could be prepared for file chooser (acceptTypes: '$acceptTypes', capture: $captureEnabled).")
            // it's important to call back with null to prevent WebView from hanging.
            onFilePathCallbackAssigned(null) // clear callback in activity
            Toast.makeText(activityContext, "Cannot open file chooser.", Toast.LENGTH_SHORT).show()
            return true // we handled it, even if by failing to show a chooser.
        } else if (intents.size == 1 && captureEnabled) { // if only camera is an option due to 'capture' attribute
            chooserIntent = intents.first()
        } else {
            // if multiple intents (e.g. camera and gallery), create a chooser.
            val primaryIntent = if (intents.any { it.action == Intent.ACTION_GET_CONTENT } && !captureEnabled) {
                intents.first { it.action == Intent.ACTION_GET_CONTENT }
            } else {
                intents.first()
            }
            intents.remove(primaryIntent)

            chooserIntent = Intent.createChooser(primaryIntent, "Choose Action")
            if (intents.isNotEmpty()) {
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            }
        }

        try {
            fileChooserLauncher.launch(chooserIntent)
        } catch (e: ActivityNotFoundException) {
            Sentry.captureException(e) { scope -> scope.setTag("WebChromeClient", "ActivityNotFound") }
            Log.e("MyWebChromeClient", "No app found to handle this action.", e)
            onFilePathCallbackAssigned(null)
            Toast.makeText(activityContext, "No app found to handle this action.", Toast.LENGTH_LONG).show()
            return true // we tried but failed
        }
        return true // we've handled the request
    }





    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "IMG_${timeStamp}_"
        // store in app's specific cache directory since we don't need to keep it locally long-term
        val storageDir = activity.externalCacheDir ?: activity.cacheDir // fallback to internal cache
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }
}
