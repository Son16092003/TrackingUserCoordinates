package com.plcoding.backgroundlocationtracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

// Class này triển khai interface LocationClient
class DefaultLocationClient(
    private val context: Context,                           // Ngữ cảnh Android
    private val client: FusedLocationProviderClient         // Google fused location API client
): LocationClient {

    @SuppressLint("MissingPermission") // Bỏ cảnh báo vì ta đã kiểm tra permission thủ công
    override fun getLocationUpdates(interval: Long): Flow<Location> {
        // Trả về một Flow phát ra liên tục vị trí mới
        return callbackFlow {
            // --- Kiểm tra quyền truy cập vị trí ---
            if(!context.hasLocationPermission()) {
                throw LocationClient.LocationException("Missing location permission")
            }

            // --- Kiểm tra GPS/Network có bật không ---
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if(!isGpsEnabled && !isNetworkEnabled) {
                throw LocationClient.LocationException("GPS is disabled")
            }

            // --- Tạo yêu cầu lấy vị trí ---
            val request = LocationRequest.create()
                .setInterval(interval)          // Thời gian lấy vị trí (ms)
                .setFastestInterval(interval)   // Khoảng nhanh nhất có thể lấy

            // --- Callback khi có kết quả vị trí mới ---
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    super.onLocationResult(result)
                    result.locations.lastOrNull()?.let { location ->
                        // Gửi vị trí mới vào Flow
                        launch { send(location) }
                    }
                }
            }

            // --- Đăng ký cập nhật vị trí ---
            client.requestLocationUpdates(
                request,                        // Yêu cầu vị trí
                locationCallback,               // Callback nhận kết quả
                Looper.getMainLooper()          // Chạy trên luồng chính
            )

            // --- Khi Flow bị đóng, hủy đăng ký để tiết kiệm pin ---
            awaitClose {
                client.removeLocationUpdates(locationCallback)
            }
        }
    }
}
