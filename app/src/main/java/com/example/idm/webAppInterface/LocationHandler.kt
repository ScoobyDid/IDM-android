package com.example.idm.webAppInterface

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import com.example.idm.MainActivity
import com.google.android.gms.location.*
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.json.JSONException
import org.json.JSONObject



class LocationHandler(
    private val activityContext: Activity,
    private val webView: WebView,
    private val mainActivity: MainActivity
) {
    private var fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(activityContext)

    // for single getCurrentLocation requests
    private var pendingPermissionJsCallbackId: String? = null
    private var pendingPermissionOptionsJson: String? = null

    // for watchPosition
    private val activeWatchers = mutableMapOf<String, LocationCallback>()
    private var pendingWatchPermissionWatchId: String? = null
    private var pendingWatchPermissionOptionsJson: String? = null

    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_HIGH_ACCURACY_THRESHOLD_METERS = 100f // 100 meters
        private const val DEFAULT_WATCH_INTERVAL_MS: Long = 10000
        private const val DEFAULT_WATCH_FASTEST_INTERVAL_MS: Long = 5000
    }

    data class LocationOptions(
        val enableHighAccuracy: Boolean,
        val maximumAge: Long,
        val interval: Long?,
        val fastestInterval: Long?
    )





    fun getCurrentLocation(jsCallbackId: String, optionsJsonString: String) {
        Log.d("LocationHandler", "getCurrentLocation called from JS with callback ID: $jsCallbackId and options: $optionsJsonString")
        val options = parseOptions(optionsJsonString)

        activityContext.runOnUiThread {
            if (hasLocationPermission()) {
                Log.d("LocationHandler", "Location permission already granted for $jsCallbackId.")
                processLocationRequest(jsCallbackId, options)
            } else {
                Log.d("LocationHandler", "Location permission NOT granted for $jsCallbackId. Requesting permission.")
                this.pendingPermissionJsCallbackId = jsCallbackId
                this.pendingPermissionOptionsJson = optionsJsonString
                requestLocationPermission()
            }
        }
    }





    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            activityContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }





    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            mainActivity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }





    // this method needs to be called from WebAppInterface, which gets it from MainActivity
    fun handlePermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (pendingWatchPermissionWatchId != null) {
                val watchId = this.pendingWatchPermissionWatchId!!
                val optionsJson = this.pendingWatchPermissionOptionsJson!!
                this.pendingWatchPermissionWatchId = null
                this.pendingWatchPermissionOptionsJson = null
                val options = parseOptions(optionsJson, true)

                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startNativeWatch(watchId, options)
                } else {
                    Sentry.captureMessage("Location permission DENIED by user", SentryLevel.WARNING)
                    sendPositionErrorToJs(watchId, "PERMISSION_DENIED", "User denied location permission for watch.")
                }
            }

            else if (pendingPermissionJsCallbackId != null) {
                val jsCallbackId = this.pendingPermissionJsCallbackId!!
                val optionsJson = this.pendingPermissionOptionsJson!!
                this.pendingPermissionJsCallbackId = null
                this.pendingPermissionOptionsJson = null
                val options = parseOptions(optionsJson, false)

                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("LocationHandler", "Location permission GRANTED for $jsCallbackId.")
                    processLocationRequest(jsCallbackId, options)
                } else {
                    Sentry.captureMessage("Location permission DENIED by user", SentryLevel.WARNING)
                    sendErrorToJs(jsCallbackId, "PERMISSION_DENIED", "User denied location permission.")
                }
            } else {
                val message = "Permission result received but no pending request found."
                Sentry.captureMessage(message, SentryLevel.INFO)
                Log.w("LocationHandler", message)
            }
        }
    }





    @SuppressLint("MissingPermission") // permission is checked before calling this path
    private fun processLocationRequest(jsCallbackId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            sendErrorToJs(jsCallbackId, "LOCATION_SERVICES_DISABLED", "Location services are disabled on the device.")
            return
        }

        if (options.maximumAge > 0) {
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location: Location? ->
                        if (location != null) {
                            val locationAge = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                            val locationAgeMillis = locationAge / 1_000_000

                            Log.d("LocationHandler", "Last known location for $jsCallbackId: $location, Age: $locationAgeMillis ms, MaxAge: ${options.maximumAge}")

                            var useCached = locationAgeMillis <= options.maximumAge

                            if (useCached && options.enableHighAccuracy) {
                                // if high accuracy is requested, cached location must also be accurate enough
                                if (location.accuracy > DEFAULT_HIGH_ACCURACY_THRESHOLD_METERS) {
                                    Log.d("LocationHandler", "Cached location for $jsCallbackId is recent, but not accurate enough for enableHighAccuracy=true (Accuracy: ${location.accuracy}m). Requesting fresh.")
                                    useCached = false
                                } else {
                                    Log.d("LocationHandler", "Cached location for $jsCallbackId is recent AND accurate enough for enableHighAccuracy=true.")
                                }
                            } else if (useCached) {
                                Log.d("LocationHandler", "Cached location for $jsCallbackId is recent and enableHighAccuracy=false. Using cached.")
                            }


                            if (useCached) {
                                Log.d("LocationHandler", "Using cached location for $jsCallbackId.")
                                sendLocationToJs(jsCallbackId, location)
                                return@addOnSuccessListener
                            }
                        } else {
                            Log.d("LocationHandler", "Last known location is null")
                        }
                        // if cached location not used (too old, not accurate enough, or null), request a fresh one.
                        Log.d("LocationHandler", "Proceeding to request fresh location.")
                        requestFreshLocation(jsCallbackId, options)
                    }
                    .addOnFailureListener { e ->
                        val errorMessage = "Error getting lastLocation. Requesting fresh location as fallback."
                        Sentry.addBreadcrumb(errorMessage, "LocationHandler")
                        Sentry.captureException(e)
                        Log.e("LocationHandler", "Error getting lastLocation. Requesting fresh location as fallback.", e)
                        requestFreshLocation(jsCallbackId, options) // fallback to fresh location
                    }
                return // Exit processLocationRequest as lastLocation handling is asynchronous
            } catch (se: SecurityException) {
                // this catch block is for the rare case that .lastLocation itself throws a SecurityException
                // (e.g. if permissions were revoked between the check and the call, though unlikely with runOnUiThread)
                val errorMessage = "SecurityException on lastLocation access. Requesting fresh location as fallback."
                Sentry.addBreadcrumb(errorMessage, "LocationHandler")
                Sentry.captureException(se)
                Log.e("LocationHandler", "SecurityException on lastLocation access. Requesting fresh location as fallback.", se)
                // fallback to fresh location
                requestFreshLocation(jsCallbackId, options)
                return
            }
        }

        // if maximumAge is 0 or not set, or if lastLocation check decided to fetch fresh:
        Log.d("LocationHandler", "maximumAge is 0 or lastLocation check failed/skipped. Requesting fresh location for $jsCallbackId.")
        requestFreshLocation(jsCallbackId, options)
    }





    @SuppressLint("MissingPermission") // permissions are checked before calling
    private fun requestFreshLocation(jsCallbackId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            // this check is important here as well, as user might disable it between permission grant and this call
            sendErrorToJs(jsCallbackId, "LOCATION_SERVICES_DISABLED", "Location services are disabled on the device (for current location).")
            return
        }

        val locationRequestPriority = if (options.enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            priority = locationRequestPriority
            numUpdates = 1 // we only need one update for getCurrentPosition
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationHandler", "Fetched current location: $location")
                    sendLocationToJs(jsCallbackId, location)
                } ?: run {
                    val message = "Current location result is null in callback."
                    Sentry.captureMessage(message, SentryLevel.ERROR)
                    Log.e("LocationHandler", message)
                    sendErrorToJs(jsCallbackId, "ERROR_CURRENT_LOCATION_NULL", "Failed to get current location (result null).")
                }
                fusedLocationClient.removeLocationUpdates(this)
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                if (!locationAvailability.isLocationAvailable) {
                    val message = "Location not available."
                    Sentry.captureMessage(message, SentryLevel.WARNING)
                    Log.e("LocationHandler", message)
                    sendErrorToJs(jsCallbackId, "LOCATION_NOT_AVAILABLE", "Location is currently not available.")
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            val errorMessage = "Exception during requestLocationUpdates"
            Sentry.addBreadcrumb(errorMessage, "LocationHandler")
            Sentry.captureException(e)
            Log.e("LocationHandler", errorMessage, e)
            sendErrorToJs(jsCallbackId, "ERROR_REQUESTING_UPDATES", "Failed to request location updates: ${e.message}")
        }
    }





    fun startWatchPosition(watchId: String, optionsJsonString: String) {
        Log.d("LocationHandler", "startWatchPosition called from JS for watch ID: $watchId with options: $optionsJsonString")
        val options = parseOptions(optionsJsonString, true)

        activityContext.runOnUiThread {
            if (hasLocationPermission()) {
                Log.d("LocationHandler", "Location permission already granted for watch $watchId.")
                startNativeWatch(watchId, options)
            } else {
                Log.d("LocationHandler", "Location permission NOT granted for watch $watchId. Requesting permission.")
                this.pendingWatchPermissionWatchId = watchId
                this.pendingWatchPermissionOptionsJson = optionsJsonString
                requestLocationPermission()
            }
        }
    }





    @SuppressLint("MissingPermission")
    private fun startNativeWatch(watchId: String, options: LocationOptions) {
        if (!isLocationEnabled()) {
            sendPositionErrorToJs(watchId, "LOCATION_SERVICES_DISABLED", "Location services are disabled for watch.")
            return
        }

        // if a watcher with this ID already exists, stop it first
        activeWatchers[watchId]?.let { existingCallback ->
            fusedLocationClient.removeLocationUpdates(existingCallback)
            Log.d("LocationHandler", "Stopped existing watcher for ID: $watchId before starting new one.")
        }

        val locationRequestPriority = if (options.enableHighAccuracy) {
            LocationRequest.PRIORITY_HIGH_ACCURACY
        } else {
            LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val locationRequest = LocationRequest.create().apply {
            priority = locationRequestPriority
            options.interval?.let { interval = it }
            options.fastestInterval?.let { fastestInterval = it }
        }
        Log.d("LocationHandler", "Starting NATIVE watch for $watchId with priority: $locationRequestPriority, interval: ${options.interval}")


        val watchLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d("LocationHandler", "Watch update for $watchId: $location")
                    sendPositionUpdateToJs(watchId, location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                watchLocationCallback,
                Looper.getMainLooper()
            )
            activeWatchers[watchId] = watchLocationCallback
            Log.d("LocationHandler", "Successfully started and stored native watcher for ID: $watchId")
        } catch (e: Exception) {
            val errorMessage = "Exception during startNativeWatch"
            Sentry.addBreadcrumb(errorMessage, "LocationHandler")
            Sentry.captureException(e)
            Log.e("LocationHandler", errorMessage, e)
            sendPositionErrorToJs(watchId, "ERROR_STARTING_WATCH", "Failed to start watch: ${e.message}")
        }
    }





    fun stopWatchPosition(watchId: String) {
        activityContext.runOnUiThread {
            activeWatchers.remove(watchId)?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
                Log.d("LocationHandler", "Stopped and removed native watcher for ID: $watchId")
            } ?: run {
                Log.w("LocationHandler", "No active watcher found to stop for ID: $watchId")
            }
        }
    }





    fun stopAllWatchPositions() {
        activeWatchers.forEach { (watchId, callback) ->
            fusedLocationClient.removeLocationUpdates(callback)
            Log.d("LocationHandler", "Stopped and removed native watcher for ID: $watchId")
        }
        activeWatchers.clear()
    }





    private fun isLocationEnabled(): Boolean {
        val locationManager = activityContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }





    private fun sendLocationToJs(jsCallbackId: String, location: Location) {
        val positionObj = JSONObject()
        val coordsObj = JSONObject()
        coordsObj.put("latitude", location.latitude)
        coordsObj.put("longitude", location.longitude)
        // by default accuracy has 68% confidence level, which is not enough if we want to show accuracy circle to users
        // so we convert it to 99% confidence level by multiplying by 2.58 (lower accuracy but higher confidence level)
        // for comparison, html5 geolocation api provides 95% confidence level (multiplied by 1.96)
        coordsObj.put("accuracy", location.accuracy * 2.58f)
        coordsObj.put("speed", if (location.hasSpeed()) location.speed else null)
        positionObj.put("coords", coordsObj)
        positionObj.put("timestamp", location.time)

        val script = "javascript:window.androidLocationBridge.resolveLocation('$jsCallbackId', ${positionObj.toString()});"
        Log.d("LocationHandler", "Sending location to JS for $jsCallbackId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }





    private fun sendErrorToJs(jsCallbackId: String, errorCode: String, message: String) {
        val errorObj = JSONObject()
        errorObj.put("code", errorCode)
        errorObj.put("message", message)

        val script = "javascript:window.androidLocationBridge.rejectLocation('$jsCallbackId', ${errorObj.toString()});"
        Log.d("LocationHandler", "Sending error to JS for $jsCallbackId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }





    private fun sendPositionUpdateToJs(watchId: String, location: Location) {
        val positionObj = JSONObject()
        val coordsObj = JSONObject()
        coordsObj.put("latitude", location.latitude)
        coordsObj.put("longitude", location.longitude)
        coordsObj.put("accuracy", location.accuracy * 2.58f)
        coordsObj.put("speed", if (location.hasSpeed()) location.speed else null)
        positionObj.put("coords", coordsObj)
        positionObj.put("timestamp", location.time)

        val script = "javascript:window.androidLocationBridge.notifyPositionUpdate('$watchId', ${positionObj.toString()});"
        Log.d("LocationHandler", "Sending watch update to JS for $watchId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }





    private fun sendPositionErrorToJs(watchId: String, errorCode: String, message: String) {
        val errorObj = JSONObject()
        errorObj.put("code", errorCode)
        errorObj.put("message", message)

        val script = "javascript:window.androidLocationBridge.notifyPositionError('$watchId', ${errorObj.toString()});"
        Log.d("LocationHandler", "Sending watch error to JS for $watchId: $script")
        webView.post { webView.evaluateJavascript(script, null) }
    }





    private fun parseOptions(optionsJsonString: String?, isWatchRequest: Boolean = false): LocationOptions {
        var enableHighAccuracy = true
        var maximumAge: Long = 0
        var interval: Long? = if (isWatchRequest) DEFAULT_WATCH_INTERVAL_MS else null
        var fastestInterval: Long? = if (isWatchRequest) DEFAULT_WATCH_FASTEST_INTERVAL_MS else null


        if (optionsJsonString != null) {
            try {
                val json = JSONObject(optionsJsonString)
                enableHighAccuracy = json.optBoolean("enableHighAccuracy", true)
                maximumAge = json.optLong("maximumAge", 0) // used by getCurrentLocation only

                if (isWatchRequest) {
                    interval = json.optLong("interval", DEFAULT_WATCH_INTERVAL_MS)
                    fastestInterval = json.optLong("fastestInterval", interval / 2)
                }
            } catch (e: JSONException) {
                val errorMessage = "Failed to parse location options JSON: $optionsJsonString"
                Sentry.addBreadcrumb(errorMessage, "LocationHandler")
                Sentry.captureException(e)
                Log.e("LocationHandler", errorMessage, e)
            }
        }
        return LocationOptions(enableHighAccuracy, maximumAge, interval, fastestInterval)
    }
}
