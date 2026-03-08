package com.sickworm.intellij.jugg.deploy

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileEffectAnalyzerTest {

    @Test
    fun `isBootClasspathClass returns true for java stdlib classes`() {
        assertTrue("Ljava/lang/Object;".isBootClasspathClass)
        assertTrue("Ljava/lang/String;".isBootClasspathClass)
        assertTrue("Ljava/util/ArrayList;".isBootClasspathClass)
        assertTrue("Ljava/lang/Exception;".isBootClasspathClass)
        assertTrue("Ljava/lang/ref/WeakReference;".isBootClasspathClass)
        assertTrue("Ljava/lang/System;".isBootClasspathClass)
        assertTrue("Ljava/util/Objects;".isBootClasspathClass)
        assertTrue("Ljava/util/Iterator;".isBootClasspathClass)
        assertTrue("Ljava/util/List;".isBootClasspathClass)
        assertTrue("Ljava/lang/Integer;".isBootClasspathClass)
        assertTrue("Ljava/lang/Boolean;".isBootClasspathClass)
        assertTrue("Ljava/lang/Class;".isBootClasspathClass)
        assertTrue("Ljava/lang/StringBuilder;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns true for android SDK classes`() {
        assertTrue("Landroid/view/View;".isBootClasspathClass)
        assertTrue("Landroid/widget/Toast;".isBootClasspathClass)
        assertTrue("Landroid/os/Bundle;".isBootClasspathClass)
        assertTrue("Landroid/content/Context;".isBootClasspathClass)
        assertTrue("Landroid/content/Intent;".isBootClasspathClass)
        assertTrue("Landroid/content/IntentFilter;".isBootClasspathClass)
        assertTrue("Landroid/content/res/Resources;".isBootClasspathClass)
        assertTrue("Landroid/os/Handler;".isBootClasspathClass)
        assertTrue("Landroid/os/SystemClock;".isBootClasspathClass)
        assertTrue("Landroid/text/TextUtils;".isBootClasspathClass)
        assertTrue("Landroid/view/LayoutInflater;".isBootClasspathClass)
        assertTrue("Landroid/view/ViewGroup;".isBootClasspathClass)
        assertTrue("Landroid/view/Window;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/AlphaAnimation;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/ScaleAnimation;".isBootClasspathClass)
        assertTrue("Landroid/view/animation/AnimationSet;".isBootClasspathClass)
        assertTrue("Landroid/widget/ImageView;".isBootClasspathClass)
        assertTrue("Landroid/widget/RelativeLayout;".isBootClasspathClass)
        assertTrue("Landroid/widget/RelativeLayout\$LayoutParams;".isBootClasspathClass)
        assertTrue("Landroid/app/ActivityManager;".isBootClasspathClass)
        assertTrue("Landroid/app/ActivityManager\$MemoryInfo;".isBootClasspathClass)
        assertTrue("Landroid/provider/Settings;".isBootClasspathClass)
        assertTrue("Landroid/provider/Settings\$Global;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns true for javax and dalvik classes`() {
        assertTrue("Ljavax/crypto/Cipher;".isBootClasspathClass)
        assertTrue("Ljavax/net/ssl/SSLContext;".isBootClasspathClass)
        assertTrue("Ldalvik/system/DexFile;".isBootClasspathClass)
        assertTrue("Ldalvik/system/PathClassLoader;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for androidx classes`() {
        assertFalse("Landroidx/fragment/app/Fragment;".isBootClasspathClass)
        assertFalse("Landroidx/lifecycle/LiveData;".isBootClasspathClass)
        assertFalse("Landroidx/lifecycle/ViewModelProvider;".isBootClasspathClass)
        assertFalse("Landroidx/constraintlayout/widget/ConstraintLayout\$LayoutParams;".isBootClasspathClass)
        assertFalse("Landroidx/fragment/app/FragmentManager;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for app classes`() {
        assertFalse("Lcom/example/MyActivity;".isBootClasspathClass)
        assertFalse("Lcom/tencent/wemusic/ui/main/activity/MainTabActivity;".isBootClasspathClass)
        assertFalse("Lcom/tencent/wemusic/ui/main/utils/ListenGuideReportUtils;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for kotlin stdlib classes`() {
        assertFalse("Lkotlin/collections/CollectionsKt;".isBootClasspathClass)
        assertFalse("Lkotlinx/coroutines/Dispatchers;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for obfuscated classes`() {
        assertFalse("Lxxx/oig;".isBootClasspathClass)
        assertFalse("Lxxx/eig;".isBootClasspathClass)
        assertFalse("Lxxx/iig;".isBootClasspathClass)
    }

    @Test
    fun `isBootClasspathClass returns false for third party library classes`() {
        assertFalse("Lcom/google/android/material/tabs/TabLayout;".isBootClasspathClass)
        assertFalse("Lcom/appsflyer/MultipleInstallBroadcastReceiver;".isBootClasspathClass)
    }
}
