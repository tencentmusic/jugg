package com.sickworm.intellij.jugg.ide

class JuggMoreOptionsItem(
    val name: String,
    private val onGet: () -> Boolean,
    private val onSet: (Boolean) -> Unit,
) {

    var isSelected: Boolean
        get() = onGet()
        set(value) {
            onSet(value)
        }

    companion object {

        private val confirmOnWhenOnFileChanges = JuggMoreOptionsItem(
            name = "Confirm fallback when no file changes",
            { JuggSettings.isConfirmFallbackWhenNoFileChanges },
            { JuggSettings.isConfirmFallbackWhenNoFileChanges = it }
        )

        val options = listOf(
            confirmOnWhenOnFileChanges
        )
    }
}