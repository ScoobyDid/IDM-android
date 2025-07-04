package com.example.idm

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WebAppInterface(private val activityContext: Activity, private val webView: WebView, private val mainActivity: MainActivity) {
    val FILE_DOWNLOAD_CHANNEL_ID = "base64_file_downloads"
    val FILE_DOWNLOAD_CHANNEL_NAME = "File Downloads"





    @JavascriptInterface
    fun refreshBarCanAppear(value: Boolean) {
        mainActivity.refreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun forceRefreshBarCanAppear(value: Boolean) {
        mainActivity.forceRefreshBarCanAppear.set(value)
    }





    @JavascriptInterface
    fun downloadFile(url: String, fileName: String?, mimeType: String?) {
        activityContext.runOnUiThread {
            val downloadManager =
                activityContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

            if (downloadManager == null) {
                webView.showJsToast("DownloadManager not available.", listOf("error"), 10_000)
                return@runOnUiThread
            }

            try {
                val requestUri = url.toUri()
                val effectiveFileName = if (!fileName.isNullOrEmpty()) {
                    fileName
                } else {
                    // guess filename if not provided.
                    URLUtil.guessFileName(url, null, null)
                }

                val request = DownloadManager.Request(requestUri).apply {
                    setTitle(effectiveFileName)
                    setDescription("Downloading file...")
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                    // save to the public 'Downloads' directory
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        effectiveFileName
                    )

                    // set mime type if known, otherwise DownloadManager tries to infer
                    if (!mimeType.isNullOrEmpty()) {
                        setMimeType(mimeType)
                    }

                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                webView.showJsToast("Download started", null, 2000)
                downloadManager.enqueue(request)
            }
            catch (e: IllegalArgumentException) {
                webView.showJsToast("Invalid URL for download: $url", listOf("error"), 10_000)
            }
            catch (e: Exception) {
                webView.showJsToast("Could not start download.", listOf("error"), 10_000)
            }
        }
    }






    @JavascriptInterface
    // intended to use for files that are not publicly accessible by URLs (e.g. blob urls)
    fun downloadBase64File(base64Data: String, fileName: String, mimeType: String) {
        activityContext.runOnUiThread {
            var fileUriForNotification: Uri? = null

            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                var successfullySaved = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // for API 29+
                    val resolver = activityContext.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    var insertedUri: Uri? = null
                    try {
                        insertedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (insertedUri == null) throw IOException("Failed to create MediaStore record.")

                        resolver.openOutputStream(insertedUri).use { os ->
                            os?.write(decodedBytes) ?: throw IOException("Failed to get output stream.")
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(insertedUri, contentValues, null, null)
                        fileUriForNotification = insertedUri
                        successfullySaved = true

                        // explicit broadcast to encourage faster gallery update for images
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        scanIntent.data = fileUriForNotification
                        activityContext.sendBroadcast(scanIntent)

                    } catch (e: Exception) {
                        insertedUri?.let { resolver.delete(it, null, null) }
                        throw e
                    }
                }
                else {
                    // for API < 29
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    val file = File(downloadsDir, fileName)
                    FileOutputStream(file).use { outputStream ->
                        outputStream.write(decodedBytes)
                    }

                    try {
                        fileUriForNotification = FileProvider.getUriForFile(
                            activityContext,
                            "${activityContext.packageName}.provider",
                            file
                        )
                    } catch (e: IllegalArgumentException) {
                        webView.showJsToast("FileProvider error: ${e.message}", listOf("error"), 10_000)
                        fileUriForNotification = Uri.fromFile(file)
                    }
                    successfullySaved = true

                    // explicit broadcast to encourage faster gallery update for images
                    MediaScannerConnection.scanFile(
                        activityContext,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType)
                    ) { path, uri -> }
                }

                if (successfullySaved && fileUriForNotification != null) {
                    Toast.makeText(activityContext, "$fileName downloaded.", Toast.LENGTH_SHORT).show()
                    showDownloadCompleteNotification(
                        activityContext,
                        fileUriForNotification,
                        fileName,
                        mimeType,
                        (System.currentTimeMillis() % 10000).toInt()
                    )
                } else {
                    throw IOException("File saving failed or URI was not obtained.")
                }

            } catch (e: IllegalArgumentException) {
                webView.showJsToast("Error processing file data.", listOf("error"), 10_000)
//                Log.e("WebAppInterface", "Error decoding Base64 data: ${e.message}", e)
            } catch (e: Exception) {
                webView.showJsToast("Error processing file data.", listOf("error"), 10_000)
//                Log.e("WebAppInterface", "Error saving or notifying for Base64 file '$fileName': ${e.message}", e)
            }
        }
    }





    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                FILE_DOWNLOAD_CHANNEL_ID,
                FILE_DOWNLOAD_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // Or IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for files downloaded from the app"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }





    private fun showDownloadCompleteNotification(
        context: Context,
        fileUri: Uri,
        fileName: String,
        mimeType: String,
        notificationId: Int
    ) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            // grant read permission to the app that handles the ACTION_VIEW intent
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        // check if any app can handle the intent
        if (openFileIntent.resolveActivity(context.packageManager) == null) {
            webView.showJsToast("No app available to open this file type.", listOf("error"), 10_000)
//            Log.w("Notification", "No activity found to handle $mimeType for URI $fileUri")

            // still show a notification but without a content intent or with a different message
            val builder = NotificationCompat.Builder(context, FILE_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("$fileName Downloaded")
                .setContentText("No app to open this file type.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            notificationManager.notify(notificationId, builder.build())
            return
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openFileIntent,
            pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(context, FILE_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$fileName Downloaded")
            .setContentText("Tap to open $fileName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (se: SecurityException) {
//            Log.e("WebAppInterface", "SecurityException on notify. POST_NOTIFICATIONS permission missing? ${se.message}")
            webView.showJsToast("Notification permission needed to show download status.", listOf("error"), 10_000)
        }
    }
}