package com.plcoding.backgroundlocationtracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Extension function cho Context, dùng để kiểm tra xem app đã được cấp
 * quyền truy cập vị trí hay chưa.
 *
 * Hàm trả về true nếu cả hai quyền:
 *  1. ACCESS_COARSE_LOCATION  (vị trí gần đúng)
 *  2. ACCESS_FINE_LOCATION    (vị trí chính xác)
 * đã được cấp.
 *
 * Trả về false nếu thiếu ít nhất một trong hai quyền.
 *
 * Sử dụng để kiểm tra trước khi thực hiện các tác vụ yêu cầu vị trí,
 * ví dụ: truy cập GPS, location services, định vị người dùng.
 *
 * Ví dụ sử dụng:
 * if (context.hasLocationPermission()) {
 *     // Đã có quyền, có thể truy cập vị trí
 * } else {
 *     // Chưa có quyền, cần request quyền
 * }
 */
fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
}
