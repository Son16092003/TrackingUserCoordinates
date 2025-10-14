@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.plcoding.backgroundlocationtracking.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.room.Room
import com.plcoding.backgroundlocationtracking.data.local.AppDatabase
import com.plcoding.backgroundlocationtracking.data.local.PendingLocation
import com.plcoding.backgroundlocationtracking.data.SupabaseClientProvider.client
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import kotlinx.serialization.Serializable
import io.github.jan.supabase.postgrest.from

/**
 * ✅ Dữ liệu gửi lên Supabase
 */
@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val deviceId: String,
    val userName: String
)

/**
 * ✅ Repository xử lý toàn bộ logic lưu trữ & đồng bộ vị trí.
 * - Nếu có mạng: upload trực tiếp lên Supabase.
 * - Nếu mất mạng: lưu tạm trong Room DB.
 * - Khi có mạng trở lại: worker tự động sync toàn bộ pending.
 */
class LocationRepository(private val context: Context) {

    // ✅ Khởi tạo Room DB với fallback migration
    private val db = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "location_db"
    )
        .fallbackToDestructiveMigration()
        .build()

    private val dao = db.locationDao()

    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }

    private fun getUserName(): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("userName", "UnknownUser") ?: "UnknownUser"
    }

    /**
     * ✅ Lưu hoặc upload vị trí tuỳ vào trạng thái mạng.
     * - Nếu upload thành công: xong.
     * - Nếu thất bại (offline): lưu vào Room DB.
     */
    suspend fun saveOrUploadLocation(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        deviceId: String? = null,
        userName: String? = null
    ) {
        val resolvedDeviceId = deviceId ?: getDeviceId()
        val resolvedUserName = userName ?: getUserName()

        Log.d(
            "LocationRepository",
            "📤 Upload attempt → lat=$latitude, lon=$longitude, time=$timestamp, device=$resolvedDeviceId, user=$resolvedUserName"
        )

        try {
            // 🔼 Upload trực tiếp lên Supabase
            client.from("locations").insert(
                LocationPayload(latitude, longitude, timestamp, resolvedDeviceId, resolvedUserName)
            )
            Log.d(
                "LocationRepository",
                "✅ Upload thành công → lat=$latitude, lon=$longitude, device=$resolvedDeviceId, user=$resolvedUserName"
            )
        } catch (e: Exception) {
            // 🌐 Lỗi mạng → lưu lại cục bộ để sync sau
            Log.w(
                "LocationRepository",
                "⚠️ Upload thất bại → Lưu tạm local để retry sau (device=$resolvedDeviceId, user=$resolvedUserName)",
                e
            )
            savePendingLocation(latitude, longitude, timestamp, resolvedDeviceId, resolvedUserName)
        }
    }

    /**
     * ✅ Lưu location pending vào Room khi upload thất bại.
     */
    private suspend fun savePendingLocation(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        deviceId: String,
        userName: String
    ) {
        try {
            val pending = PendingLocation(
                latitude = latitude,
                longitude = longitude,
                timestamp = timestamp,
                deviceId = deviceId,
                userName = userName
            )
            dao.insertLocation(pending)
            Log.d(
                "LocationRepository",
                "💾 Lưu pending thành công → lat=$latitude, lon=$longitude, device=$deviceId, user=$userName"
            )
        } catch (e: Exception) {
            Log.e(
                "LocationRepository",
                "❌ [ROOM FAILED] Không thể lưu pending location → lat=$latitude, lon=$longitude, device=$deviceId, user=$userName",
                e
            )
        }
    }

    /**
     * ✅ Đồng bộ toàn bộ location pending trong Room DB khi có mạng.
     * - Upload từng bản ghi.
     * - Nếu upload thành công → xoá bản ghi local.
     * - Nếu lỗi mạng → giữ lại để retry lần sau.
     */
    suspend fun syncPendingLocations(): Boolean {
        val pendingList = dao.getAllLocations()
        if (pendingList.isEmpty()) {
            Log.d("LocationRepository", "ℹ️ Không có dữ liệu pending để sync.")
            return true
        }

        Log.d("LocationRepository", "🔄 Bắt đầu sync ${pendingList.size} pending locations...")

        var hasNetworkError = false
        val uploadedIds = mutableListOf<Int>()

        for (loc in pendingList) {
            try {
                client.from("locations").insert(
                    LocationPayload(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        timestamp = loc.timestamp,
                        deviceId = loc.deviceId,
                        userName = loc.userName
                    )
                )
                uploadedIds.add(loc.id)
                Log.d("LocationRepository", "✅ Uploaded pending → ${loc.id}")
            } catch (e: UnauthorizedRestException) {
                // Token hết hạn hoặc quyền bị thu hồi → xoá khỏi pending
                uploadedIds.add(loc.id)
                Log.e("LocationRepository", "🚫 Unauthorized, dropped ${loc.id}", e)
            } catch (e: Exception) {
                hasNetworkError = true
                Log.e("LocationRepository", "🌐 Network error, giữ lại ${loc.id}", e)
            }
        }

        // 🗑️ Xoá các bản ghi upload thành công
        if (uploadedIds.isNotEmpty()) {
            dao.deleteLocations(uploadedIds)
            Log.d("LocationRepository", "🗑️ Đã xoá ${uploadedIds.size} bản ghi pending đã upload.")
        }

        // ✅ Trả về true nếu tất cả thành công, false nếu có lỗi mạng
        return !hasNetworkError
    }
}
