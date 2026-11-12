package com.sickworm.jugg.demo.testcase.butterknife

import android.widget.TextView
import butterknife.BindView as BV
import butterknife.OnClick
import com.example.myapplication.R

/** Exercises Kotlin aliases, use-site fields and listener generation. */
class KotlinBindings {
    @field:BV(R.id.butterknife_kotlin_title)
    @JvmField var title: TextView? = null

    @OnClick(R.id.butterknife_kotlin_title)
    fun clicked() {
        title!!.text = "Kotlin listener updated"
    }
}
