package com.sickworm.intellij.jugg.instrument;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.junit.After;
import org.junit.Test;

public class ApplyChangesOverlayPolicyTest {

    @After
    public void tearDown() {
        ApplyChangesOverlayPolicy.clearHostApplicationInfo();
    }

    @Test
    public void hostApkResourcesShouldKeepApplyChangesOverlay() {
        ApplyChangesOverlayPolicy.recordHostApplicationInfo(new FakeApplicationInfo(
                "/data/app/host/base.apk",
                new String[]{"/data/app/host/split_config.apk"},
                "/data/app/host/base.apk"));

        assertFalse(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/data/app/host/base.apk")));
        assertFalse(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/data/app/host/split_config.apk")));
    }

    @Test
    public void otherDataAppResourcesShouldRemoveHostApplyChangesOverlay() {
        ApplyChangesOverlayPolicy.recordHostApplicationInfo(new FakeApplicationInfo(
                "/data/app/host/base.apk",
                null,
                "/data/app/host/base.apk"));

        assertTrue(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/data/app/webview/base.apk")));
    }

    @Test
    public void systemStandaloneResourcesShouldStillRemoveApplyChangesOverlay() {
        ApplyChangesOverlayPolicy.recordHostApplicationInfo(new FakeApplicationInfo(
                "/data/app/host/base.apk",
                null,
                "/data/app/host/base.apk"));

        assertTrue(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/system/framework/framework-res.apk")));
    }

    @Test
    public void missingHostInfoShouldKeepOldDataAppBehavior() {
        assertFalse(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/data/app/webview/base.apk")));
        assertTrue(ApplyChangesOverlayPolicy.shouldRemoveApplyChangesOverlay(
                new FakeResourcesKey("/system/framework/framework-res.apk")));
    }

    public static class FakeApplicationInfo {
        public String sourceDir;
        public String[] splitSourceDirs;
        public String publicSourceDir;

        FakeApplicationInfo(String sourceDir, String[] splitSourceDirs, String publicSourceDir) {
            this.sourceDir = sourceDir;
            this.splitSourceDirs = splitSourceDirs;
            this.publicSourceDir = publicSourceDir;
        }
    }

    public static class FakeResourcesKey {
        public String mResDir;

        FakeResourcesKey(String resDir) {
            this.mResDir = resDir;
        }
    }
}
