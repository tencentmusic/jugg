package com.sickworm.jugg.demo.testcase.mcp

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R

/**
 * Manual UI page for MCP action tests described in 08_mcp_test_case.md.
 */
class McpTestActivity : AppCompatActivity() {

    private lateinit var groupSummaryTextView: TextView
    private lateinit var actionStateTextView: TextView
    private lateinit var swipeItemContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mcp_test)

        groupSummaryTextView = findViewById(R.id.tv_mcp_case_group_summary)
        actionStateTextView = findViewById(R.id.tv_mcp_action_state)
        swipeItemContainer = findViewById(R.id.layout_mcp_swipe_items)

        renderGroupSummary()
        renderSwipeVerificationArea()
        bindActionButtons()
    }

    private fun renderGroupSummary() {
        groupSummaryTextView.text = McpCaseCatalog.groups.joinToString(separator = "\n") { group ->
            "Group ${group.groupId}: ${group.title} (${group.startCaseId}~${group.endCaseId})"
        }
    }

    private fun bindActionButtons() {
        bindResultButton(R.id.btn_mcp_unique_text, "Unique MCP Target")
        bindResultButton(R.id.btn_mcp_resource_target, "Resource Tap Target")
        bindResultButton(R.id.btn_mcp_repeat_a, "Repeat Tap Target")
        bindResultButton(R.id.btn_mcp_repeat_b, "Repeat Tap Target")
        bindResultButton(R.id.btn_mcp_visibility_visible, "Visibility Tap Target")
    }

    private fun renderSwipeVerificationArea() {
        swipeItemContainer.removeAllViews()
        McpSwipeCaseSpec.buildScrollableLabels().forEachIndexed { index, label ->
            swipeItemContainer.addView(createSwipeLabelView(index, label))
        }
    }

    private fun createSwipeLabelView(index: Int, label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setBackgroundResource(if (index % 2 == 0) android.R.color.darker_gray else android.R.color.transparent)
        }
    }

    private fun bindResultButton(buttonId: Int, label: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            actionStateTextView.text = "Clicked: $label"
        }
    }
}
