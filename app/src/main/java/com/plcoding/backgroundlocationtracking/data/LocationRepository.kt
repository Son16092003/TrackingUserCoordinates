@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.plcoding.backgroundlocationtracking.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.plcoding.backgroundlocationtracking.data.local.AppDatabase
import com.plcoding.backgroundlocationtracking.data.local.PendingLocation
import com.plcoding.backgroundlocationtracking.data.SupabaseClientProvider.client
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

/**
 * Payload để gửi lên Supabase
 */
@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

class LocationRepository(context: Context) {

    private val db = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "location_db"
    ).build()

    private val dao = db.locationDao()

    /**
     * Upload trực tiếp, nếu fail thì lưu local để sync sau.
     */
    suspend fun saveOrUploadLocation(latitude: Double, longitude: Double, timestamp: Long) {
        try {
            client.from("locations").insert(
                LocationPayload(latitude, longitude, timestamp)
            )
            Log.d("LocationRepository", "✅ Uploaded location directly: $latitude,$longitude,$timestamp")
        } catch (e: Exception) {
            Log.w("LocationRepository", "⚠️ Upload failed, saving locally", e)
            dao.insertLocation(
                PendingLocation(
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp
                )
            )
        }
    }

    /**
     * Đồng bộ dữ liệu pending từ local DB lên Supabase.
     * @return true nếu tất cả pending upload thành công
     *         false nếu còn bản ghi chưa upload được (để Worker retry)
     */
    suspend fun syncPendingLocations(): Boolean {
        val pending = dao.getAllLocations()
        if (pending.isEmpty()) {
            Log.d("LocationRepository", "ℹ️ No pending locations to sync")
            return true
        }

        var hasNetworkError = false
        val uploadedIds = mutableListOf<Int>()

        for (loc in pending) {
            try {
                client.from("locations").insert(
                    LocationPayload(loc.latitude, loc.longitude, loc.timestamp)
                )
                uploadedIds.add(loc.id)
                Log.d("LocationRepository", "✅ Uploaded & removed pending: $loc")
            } catch (e: UnauthorizedRestException) {
                // Lỗi quyền => bỏ luôn, tránh retry vô hạn
                uploadedIds.add(loc.id)
                Log.e("LocationRepository", "🚫 Unauthorized, dropping pending $loc", e)
            } catch (e: Exception) {
                // Lỗi mạng => giữ lại để retry sau
                hasNetworkError = true
                Log.e("LocationRepository", "🌐 Network error, keep pending $loc", e)
            }
        }

        if (uploadedIds.isNotEmpty()) {
            dao.deleteLocations(uploadedIds)
        }

        return !hasNetworkError
    }
}
