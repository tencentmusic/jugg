/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "native_callbacks.h"

#include "jni/jni_class.h"
#include "jni/jni_object.h"

namespace deploy {

bool RegisterNative(JNIEnv *jni, const NativeBinding &binding) {
    jclass klass = jni->FindClass(binding.class_name);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return false;
    }

    jni->RegisterNatives(klass, &binding.native_method, 1);
    jni->DeleteLocalRef(klass);
    return true;
}

// Obtain the LoadedApk of the current application, then replace the
// application's ResourcesImpl with the ResourcesImpl of the LoadedApk. Return
// the resource implementation of the LoadedApk so that it can be used to fix
// activity contexts.
jobject Native_FixAppContext(JNIEnv *jni, jobject object,
                             jobject activity_thread) {
    // Application app = activityThread.getApplication();
    // LoadedApk loadedApk = app.mLoadedApk;
    // Resources newResources = loadedApk.getResources();
    // ResourcesImpl newResourcesImpl = newResources.getImpl();
    // Resources oldResources = app.getResources();
    // oldResources.setImpl(newResourcesImpl);
    // ApplicationInfo appInfo = loadedApk.getApplicationInfo();
    // activityThread.handleApplicationInfoChanged(appInfo);
    // return newResourcesImpl;

    JniObject thread_wrapper(jni, activity_thread);

    jobject app = thread_wrapper.CallObjectMethod("getApplication",
                                                  "()Landroid/app/Application;");
    JniObject app_wrapper(jni, app);

    JniObject loaded_apk =
        app_wrapper.GetJniObjectField("mLoadedApk", "Landroid/app/LoadedApk;");

    JniObject new_resources = loaded_apk.CallJniObjectMethod(
        "getResources", "()Landroid/content/res/Resources;");

    jobject new_resources_impl = new_resources.CallObjectMethod(
        "getImpl", "()Landroid/content/res/ResourcesImpl;");

    JniObject old_resources = app_wrapper.CallJniObjectMethod(
        "getResources", "()Landroid/content/res/Resources;");

    old_resources.CallVoidMethod(
        "setImpl",
        "(Landroid/content/res/ResourcesImpl;)V",
        new_resources_impl);

    return new_resources_impl;
}

// Get the list of ActivityClientRecords so that we can reach into each
// activity and update its internal ResourceImpl.
jobject Native_GetActivityClientRecords(JNIEnv *jni, jobject object,
                                        jobject activity_thread) {
    // ArrayMap<IBinder, ActivityClientRecord> map = activityThread.mActivities;
    // return map.values();

    JniObject thread_wrapper(jni, activity_thread);

    JniObject map = thread_wrapper.GetJniObjectField("mActivities",
                                                     "Landroid/util/ArrayMap;");

    return map.CallObjectMethod("values", "()Ljava/util/Collection;");
}

// Given an ActivityRecord, replace the resource implementation of the activity
// with a new ResourcesImpl.
void Native_FixActivityContext(JNIEnv *jni, jobject object,
                               jobject activity_record,
                               jobject new_resources_impl) {
    // Activity activity = activityRecord.activity;
    // Resources oldResources = activity.getResources();
    // oldResources.setImpl(newResourcesImpl);

    JniObject record_wrapper(jni, activity_record);

    JniObject activity =
        record_wrapper.GetJniObjectField("activity", "Landroid/app/Activity;");

    JniObject old_resources = activity.CallJniObjectMethod(
        "getResources", "()Landroid/content/res/Resources;");

    old_resources.CallVoidMethod(
        "setImpl",
        "(Landroid/content/res/ResourcesImpl;)V",
        new_resources_impl);
}

// Call handleUpdateApplicationInfo changed on the current activity thread,
// using the LoadedApk of the current application.
void Native_UpdateApplicationInfo(JNIEnv *jni, jobject object,
                                  jobject activity_thread) {
    // Application app = activityThread.getApplication();
    // LoadedApk loadedApk = app.mLoadedApk;
    // ApplicationInfo appInfo = loadedApk.getApplicationInfo();
    // activityThread.handleApplicationInfoChanged(appInfo);

    JniObject thread_wrapper(jni, activity_thread);

    jobject app = thread_wrapper.CallObjectMethod("getApplication",
                                                  "()Landroid/app/Application;");
    JniObject app_wrapper(jni, app);

    JniObject loaded_apk =
        app_wrapper.GetJniObjectField("mLoadedApk", "Landroid/app/LoadedApk;");

    jobject app_info = loaded_apk.CallObjectMethod(
        "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");

    thread_wrapper.CallVoidMethod("handleApplicationInfoChanged",
                                  "(Landroid/content/pm/ApplicationInfo;)V",
                                  app_info);
}

// Simple wrapper around DexPathList#makeInMemoryDexElements.
jarray Native_MakeInMemoryDexElements(JNIEnv *jni, jobject object,
                                      jarray dex_files,
                                      jobject suppressed_exceptions) {
    // return DexPathList.makeInMemoryDexElements(dexFiles, suppressedExceptions);
    JniClass dex_path_list(jni, "dalvik/system/DexPathList");
    return (jarray) dex_path_list.CallStaticObjectMethod(
        "makeInMemoryDexElements",
        "([Ljava/nio/ByteBuffer;Ljava/util/List;)[Ldalvik/system/"
        "DexPathList$Element;",
        dex_files, suppressed_exceptions);
}

}