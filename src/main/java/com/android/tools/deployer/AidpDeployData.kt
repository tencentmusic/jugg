package com.android.tools.deployer

data class AidpDeployData(
    val changesClasses: DexComparator.ChangedClasses,
) {
    val isEmpty get() = changesClasses.modifiedClasses.size == 0 && changesClasses.newClasses.size == 0
}