package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "profiles_cache")
data class ProfileCacheEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("updated_at") val updatedAt: Long
)
