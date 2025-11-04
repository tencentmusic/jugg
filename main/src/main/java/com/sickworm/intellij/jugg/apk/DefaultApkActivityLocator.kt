/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo
import com.sickworm.intellij.jugg.apk.manifest.NodeActivity
import java.util.stream.Collectors

/**
 * Copied from DefaultApkActivityLocator in Android Plugin
 */
class DefaultApkActivityLocator(val logger: Logger) {

    fun computeDefaultActivityFromApks(manifest: ManifestActivityInfo): String? {
        val activities = manifest.activities()
        val defaultActivityName = computeDefaultActivity(activities)

        // Useful information to investigate bug reports
        if (defaultActivityName == null) {
            val errorMessage = StringBuilder("Unable to find Default Activity in:\n")
            printActivities(activities, errorMessage)
            logger.info(errorMessage.toString())
        }
        return defaultActivityName
    }

    private fun printActivities(activities: List<NodeActivity>, message: StringBuilder) {
        for (activity in activities) {
            message.append("  ${activity.qualifiedName}:\n")
            for (intent in activity.intentFilters) {
                for (action in intent.actions) {
                    message.append("    $action\n")
                }
                for (category in intent.categories) {
                    message.append("    $category\n")
                }
            }
        }
    }

    private fun computeDefaultActivity(activities: List<NodeActivity>): String? {
        val launchableActivities = getLaunchableActivities(activities)
        if (launchableActivities.isEmpty()) {
            return null
        } else if (launchableActivities.size == 1) {
            return launchableActivities[0].realActivityQname
        }

        // Prefer the launcher which has the CATEGORY_DEFAULT intent filter.
        // There is no such rule, but since Context.startActivity() prefers such activities, we do the same.
        // https://code.google.com/p/android/issues/detail?id=67068
        val defaultLauncher = findDefaultLauncher(launchableActivities)
        return if (defaultLauncher != null) {
            defaultLauncher.realActivityQname
        } else launchableActivities[0].realActivityQname

        // Just return the first one we find
    }

    private fun getLaunchableActivities(allActivities: List<NodeActivity>): List<NodeActivity> {
        val launchableActivities = allActivities
            .stream()
            .filter { activity: NodeActivity ->
                containsLauncherIntent(
                    activity
                ) && activity.isEnabled
            }
            .collect(Collectors.toList())
        if (launchableActivities.isEmpty() && logger.isDebugEnabled()) {
            logger.debug("No launchable activities found, total # of activities: " + allActivities.size)
            allActivities
                .forEach { wrapper: NodeActivity ->
                    logger.debug(
                        String.format(
                            "activity: %1\$s, isEnabled: %2\$s, containsLauncherIntent: %3\$s",
                            wrapper.qualifiedName,
                            wrapper.isEnabled,
                            containsLauncherIntent(wrapper)
                        )
                    )
                }
        }
        return launchableActivities
    }

    private fun findDefaultLauncher(launcherActivities: List<NodeActivity>): NodeActivity? {
        for (activity in launcherActivities) {
            if (activity.hasCategory("android.intent.category.DEFAULT")) {
                return activity
            }
        }
        return null
    }

    private fun containsLauncherIntent(activity: NodeActivity): Boolean {
        return activity.hasAction("android.intent.action.MAIN") &&
                (activity.hasCategory("android.intent.category.LAUNCHER") ||
                        activity.hasCategory("android.intent.category.LEANBACK_LAUNCHER"))
    }

}