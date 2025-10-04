package com.plcoding.backgroundlocationtracking.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PendingLocation::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun locationDao(): LocationDao
}
