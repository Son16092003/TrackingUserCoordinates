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
        Log.d(TAG, "🚀 [START] getLocationUpdates() called with interval=${interval}ms")

        // --- Kiểm tra quyền location ---
        val hasFine = context.hasFineLocationPermission()
        val hasCoarse = context.hasCoarseLocationPermission()
        val hasBackground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.hasBackgroundLocationPermission() else true

        Log.d(TAG, "🔍 Kiểm tra quyền:")
        Log.d(TAG, "   ➤ ACCESS_FINE_LOCATION: ${if (hasFine) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "   ➤ ACCESS_COARSE_LOCATION: ${if (hasCoarse) "✅ GRANTED" else "❌ DENIED"}")
        Log.d(TAG, "   ➤ ACCESS_BACKGROUND_LOCATION: ${if (hasBackground) "✅ GRANTED" else "❌ DENIED"}")

        if (!context.hasLocationPermission()) {
            Log.e(TAG, "❌ [ERROR] Thiếu quyền truy cập vị trí!")
            throw LocationClient.LocationException("Missing location permission")
        }

        // --- Kiểm tra trạng thái GPS / Network ---
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "📡 Trạng thái Provider:")
        Log.d(TAG, "   ➤ GPS Provider: ${if (isGpsEnabled) "✅ Enabled" else "❌ Disabled"}")
        Log.d(TAG, "   ➤ Network Provider: ${if (isNetworkEnabled) "✅ Enabled" else "❌ Disabled"}")

        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.e(TAG, "❌ [ERROR] GPS và Network đều đang tắt!")
            throw LocationClient.LocationException("GPS is disabled")
        }

        // --- API >= 29: Dùng FusedLocationProviderClient ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "📱 Dùng FusedLocationProviderClient (API ≥ 29)")

            val request = LocationRequest.Builder(interval)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(interval)
                .build()

            Log.d(TAG, "🧩 Đã tạo LocationRequest: interval=${interval}ms, priority=HIGH_ACCURACY")

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.locations.lastOrNull()
                    if (location != null) {
                        Log.d(
                            TAG,
                            "📍 [Fused] New Location => lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
                        )
                        launch { send(location) }
                    } else {
                        Log.w(TAG, "⚠️ [Fused] Không có location trong kết quả.")
                    }
                }

                override fun onLocationAvailability(p0: LocationAvailability) {
                    Log.d(TAG, "📶 Location availability: ${p0.isLocationAvailable}")
                }
            }

            Log.d(TAG, "🚦 Bắt đầu nhận cập nhật vị trí qua FusedLocationProviderClient...")
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "✅ Đã đăng ký callback nhận vị trí thành công (FusedLocationProviderClient)")

            awaitClose {
                Log.d(TAG, "🧹 [CLOSE] Xoá cập nhật FusedLocationProviderClient...")
                client.removeLocationUpdates(locationCallback)
                Log.d(TAG, "🧽 [DONE] Đã gỡ callback FusedLocationProviderClient.")
            }

        } else {
            // --- API 23–28: fallback sang LocationManager ---
            Log.d(TAG, "📱 Dùng LocationManager fallback (API 23–28)")

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    Log.d(
                        TAG,
                        "📍 [Legacy] New Location => lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
                    )
                    launch { send(location) }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    Log.d(TAG, "ℹ️ [Legacy] Status changed for $provider => $status")
                }

                override fun onProviderEnabled(provider: String) {
                    Log.d(TAG, "✅ [Legacy] Provider enabled: $provider")
                }

                override fun onProviderDisabled(provider: String) {
                    Log.w(TAG, "⚠️ [Legacy] Provider disabled: $provider")
                }
            }

            try {
                Log.d(TAG, "🧩 Đăng ký listener cập nhật vị trí...")

                if (isGpsEnabled) {
                    Log.d(TAG, "➡️ Ưu tiên GPS_PROVIDER")
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        interval,
                        5f, // tránh spam
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d(TAG, "✅ Đăng ký thành công GPS_PROVIDER")
                } else if (isNetworkEnabled) {
                    Log.d(TAG, "➡️ Dùng NETWORK_PROVIDER")
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        interval,
                        10f,
                        listener,
                        Looper.getMainLooper()
                    )
                    Log.d(TAG, "✅ Đăng ký thành công NETWORK_PROVIDER")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ Thiếu quyền truy cập vị trí (SecurityException)", e)
                throw LocationClient.LocationException("Missing location permission")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Không thể đăng ký cập nhật vị trí: ${e.message}", e)
                throw LocationClient.LocationException("Failed to request location updates: ${e.message}")
            }

            awaitClose {
                Log.d(TAG, "🧹 [CLOSE] Xoá listener LocationManager...")
                locationManager.removeUpdates(listener)
                Log.d(TAG, "🧽 [DONE] Đã gỡ listener LocationManager.")
            }
        }
    }
}
