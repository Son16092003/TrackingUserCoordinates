package com.plcoding.backgroundlocationtracking.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(location: PendingLocation)

    @Query("SELECT * FROM pending_locations ORDER BY timestamp ASC")
    suspend fun getAllLocations(): List<PendingLocation>

    @Query("DELETE FROM pending_locations WHERE id IN (:ids)")
    suspend fun deleteLocations(ids: List<Int>)
}
