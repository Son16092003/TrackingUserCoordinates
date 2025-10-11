package com.plcoding.backgroundlocationtracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class DefaultLocationClient(
    private val context: Context,
    private val client: FusedLocationProviderClient
) : LocationClient {

    companion object {
        private const val TAG = "DefaultLocationClient"
    }

    @SuppressLint("MissingPermission")
    override fun getLocationUpdates(interval: Long): Flow<Location> = callbackFlow {
        Log.d(TAG, "🚀 getLocationUpdates() called with interval=${interval}ms")

        // --- Kiểm tra quyền location ---
        val hasFine = context.hasFineLocationPermission()
        val hasCoarse = context.hasCoarseLocationPermission()
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.hasBackgroundLocationPermission() else true

        Log.d(TAG, "🔍 ACCESS_FINE_LOCATION: ${if (hasFine) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "🔍 ACCESS_COARSE_LOCATION: ${if (hasCoarse) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "🔍 ACCESS_BACKGROUND_LOCATION: ${if (hasBackground) "✅ GRANTED" else "❌ DENIED"}")

        if (!context.hasLocationPermission()) {
            throw LocationClient.LocationException("Missing location permission")
        }

        // --- Kiểm tra trạng thái GPS / Network ---
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "📡 GPS Provider: ${if (isGpsEnabled) "✅ Enabled" else "❌ Disabled"}")
        Log.d(TAG, "🌐 Network Provider: ${if (isNetworkEnabled) "✅ Enabled" else "❌ Disabled"}")

        if (!isGpsEnabled && !isNetworkEnabled) {
            throw LocationClient.LocationException("GPS is disabled")
        }

        // --- API >= 29: Dùng FusedLocationProviderClient ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "📱 Using FusedLocationProviderClient (API ≥ 29)")

            val request = LocationRequest.Builder(interval)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(interval)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.lastOrNull()?.let { location ->
                        Log.d(TAG, "📍 [Fused] lat=${location.latitude}, lon=${location.longitude}")
                        launch { send(location) }
                    }
                }

                override fun onLocationAvailability(p0: LocationAvailability) {
                    Log.d(TAG, "📶 Location availability: ${p0.isLocationAvailable}")
                }
            }

            client.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Location updates started with FusedLocationProviderClient")

            awaitClose {
                Log.d(TAG, "🧹 Removing FusedLocationProviderClient updates")
                client.removeLocationUpdates(locationCallback)
            }

        } else {
            // --- API 23–28: fallback sang LocationManager ---
            Log.d(TAG, "📱 Using LocationManager fallback (API 23–28)")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(TAG, "📍 [Legacy] lat=${location.latitude}, lon=${location.longitude}")
                    launch { send(location) }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {
                    Log.d(TAG, "✅ Provider enabled: $provider")
                }
                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "⚠️ Provider disabled: $provider")
                }
            }

            try {
                if (isGpsEnabled) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        interval,
                        5f, // tránh gửi liên tục quá nhanh
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d(TAG, "✅ GPS provider registered for updates")
                } else if (isNetworkEnabled) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        interval,
                        10f,
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d(TAG, "✅ Network provider registered for updates")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Missing location permission", e)
                throw LocationClient.LocationException("Missing location permission")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to request location updates: ${e.message}", e)
                throw LocationClient.LocationException("Failed to request location updates: ${e.message}")
            }

            awaitClose {
                Log.d(TAG, "🧹 Removing LocationManager updates")
                locationManager.removeUpdates(listener)
            }
        }
    }
}
