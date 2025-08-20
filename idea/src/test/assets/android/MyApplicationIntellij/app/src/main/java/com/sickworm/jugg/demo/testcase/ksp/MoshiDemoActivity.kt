package com.sickworm.jugg.demo.testcase.ksp

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class MoshiDemoActivity : AppCompatActivity() {

    private lateinit var tvJsonOutput: TextView
    private lateinit var tvObjectOutput: TextView
    private lateinit var btnSerialize: Button
    private lateinit var btnDeserialize: Button
    private lateinit var btnSerializeList: Button

    // Create Moshi instance using KSP generated adapters
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory()) // Add Kotlin support
        .build()

    // Get adapters (KSP will automatically generate these adapters)
    private val userAdapter = moshi.adapter(User::class.java)
    private val userListAdapter = moshi.adapter(UserListResponse::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_moshi_demo)

        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        tvJsonOutput = findViewById(R.id.tv_json_output)
        tvObjectOutput = findViewById(R.id.tv_object_output)
        btnSerialize = findViewById(R.id.btn_serialize)
        btnDeserialize = findViewById(R.id.btn_deserialize)
        btnSerializeList = findViewById(R.id.btn_serialize_list)
    }

    private fun setupClickListeners() {
        btnSerialize.setOnClickListener {
            demonstrateObjectToJson()
        }

        btnDeserialize.setOnClickListener {
            demonstrateJsonToObject()
        }

        btnSerializeList.setOnClickListener {
            demonstrateListSerialization()
        }
    }

    /**
     * Demonstrate object serialization to JSON
     */
    @SuppressLint("SetTextI18n")
    private fun demonstrateObjectToJson() {
        val user = User(
            userName = "John Doe",
            age = 28,
            isActive = true,
            avatar = "https://example.com/avatar.jpg",
            profile = UserProfile(
                bio = "Software Engineer",
                location = "Beijing",
                createdAt = "2023-01-15T10:30:00Z"
            )
        )

        try {
            val json = userAdapter.toJson(user)
            tvJsonOutput.text = "Serialization Result:\n$json"
            tvObjectOutput.text = "Original Object:\n$user"
        } catch (e: Exception) {
            tvJsonOutput.text = "Serialization Failed: ${e.message}"
        }
    }

    /**
     * Demonstrate JSON deserialization to object
     */
    @SuppressLint("SetTextI18n")
    private fun demonstrateJsonToObject() {
        val jsonString = """
            {
                "user_id": "67890",
                "user_name": "Jane Smith",
                "email": "janesmith@example.com",
                "age": 32,
                "is_active": false,
                "avatar": null,
                "profile": {
                    "bio": "Product Manager",
                    "location": "Shanghai",
                    "created_at": "2022-08-20T14:45:00Z"
                }
            }
        """.trimIndent()

        try {
            val user = userAdapter.fromJson(jsonString)
            tvJsonOutput.text = "Original JSON:\n$jsonString"
            tvObjectOutput.text = "Deserialization Result:\n$user"
        } catch (e: Exception) {
            tvObjectOutput.text = "Deserialization Failed: ${e.message}"
        }
    }

    /**
     * Demonstrate list serialization
     */
    @SuppressLint("SetTextI18n")
    private fun demonstrateListSerialization() {
        val users = listOf(
            User(
                userName = "Alice Wang",
                age = 25,
                isActive = true,
                profile = UserProfile(
                    bio = "Frontend Developer",
                    location = "Shenzhen",
                    createdAt = "2023-03-10T09:15:00Z"
                )
            ),
            User(
                userName = "Bob Zhang",
                age = 30,
                isActive = true,
                profile = UserProfile(
                    bio = "Backend Developer",
                    location = "Hangzhou",
                    createdAt = "2023-02-05T16:20:00Z"
                )
            )
        )

        val userListResponse = UserListResponse(
            users = users,
            total = users.size,
            page = 1
        )

        try {
            val json = userListAdapter.toJson(userListResponse)
            tvJsonOutput.text = "List Serialization Result:\n$json"
            tvObjectOutput.text = "Original List Object:\n$userListResponse"
        } catch (e: Exception) {
            tvJsonOutput.text = "List Serialization Failed: ${e.message}"
        }
    }
}