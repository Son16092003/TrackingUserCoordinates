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

@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val deviceId: String,
    val userName: String
)

class LocationRepository(private val context: Context) {

    // Khởi tạo Room DB với fallback migration
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
            "📤 Trying to upload location: lat=$latitude, lon=$longitude, time=$timestamp, device=$resolvedDeviceId, user=$resolvedUserName"
        )

        try {
            // Upload trực tiếp
            client.from("locations").insert(
                LocationPayload(latitude, longitude, timestamp, resolvedDeviceId, resolvedUserName)
            )
            Log.d(
                "LocationRepository",
                "✅ Uploaded location successfully: lat=$latitude, lon=$longitude, device=$resolvedDeviceId, user=$resolvedUserName"
            )
        } catch (e: Exception) {
            Log.w(
                "LocationRepository",
                "⚠️ [UPLOAD FAILED] Saving locally -> device=$resolvedDeviceId, user=$resolvedUserName",
                e
            )
            try {
                val pending = PendingLocation(
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp,
                    deviceId = resolvedDeviceId,
                    userName = resolvedUserName
                )
                dao.insertLocation(pending)
                Log.d(
                    "LocationRepository",
                    "💾 Saved location locally for retry later: lat=$latitude, lon=$longitude, time=$timestamp, device=$resolvedDeviceId, user=$resolvedUserName"
                )
            } catch (e: Exception) {
                Log.e(
                    "LocationRepository",
                    "❌ [ROOM FAILED] Failed to insert pending location: lat=$latitude, lon=$longitude, device=$resolvedDeviceId, user=$resolvedUserName",
                    e
                )
            }
        }
    }

    suspend fun syncPendingLocations(): Boolean {
        val pending = dao.getAllLocations()
        if (pending.isEmpty()) {
            Log.d("LocationRepository", "ℹ️ No pending locations to sync")
            return true
        }

        var hasNetworkError = false
        val uploadedIds = mutableListOf<Int>()

        Log.d("LocationRepository", "🔄 Syncing ${pending.size} pending locations...")

        for (loc in pending) {
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
                Log.d("LocationRepository", "✅ Uploaded pending: $loc")
            } catch (e: UnauthorizedRestException) {
                uploadedIds.add(loc.id)
                Log.e("LocationRepository", "🚫 Unauthorized, dropping pending $loc", e)
            } catch (e: Exception) {
                hasNetworkError = true
                Log.e("LocationRepository", "🌐 Network error, keeping pending $loc", e)
            }
        }

        if (uploadedIds.isNotEmpty()) {
            dao.deleteLocations(uploadedIds)
            Log.d("LocationRepository", "🗑️ Deleted ${uploadedIds.size} uploaded pending locations")
        }

        return !hasNetworkError
    }
}
