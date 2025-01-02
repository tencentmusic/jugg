package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import java.lang.reflect.Type

class CompatDeployHelper(
    logger: Logger,
) {

    private val logger = logger.getInstance("CompatDeployHelper")

    companion object {
        var type: Type = object : TypeToken<List<CompatDeployRecord>?>() {}.type
    }

    private var records: List<CompatDeployRecord>
        get() {
            val json = JuggSettings.deviceCompatRecordJson
            return try {
                Gson().fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(value) {
            val json = Gson().toJson(value)
            JuggSettings.deviceCompatRecordJson = json
        }

    private fun isRecordedCompatDevice(device: IDeviceAdb): Boolean {
        return records.find { it.displayName == device.displayName } != null
    }

    fun isForceCompatDevice(device: IDeviceAdb): Boolean {
        val record = records.find { it.displayName == device.displayName }
        if (record == null) {
            return false
        }
        return record.applications.isNullOrEmpty()
    }

    fun isEnableCompatDeploy(device: IDeviceAdb, data: JuggDeployData): Boolean {
        if (device.api < IAsDeployerCompat.ANDROID_11_API) {
            // device not supports overlay swap, use compat deploy
            return true
        }
        val record = records.find { it.displayName == device.displayName }
        if (record == null) {
            return false
        }

        if (!record.applications.isNullOrEmpty()) {
            // record records applications, so only compat deploy when application match
            val isMatchApplication = record.applications.any { compatApplication ->
                data.apks.any { it.applicationId == compatApplication }
            }
            return isMatchApplication
        }
        return true
    }

    fun recordCompatDeviceRecord(device: IDeviceAdb, applications: List<String>? = null) {
        logger.debug("before record: $records")
        var newApplications = applications
        if (isRecordedCompatDevice(device) && !applications.isNullOrEmpty()) {
            // try combine applications
            val oldRecord = records.find { it.displayName == device.displayName }!!
            if (oldRecord.applications.isNullOrEmpty()) {
                // is already compat with all applications, no need to record
                return
            }
            newApplications = (oldRecord.applications + applications).distinct()
        }

        val newRecords = records.filter { it.displayName != device.displayName }.toMutableList()
        newRecords.add(CompatDeployRecord(device.displayName, newApplications))
        records = newRecords
        logger.debug("after record: $newRecords")
    }

    fun clearCompatDeviceRecord(device: IDeviceAdb, applications: List<String>? = null) {
        logger.debug("before clear record: $records")
        if (applications != null) {
            val oldRecord = records.find { it.displayName == device.displayName }
            if (oldRecord?.applications != null) {
                // try filter applications
                val newApplications = oldRecord.applications.filter { it !in applications }
                val newRecords = records.filter { it.displayName != device.displayName }.toMutableList()
                newRecords.add(CompatDeployRecord(device.displayName, newApplications))
                records = newRecords
                logger.debug("after clear record: $newRecords")
                return
            }
        }

        val newRecords = records.filter { it.displayName != device.displayName }.toMutableList()
        records = newRecords
        logger.debug("after clear record: $newRecords")
    }

    fun isHasRelaunchActivityIssues(device: IDeviceAdb): Boolean {
        // Android 15 has problem with relaunch activity
        val isAndroid15 = device.api >= 35
        logger.debug("isHasRelaunchActivityIssues, isAndroid15: $isAndroid15")
        return isAndroid15
    }
}

data class CompatDeployRecord(
    /** format: "${model} ${manufacturer}" */
    val displayName: String?,
    /** HyperOS(Xiaomi) only has problem with specific apps, so record it */
    val applications: List<String>? = null,
)