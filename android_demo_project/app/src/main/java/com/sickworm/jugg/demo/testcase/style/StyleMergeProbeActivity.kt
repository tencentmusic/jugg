package com.sickworm.jugg.demo.testcase.style

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.myapplication.R

/** Manual resource-only probes. Reopen this page after each incremental deployment. */
class StyleMergeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "Style merge probes\nConfiguration: ${resources.configuration}\n" +
                "Change one probe XML, deploy, then reopen this page. See the task guide."
        })
        content.addView(Button(this).apply {
            text = "Recreate and read resources again"
            setOnClickListener { recreate() }
        })
        addProbe(content, "1 Framework item", R.style.StyleProbeFramework)
        addProbe(content, "2 Local item", R.style.StyleProbeLocal)
        addProbe(content, "3 Delete item", R.style.StyleProbeDelete)
        addProbe(content, "4 Remove parent", R.style.StyleProbeParentRemoval)
        addProbe(content, "5 Configuration", R.style.StyleProbeConfiguration)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun addProbe(content: LinearLayout, label: String, styleId: Int) {
        // A fresh theme excludes application-theme fallback from the diagnostic values.
        val theme = resources.newTheme().apply { applyStyle(styleId, true) }
        val attrs = theme.obtainStyledAttributes(intArrayOf(
            android.R.attr.textColor, android.R.attr.textSize, R.attr.styleProbeToken
        ))
        val summary = try {
            val color = if (attrs.hasValue(0)) {
                String.format("#%08X", attrs.getColor(0, 0))
            } else "unset"
            val size = if (attrs.hasValue(1)) "${attrs.getDimension(1, 0f)}px" else "unset"
            val token = if (attrs.hasValue(2)) attrs.getInt(2, -1).toString() else "unset"
            "$label: color=$color, size=$size, token=$token"
        } finally {
            attrs.recycle()
        }
        Log.i("StyleMergeProbe", summary)
        content.addView(TextView(this).apply { text = summary })
        content.addView(TextView(ContextThemeWrapper(this, styleId), null, 0).apply {
            text = "$label preview"
        })
    }
}
