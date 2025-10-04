package com.plcoding.backgroundlocationtracking.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plcoding.backgroundlocationtracking.data.LocationRepository
import java.io.IOException

class LocationSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = LocationRepository(context)

    override suspend fun doWork(): Result {
        Log.d("LocationSyncWorker", "🚀 Worker started id=$id: syncing pending locations...")

        return try {
            val success = repository.syncPendingLocations()

            if (success) {
                Log.d("LocationSyncWorker", "✅ Worker finished: sync success (id=$id)")
                Result.success()
            } else {
                Log.w("LocationSyncWorker", "⚠️ Some locations not synced (temporary issue), will retry (id=$id)")
                Result.retry()
            }
        } catch (e: IOException) {
            // 👉 Lỗi mạng (mất mạng, timeout) => retry với backoff
            Log.e("LocationSyncWorker", "🌐 Network error, will retry later (id=$id)", e)
            Result.retry()
        } catch (e: Exception) {
            // 👉 Lỗi khác (thường là 4xx) => không retry nữa
            Log.e("LocationSyncWorker", "❌ Unrecoverable error, will fail (id=$id)", e)
            Result.failure()
        }
    }
}

