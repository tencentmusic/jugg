package com.sickworm.intellij.jugg.hotfix;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;

public class IncrementalCompile {

    public static final String TAG = "IncrementalCompile";
    public static final String INCREMENTAL_DIR_NAME = "incremental_compile";

    private static volatile IncrementalCompile instance;
    private static final String INCREMENTAL_BASE_APK_STAMP_NAME = "com.tencent.compile.incremental.runtime.BaseApkStamp";
    private static volatile String baseApkStamp = "";
    private static volatile boolean isCIBuild = false;
    private static final String CI_TAG_FILE = "ci.mk";
    private static final String FULL_APK = "FULL_APK";
    private static String packagerName="";

    public static IncrementalCompile get() {
        if (instance == null) {
            synchronized (IncrementalCompile.class) {
                if (instance == null) {
                    instance = new IncrementalCompile();
                }
            }
        }
        return instance;
    }

    private IncrementalCompile() {}

    public void install(Application application, final Context base) {

        boolean shouldInstallInc = true;

        ApplicationInfo applicationInfo ;
        try {
            applicationInfo = base.getPackageManager().getApplicationInfo(base.getPackageName(),
                    PackageManager.GET_META_DATA);
            if (applicationInfo != null) {
                baseApkStamp = applicationInfo.metaData.getString(INCREMENTAL_BASE_APK_STAMP_NAME);
                packagerName = applicationInfo.packageName;
            }

            try {
                base.getAssets().open(CI_TAG_FILE);
                isCIBuild = true;
            } catch (Exception ex) {

                isCIBuild = false;

                try {

                    shouldInstallInc =
                            base.getSharedPreferences(TAG,0).getBoolean(FULL_APK,false) || isFirstInstallFromLocal();


                    L.i(TAG, "shouldInstallInc : "+shouldInstallInc +",packagerName:"+packagerName);

                    base.getSharedPreferences(TAG,0).edit().putBoolean(FULL_APK,true).commit();

                } catch (Exception noLocalMk) {
                    shouldInstallInc = false;
                }
            }

            L.i(TAG, "baseApkStamp : "+baseApkStamp+",isCIBuild: "+isCIBuild);

        } catch (Exception e) {
            throw  new RuntimeException("IncrementalCompile ");
        }

        if(!shouldInstallInc) {
            L.i(TAG, "full compiled APK ，skipInstall");
            return;
        }

        installDexPatches(application, base);
        installResources(application, base);
    }


    /**
     * Used to install incremental-compile patches dex files  while runtime
     * which would process ONLY ONCE on multiple invoke
     */
    private void installDexPatches(Application application, Context base) {
        L.i(TAG, "installDexPatches start...");
        new DexPatchLoader(base).install();
        L.i(TAG, "installDexPatches finish.");
    }

    /**
     * Used to install incremental-compile patches resources files  while runtime
     * which could be process several times on multiple invoke
     */
    void installResources(Application application, Context base) {
        L.i(TAG, "installResources start...");
        ResourcesPatchLoader.create(application, base).install();
        L.i(TAG, "installResources finish.");
    }

    public static File rootDir() {
        return new File("/data/local/tmp/lightning/"+packagerName+"/"+baseApkStamp); // or Environment
        // .getExternalStorageDirectory()
    }

    private boolean isFirstInstallFromLocal() {
        File localMark =
                new File("/data/local/tmp/lightning/"+packagerName+"/"+baseApkStamp+"/"+IncrementalCompile.INCREMENTAL_DIR_NAME+"/dex/local");

        L.i(TAG, "localMark : "+localMark.getAbsolutePath()+",exist: "+localMark.exists());

        if(localMark.exists()) {
            boolean delete = localMark.delete();
            L.i(TAG, "localMark :"+ delete);

            return true;
        }
        return false;
    }

    public static String baseApkStamp() {
        return baseApkStamp;
    }

    public static boolean isCIBuild() {
        return isCIBuild;
    }
}
