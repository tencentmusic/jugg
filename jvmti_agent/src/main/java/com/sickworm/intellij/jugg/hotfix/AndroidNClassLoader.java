package com.sickworm.intellij.jugg.hotfix;

import android.content.Context;
import android.content.res.Resources;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Refer from lightning :)
 * Used to replace origin PathClassLoader
 *
 * @author ethanfeng
 * @noinspection unused, deprecation
 */
class AndroidNClassLoader extends PathClassLoader {

    private static final String TAG = HotfixLoader.TAG + "#AndroidNClassLoader";
    /** @noinspection FieldCanBeLocal*/
    private static Object oldDexPathListHolder = null;
    private static String baseApkFullPath = null;

    private final BaseDexClassLoader originClassLoader;

    private AndroidNClassLoader(String dexPath, BaseDexClassLoader parent, Context base) {
        super(dexPath, parent.getParent());
        originClassLoader = parent;
        baseApkFullPath = base.getPackageCodePath();
    }

    @SuppressWarnings("unchecked")
    private static Object recreateDexPathList(Object originalDexPathList, ClassLoader newDefiningContext) throws Exception {

        final Constructor<?> dexPathListConstructor = ReflectUtil.findConstructor(originalDexPathList, ClassLoader.class, String.class, String.class, File.class);
        final Field dexElementsField = ReflectUtil.findField(originalDexPathList, "dexElements");
        final Object[] dexElements = (Object[]) dexElementsField.get(originalDexPathList);
        final Field nativeLibraryDirectoriesField = ReflectUtil.findField(originalDexPathList, "nativeLibraryDirectories");
        final List<File> nativeLibraryDirectories = (List<File>) nativeLibraryDirectoriesField.get(originalDexPathList);
        assert nativeLibraryDirectories != null;

        final StringBuilder dexPathBuilder = new StringBuilder();
        assert dexElements != null;
        final Field dexFileField = ReflectUtil.findField(dexElements.getClass().getComponentType(), "dexFile");

        boolean isFirstItem = true;
        for (Object dexElement : dexElements) {
            final DexFile dexFile = (DexFile) dexFileField.get(dexElement);
            if (dexFile == null || dexFile.getName() == null) {
                continue;
            }
            if (!dexFile.getName().equals(baseApkFullPath)) {
                continue;
            }
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                dexPathBuilder.append(File.pathSeparator);
            }
            dexPathBuilder.append(dexFile.getName());
        }

        final String dexPath = dexPathBuilder.toString();

        final StringBuilder libraryPathBuilder = new StringBuilder();
        isFirstItem = true;
        for (File libDir : nativeLibraryDirectories) {
            if (libDir == null) {
                continue;
            }
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                libraryPathBuilder.append(File.pathSeparator);
            }
            libraryPathBuilder.append(libDir.getAbsolutePath());
        }

        final String libraryPath = libraryPathBuilder.toString();
        return dexPathListConstructor.newInstance(newDefiningContext, dexPath, libraryPath, null);
    }

    /**
     * @url <a href="http://w4lle.com/2016/12/16/tinker/index.html">Tinker</a>
     */
    private static AndroidNClassLoader createAndroidNClassLoader(BaseDexClassLoader originalClassLoader, Context base) throws Exception {
        //let all element ""
        final AndroidNClassLoader androidNClassLoader = new AndroidNClassLoader("",  originalClassLoader, base);
        final Field pathListField = ReflectUtil.findField(originalClassLoader, "pathList");
        final Object originPathList = pathListField.get(originalClassLoader);
        assert originPathList != null;

        Object newPathList = recreateDexPathList(originPathList, androidNClassLoader);

        // Update new classloader's pathList.
        pathListField.set(androidNClassLoader, newPathList);

        // Change original classloader's definingContext to avoid potential class cast exception.
        //        //
        //        // Here's why we aren't going to recreate DexPathList with original classloader directly:
        //        //  To avoid 'dex file register with multiple classloader' exception on Android O, we must
        //        //  keep old dexPathList in original classloader so that we can still load classes in
        //        //  base dex from original classloader.
        ReflectUtil.findField(originPathList, "definingContext").set(originPathList, androidNClassLoader);

        // Keep old dexPathList to avoid gc issue.
        oldDexPathListHolder = originPathList;

        return androidNClassLoader;
    }

    private static void reflectPackageInfoClassloader(Context base, ClassLoader reflectClassLoader) throws Exception {
        Object basePackageInfo = ReflectUtil.findField(base, "mPackageInfo").get(base);
        assert basePackageInfo != null;
        ReflectUtil.findField(basePackageInfo, "mClassLoader").set(basePackageInfo, reflectClassLoader);

        // There's compatibility risk here when omit these hacking logic.
        // However I still have no idea about how to solve it without touching the Android P's
        // dark grey list API.
//        if (Build.VERSION.SDK_INT < 27) {
//            Resources res = base.getResources();
//            ReflectUtil.findField(res, "mClassLoader").set(res, reflectClassLoader);
//
//            Object drawableInflater = ReflectUtil.findField(res, "mDrawableInflater").get(res);
//            if (drawableInflater != null) {
//                ReflectUtil.findField(drawableInflater, "mClassLoader").set(drawableInflater, reflectClassLoader);
//            }
//        }

        Resources res = base.getResources();
        ReflectUtil.findField(res, "mClassLoader").set(res, reflectClassLoader);

        Object drawableInflater = ReflectUtil.findField(res, "mDrawableInflater").get(res);
        if (drawableInflater != null) {
            try {
                ReflectUtil.findField(drawableInflater, "mClassLoader").set(drawableInflater, reflectClassLoader);
            } catch (Exception e) {
                if (IncrementalApkLoader.isIncrementalApk()) {
                    // no idea why it will crash
                    LogUtils.i(TAG, "reflectPackageInfoClassloader isIncrementalApk, ignore exception while reflect drawableInflater : " + e);
                } else {
                    throw e;
                }
            }
        }

        Thread.currentThread().setContextClassLoader(reflectClassLoader);
    }

    static AndroidNClassLoader inject(BaseDexClassLoader originClassLoader, Context base) throws Exception {
        AndroidNClassLoader classLoader = createAndroidNClassLoader(originClassLoader, base);
        reflectPackageInfoClassloader(base, classLoader);
        return classLoader;
    }

    public Class<?> findClass(String name) throws ClassNotFoundException {
        // app class use default PathClassloader to load
        if (name != null &&  (name.startsWith("org.apache.commons.codec.")
                || name.startsWith("org.apache.commons.logging.")
                || name.startsWith("org.apache.http."))) {
            // Here's the whole story:
            //   Some app use apache wrapper library to access Apache utilities. Classes in apache wrapper
            //   library may be conflict with those preloaded in BootClassLoader.
            //   So with the build option:
            //       useLibrary 'org.apache.http.legacy'
            //   appears, the Android Framework will inject a jar called 'org.apache.http.legacy.boot.jar'
            //   in front of the path of user's apk. After that, PathList in app's PathClassLoader should
            //   look like this:
            //       ["/system/framework/org.apache.http.legacy.boot.jar", "path-to-user-apk", "path-to-other-preload-jar"]
            //   When app runs to the code refer to Apache classes, the referred classes in the first
            //   jar override those in user's app, which avoids any conflicts and crashes.
            //
            //   When it comes to Tinker, to block the cached instances in class table of app's
            //   PathClassLoader we use this AndroidNClassLoader to replace the original PathClassLoader.
            //   At the beginning it's fine to imitate system's behavior and construct the PathList in AndroidNClassLoader
            //   like below:
            //       ["/system/framework/org.apache.http.legacy.boot.jar", "path-to-new-dexes", "path-to-other-preload-jar"]
            //   However, the ART VM of Android P adds a new feature that checks whether the inlined class is loaded by the same
            //   ClassLoader that loads the callsite's class. If any Apache classes is inlined in old dex(oat), after we replacing
            //   the App's ClassLoader we will receive an assert since the Apache classes is loaded by another ClassLoader now.
            return originClassLoader.loadClass(name);
        } else if (name != null && name.startsWith("com.sickworm.intellij.jugg")) {
            return originClassLoader.loadClass(name);
        }

        Class<?> clazz = null;
        try {
            clazz = super.findClass(name);
            return clazz;
        } catch (ClassNotFoundException e) {
            // Some jars/apks other than base.apk was removed from AndroidNClassloader's dex path list.
            // So if target class cannot be found in AndroidNClassloader, we should fallback to try
            // original PathClassLoader for compatibility.
            // Obviously this behavior violates the Parent Delegate Model, but it doesn't matter.
            clazz = originClassLoader.loadClass(name);
            return clazz;
        }
    }

    @Override
    public String findLibrary(String name) {
        return super.findLibrary(name);
    }
}