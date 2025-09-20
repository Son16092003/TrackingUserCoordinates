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
        Log.d("LocationSyncWorker", "Worker started: syncing pending locations...")

        return try {
            val success = repository.syncPendingLocations()

            if (success) {
                Log.d("LocationSyncWorker", "Worker finished: sync success ✅")
                Result.success()
            } else {
                Log.w("LocationSyncWorker", "Some locations not synced (temporary issue), will retry")
                Result.retry() // Lỗi tạm thời -> retry
            }
        } catch (e: IOException) {
            // 👉 Lỗi mạng (mất mạng, timeout) => retry
            Log.e("LocationSyncWorker", "Network error, will retry later 🌐", e)
            Result.retry()
        } catch (e: Exception) {
            // 👉 Lỗi khác (thường là lỗi 4xx do dữ liệu sai) => không retry nữa
            Log.e("LocationSyncWorker", "Unrecoverable error, will fail ❌", e)
            Result.failure()
        }
    }
}
