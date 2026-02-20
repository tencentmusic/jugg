package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import java.lang.reflect.Type

/**
 * SliceDeployHelper provides helper utilities for slice deploy.
 */
class SliceDeployHelper(
    logger: Logger,
) {

    private val logger = logger.getInstance("SliceDeployHelper")

    companion object {
        var type: Type = object : TypeToken<List<SliceDeployRecord>?>() {}.type
    }

    private var records: List<SliceDeployRecord>
        get() {
            val json = JuggSettings.sliceDeployRecordJson
            return try {
                Gson().fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val json = Gson().toJson(value)
            JuggSettings.sliceDeployRecordJson = json
        }

    fun get(device: IDeviceAdb): Pair<Int, Int> {
        var overlayDeploySplitSizeFirstSlice = JuggSettings.overlayDeploySplitSizeFirstSlice
        var overlayDeploySplitSize = JuggSettings.overlayDeploySplitSize

        val record = records.find { it.displayName == device.displayName }
        if (record != null) {
            if (record.firstSliceSize != null && record.sliceSize != null) {
                overlayDeploySplitSizeFirstSlice = record.firstSliceSize
                overlayDeploySplitSize = record.sliceSize
            }
        }

        return overlayDeploySplitSizeFirstSlice to overlayDeploySplitSize
    }

    fun onTimeout(device: IDeviceAdb) {
        val filteredRecords = records.filterNot { it.displayName == device.displayName }
        // just simply half the size of first slice and slice
        val record = SliceDeployRecord(device.displayName,
            JuggSettings.overlayDeploySplitSizeFirstSlice / 2,
            JuggSettings.overlayDeploySplitSize / 2)
        records = filteredRecords + record
    }
}

/**
 * SliceDeployRecord carries displayName, firstSliceSize, and sliceSize.
 */
data class SliceDeployRecord(
    /** format: "${model} ${manufacturer}" */
    val displayName: String?,
    val firstSliceSize: Int? = null,
    val sliceSize: Int? = null,
)
