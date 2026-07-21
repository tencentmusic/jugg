package com.sickworm.jugg.demo.testcase.applicationcontext

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize
import com.sickworm.jugg.demo.testcase.appcomponentfactory.TestInitialize.describe

/**
 * Logs the Context and Application instances visible during ContentProvider startup.
 */
class ApplicationContextProbeProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val providerContext = context
        val applicationContext = providerContext?.applicationContext
        val rawApplication = TestInitialize.application
        val reflectedRawApplication = BootstrapApplicationHelper.unwrap(applicationContext as? Application)
        Log.i(
            TAG,
            "onCreate providerContext=${describe(providerContext)}, " +
                "applicationContext=${describe(applicationContext)}, " +
                "rawApplication=${describe(rawApplication)}, " +
                "reflectedRawApplication=${describe(reflectedRawApplication)}, " +
                "contextIsRaw=${providerContext === rawApplication}, " +
                "applicationContextIsRaw=${applicationContext === rawApplication}, " +
                "reflectedRawIsRaw=${reflectedRawApplication === rawApplication}",
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "ApplicationContextProbe"
    }
}
