package com.ledgerx.data.entities

import androidx.room.*
import kotlinx.serialization.*

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String,
    @SerialName("icon") val icon: String,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("sort_order") val sortOrder: Int
)
