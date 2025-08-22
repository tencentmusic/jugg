package com.sickworm.jugg.demo.testcase.ksp

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Moshi data class example
 * @JsonClass(generateAdapter = true) to generate adapter by ksp
 */
@JsonClass(generateAdapter = true)
data class User(
//    val user_id: String = "",

    @Json(name = "user_name")
    val userName: String,
    
    val age: Int,

    @Json(name = "is_active")
    val isActive: Boolean,
    
    val avatar: String? = null,
    
    val profile: UserProfile? = null
)

@JsonClass(generateAdapter = true)
data class UserProfile(
    val bio: String,
    val location: String,
    @Json(name = "created_at")
    val createdAt: String
)

@JsonClass(generateAdapter = true)
data class UserListResponse(
    val users: List<User>,
    val total: Int,
    val page: Int
)