package com.sickworm.intellij.jugg.compiler.manifest

import com.sickworm.intellij.jugg.mock.assetsDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

open class ManifestDifferTest {

    val mergedFile = File(assetsDir, "android/manifest/merged.xml")
    private val mergedFileText = mergedFile.readText()

    @Test
    open fun testFileEquals() {
        val changedManifestFile = ChangedManifestFile(mergedFile, mergedFile)
        val diffResult = ManifestDiffer().diff(changedManifestFile)
        val diffContent = diffResult.diffElement.toXmlString()
        println(diffContent)
        assertEquals("<manifest>\n</manifest>".trimLines(), diffContent.trimLines())
    }

    @Test
    fun testEquals() {
        diff(
            newXml = mergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = "<manifest>\n</manifest>",
        )
    }


    @Test
    fun testEmpty() {
        diff(
            newXml = "<manifest></manifest>",
            oldXml = "<manifest></manifest>",
            expectDiffResult = "<manifest>\n</manifest>",
        )
    }

    @Test
    fun testNewAttributeInMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="android.max_aspect"""",
            """android:name="android.max_aspect" android:newAttr="abc"""",
            )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <meta-data android:newAttr="abc">
                    </meta-data>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewAttributeInActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="com.sickworm.intellij.jugg.connect.common.AssistActivity"""",
            """android:name="com.sickworm.intellij.jugg.connect.common.AssistActivity" android:noHistory="true"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <activity android:noHistory="true">
                    </activity>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewAttributeInIntentFilterData() {
        val newMergedFileText = mergedFileText.replace(
            """android:scheme="110156298833"""",
            """android:scheme="110156298833" android:host="google.com"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // currently we mark changed-no-name node as new node e.g. <intent> <intent-filter>
            expectDiffResult = """
            <manifest>
              <application>
                <activity>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW">
                    </action>
                    <category android:name="android.intent.category.DEFAULT">
                    </category>
                    <category android:name="android.intent.category.BROWSABLE">
                    </category>
                    <data android:host="google.com" android:scheme="110156298833">
                    </data>
                  </intent-filter>
                </activity>
              </application>
            </manifest>
            """
        )
    }

    @Test
    fun testModifyAttributeInMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:value="2.4"""",
            """android:value="2.5"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <meta-data android:value="2.5">
                    </meta-data>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testModifyAttributeNameInMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="android.max_aspect"""",
            """android:name="android.max_aspect2"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // change android:name will mark as new node
            expectDiffResult = """
            <manifest>
                <application>
                    <meta-data android:name="android.max_aspect2" android:value="2.4">
                    </meta-data>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testModifyAttributeInActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:theme="@style/com_facebook_activity_theme"""",
            """android:theme="@style/com_facebook_activity_theme2"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <activity android:theme="@style/com_facebook_activity_theme2">
                    </activity>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testModifyAttributeNameInActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="com.appsflyer.MultipleInstallBroadcastReceiver"""",
            """android:name="com.appsflyer.MultipleInstallBroadcastReceiver2"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // change android:name will mark as new node
            expectDiffResult = """
            <manifest>
                <application>
                    <receiver android:exported="true" android:name="com.appsflyer.MultipleInstallBroadcastReceiver2">
                        <intent-filter>
                            <action android:name="com.android.vending.INSTALL_REFERRER">
                            </action>
                        </intent-filter>
                    </receiver>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testModifyAttributeInIntentFilterData() {
        val newMergedFileText = mergedFileText.replace(
            """android:scheme="110156298833"""",
            """android:scheme="110156298834"""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // currently we mark changed-no-name node as new node e.g. <intent> <intent-filter>
            expectDiffResult = """
            <manifest>
              <application>
                <activity>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW">
                    </action>
                    <category android:name="android.intent.category.DEFAULT">
                    </category>
                    <category android:name="android.intent.category.BROWSABLE">
                    </category>
                    <data android:scheme="110156298834">
                    </data>
                  </intent-filter>
                </activity>
              </application>
            </manifest>
            """
        )
    }

    @Test
    fun testRemoveAttributeInMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:value="2.4"""",
            """""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // ignore remove
            expectDiffResult = """
            <manifest>
            </manifest>
            """
        )
    }

    @Test
    fun testRemoveAttributeNameInMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="android.max_aspect"""",
            """""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // name missing, mark it as new node
            expectDiffResult = """
            <manifest>
              <application>
                <meta-data android:value="2.4">
                </meta-data>
              </application>
            </manifest>
            """
        )
    }

    @Test
    fun testRemoveAttributeInActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:theme="@style/com_facebook_activity_theme"""",
            """""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // ignore remove
            expectDiffResult = """
            <manifest>
            </manifest>
            """
        )
    }

    @Test
    fun testRemoveAttributeNameInActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="com.appsflyer.MultipleInstallBroadcastReceiver"""",
            """""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // name missing, mark it as new node
            expectDiffResult = """
            <manifest>
              <application>
                <receiver android:exported="true">
                  <intent-filter>
                    <action android:name="com.android.vending.INSTALL_REFERRER">
                    </action>
                  </intent-filter>
                </receiver>
              </application>
            </manifest>
            """
        )
    }

    @Test
    fun testRemoveAttributeInIntentFilterData() {
        val newMergedFileText = mergedFileText.replace(
            """android:scheme="110156298833"""",
            """""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // currently we mark changed-no-name node as new node e.g. <intent> <intent-filter>
            expectDiffResult = """
            <manifest>
              <application>
                <activity>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW">
                    </action>
                    <category android:name="android.intent.category.DEFAULT">
                    </category>
                    <category android:name="android.intent.category.BROWSABLE">
                    </category>
                    <data>
                    </data>
                  </intent-filter>
                </activity>
              </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewMetadata() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="isMidasGdprOn"""",
            """
                    android:name="android.vendor.full_screen2"
                    android:value="123" />
                <meta-data
                        android:name="isMidasGdprOn"
                """
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <meta-data android:name="android.vendor.full_screen2" android:value="123">
                    </meta-data>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewActivity() {
        val newMergedFileText = mergedFileText.replace(
            """android:name="com.sickworm.intellij.jugg.connect.common.AssistActivity"""",
            """
                android:name="com.sickworm.intellij.jugg.intent.IntentHandleActivity2"
                android:configChanges="keyboardHidden|orientation|screenSize|screenLayout|uiMode"
                android:exported="true"
                android:launchMode="singleTask"
                android:screenOrientation="portrait"
                android:theme="@style/AppTheme.Launcher"
                android:windowSoftInputMode="adjustPan" >
                <intent-filter android:autoVerify="true" >
                    <action android:name="android.intent.action.VIEW" />
    
                    <category android:name="android.intent.category.DEFAULT" />
                    <category android:name="android.intent.category.BROWSABLE" />
    
                    <data
                            android:host="jugg.onelink.me"
                            android:pathPrefix="/JAsNjkf"
                            android:scheme="https" />
                </intent-filter>
                <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
                    <action android:name="com.sickworm.intellij.jugg.action.PUSH" />
                    <action android:name="com.sickworm.intellij.jugg.action.PLAYER" />
                    <action android:name="com.sickworm.intellij.jugg.action.LIVE_AND_ROOM" />
    
                    <category android:name="android.intent.category.DEFAULT" />
                    <category android:name="android.intent.category.BROWSABLE" />
    
                    <data android:scheme="jugg" />
                </intent-filter>
            </activity>
            <activity
                android:name="com.sickworm.intellij.jugg.connect.common.AssistActivity"
            """,
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            expectDiffResult = """
            <manifest>
                <application>
                    <activity android:configChanges="keyboardHidden|orientation|screenSize|screenLayout|uiMode" android:exported="true" android:launchMode="singleTask" android:name="com.sickworm.intellij.jugg.intent.IntentHandleActivity2" android:screenOrientation="portrait" android:theme="@style/AppTheme.Launcher" android:windowSoftInputMode="adjustPan">
                        <intent-filter android:autoVerify="true">
                            <action android:name="android.intent.action.VIEW">
                            </action>
                            <category android:name="android.intent.category.DEFAULT">
                            </category>
                            <category android:name="android.intent.category.BROWSABLE">
                            </category>
                            <data android:host="jugg.onelink.me" android:pathPrefix="/JAsNjkf" android:scheme="https">
                            </data>
                        </intent-filter>
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW">
                            </action>
                            <action android:name="com.sickworm.intellij.jugg.action.PUSH">
                            </action>
                            <action android:name="com.sickworm.intellij.jugg.action.PLAYER">
                            </action>
                            <action android:name="com.sickworm.intellij.jugg.action.LIVE_AND_ROOM">
                            </action>
                            <category android:name="android.intent.category.DEFAULT">
                            </category>
                            <category android:name="android.intent.category.BROWSABLE">
                            </category>
                            <data android:scheme="jugg">
                            </data>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewSameIntentFilter() {
        val newMergedFileText = mergedFileText.replace(
            """<data android:scheme="110156298833" />""",
            """<data android:scheme="110156298833" />
                </intent-filter>
                <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
    
                    <category android:name="android.intent.category.DEFAULT" />
                    <category android:name="android.intent.category.BROWSABLE" />
    
                    <data android:scheme="110156298833" />
            """,
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // ignore because it same as last node
            expectDiffResult = """
            <manifest>
            </manifest>
            """
        )
    }

    @Test
    fun testNewIntentFilter() {
        val newMergedFileText = mergedFileText.replace(
            """<data android:scheme="110156298833" />""",
            """<data android:scheme="110156298833" />
                </intent-filter>
                <intent-filter>
                    <action android:name="android.intent.action.VIEW" />
    
                    <category android:name="android.intent.category.DEFAULT" />
                    <category android:name="android.intent.category.BROWSABLE" />
    
                    <data android:scheme="110156298834" />
            """,
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // ignore because it same as last node
            expectDiffResult = """
            <manifest>
                <application>
                    <activity>
                      <intent-filter>
                        <action android:name="android.intent.action.VIEW">
                        </action>
                        <category android:name="android.intent.category.DEFAULT">
                        </category>
                        <category android:name="android.intent.category.BROWSABLE">
                        </category>
                        <data android:scheme="110156298834">
                        </data>
                      </intent-filter>
                    </activity>
                  </application>
            </manifest>
            """
        )
    }

    @Test
    fun testNewIntentFilterData() {
        val newMergedFileText = mergedFileText.replace(
            """<data android:scheme="110156298833" />""",
            """<data android:scheme="110156298833" /> <data android:scheme="110156298834" />""",
        )
        diff(
            newXml = newMergedFileText,
            oldXml = mergedFileText,
            // currently we mark changed-no-name node as new node e.g. <intent> <intent-filter>
            expectDiffResult = """
            <manifest>
              <application>
                <activity>
                  <intent-filter>
                    <action android:name="android.intent.action.VIEW">
                    </action>
                    <category android:name="android.intent.category.DEFAULT">
                    </category>
                    <category android:name="android.intent.category.BROWSABLE">
                    </category>
                    <data android:scheme="110156298833">
                    </data>
                    <data android:scheme="110156298834">
                    </data>
                  </intent-filter>
                </activity>
              </application>
            </manifest>
            """
        )
    }

    open fun diff(newXml: String, oldXml: String, expectDiffResult: String): ManifestDiffResult.DiffElement {
        val newNode = XmlParser().parse(newXml)
        val oldNode = XmlParser().parse(oldXml)
        val diffResult = ManifestDiffer().diff(newNode, oldNode)

        val startTime = System.currentTimeMillis()
        val diffContent = diffResult.toXmlString()
        val costTime = System.currentTimeMillis() - startTime
        println(diffContent)
        println("diff cost ${costTime}ms")

        assertEquals(expectDiffResult.trimLines(), diffContent.trimLines())
        return diffResult
    }

    private fun String.trimLines(): String {
        return this.trim().lines().joinToString("\n") { it.trim() }
    }

}