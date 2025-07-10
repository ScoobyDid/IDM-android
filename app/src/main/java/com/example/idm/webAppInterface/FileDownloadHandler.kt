package com.example.idm.webAppInterface

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
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.idm.R
import com.example.idm.showJsError
import com.example.idm.showJsToast
import io.sentry.Sentry
import io.sentry.SentryLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException




class FileDownloadHandler(
    private val activityContext: Activity,
    private val webView: WebView 
) {
    val FILE_DOWNLOAD_CHANNEL_ID = "base64_file_downloads"
    val FILE_DOWNLOAD_CHANNEL_NAME = "File Downloads"


    


    fun downloadFile(url: String, fileName: String?, mimeType: String?) {
        activityContext.runOnUiThread {
            val downloadManager =
                activityContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

            if (downloadManager == null) {
                webView.showJsError("DownloadManager not available.")
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
                webView.showJsError("Invalid URL for download: $url")
            }
            catch (e: Exception) {
                webView.showJsError("Could not start download.")
            }
        }
    }






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
                        successfullySaved = true
                    } catch (e: IllegalArgumentException) {
                        webView.showJsError("FileProvider error: ${e.message}")
                        fileUriForNotification = Uri.fromFile(file)
                    }

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
                webView.showJsError("Error processing file data.")
                val errorMessage = "Error decoding Base64 data: ${e.message}"
                Sentry.addBreadcrumb(errorMessage, "FileDownloadHandler")
                Sentry.captureException(e)
            } catch (e: Exception) {
                webView.showJsError("Error processing file data.")
                val errorMessage = "Error saving or notifying for Base64 file '$fileName': ${e.message}"
                Sentry.addBreadcrumb(errorMessage, "FileDownloadHandler")
                Sentry.captureException(e)
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
            webView.showJsError("No app available to open this file type.")
            Sentry.captureMessage("No activity found to handle $mimeType for URI $fileUri", SentryLevel.WARNING)

            // still show a notification but without a content intent or with a different message
            val builder = NotificationCompat.Builder(context, FILE_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_done) // Ensure this drawable exists
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
            .setSmallIcon(R.drawable.ic_download_done)
            .setContentTitle("$fileName Downloaded")
            .setContentText("Tap to open $fileName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
        } catch (se: SecurityException) {
            val errorMessage = "SecurityException on notify. POST_NOTIFICATIONS permission missing?"
            Sentry.addBreadcrumb(errorMessage, "FileDownloadHandler")
            Sentry.captureException(se)
            webView.showJsError("Notification permission needed to show download status.")
        }
    }
}
