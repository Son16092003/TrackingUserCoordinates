package com.plcoding.backgroundlocationtracking.data

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)
