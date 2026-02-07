package com.example.myapplication.model

/**
 * Test User class for DataBinding source change test (Kotlin version)
 * This is the MODIFIED version with changed field names
 */
data class UserKt(
    // Changed from 'name' to 'userName'
    val userName: String,

    // Changed from 'age' to 'userAge'
    val userAge: Int
)
