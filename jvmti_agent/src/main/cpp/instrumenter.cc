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
 *
 */

#include <fcntl.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>
#include <slicer/dex_ir_builder.h>
#include <slicer/code_ir.h>
#include <slicer/reader.h>
#include <slicer/writer.h>

#include <string>

#include "jvmti.h"
#include "instrumenter.h"
#include "jni/jni_class.h"
#include "native_callbacks.h"

namespace deploy {

const std::string agentVersion = AGENT_VERSION; // declared in build.gradle cppFlags
const std::string kInstrumentationJarName = "/data/local/tmp/jugg/" + agentVersion + "/jugg-instruments.jar";
const char* kBreadcrumbClass = "com/sickworm/intellij/jugg/instrument/Breadcrumb";
const char* instrumentation_jar_hash = kInstrumentationJarName.c_str();

const std::string MethodHooks::kNoHook = "";
const std::string kNoCache = "";

namespace {

// Holds the transform that will be applied by Agent_ClassFileLoadHook.
const Transform* current_transform = nullptr;

// Holds the transformed bytes of the last class transformed by
// Agent_ClassFileLoadHook.
std::vector<dex::u4> last_class_bytes;

}  // namespace

#define FILE_MODE (S_IRUSR | S_IWUSR)

void ErrEvent(const std::string& message) {
  __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, message.c_str());
}

void LogEvent(const std::string& message) {
  __android_log_write(ANDROID_LOG_INFO, LOG_TAG, message.c_str());
}

std::string GetInstrumentJarPath(const std::string& package_name) {
    return kInstrumentationJarName;
}

bool CheckJvmti(jvmtiError error, const std::string& error_message) {
    if (error != JVMTI_ERROR_NONE) {
        ALOGE("JVMTI error: %s", error_message.c_str());
        return false;
    }

    return true;
}


bool LoadInstrumentationJar(jvmtiEnv* jvmti, JNIEnv* jni,
                            const std::string& jar_path) {
    // Check for the existence of a breadcrumb class, indicating a previous agent
    // has already loaded instrumentation. If no previous agent has run on this
    // jvm, add our instrumentation classes to the bootstrap class loader.
    jclass unused = jni->FindClass(kBreadcrumbClass);
    if (unused == nullptr) {
        ALOGI("No existing instrumentation found. Loading instrumentation from %s",
               kInstrumentationJarName.c_str());
        jni->ExceptionClear();
        ALOGI("Load instrument jar: %s", jar_path.c_str());
        if (jvmti->AddToBootstrapClassLoaderSearch(jar_path.c_str()) !=
            JVMTI_ERROR_NONE) {
            return false;
        }
    } else {
        jni->DeleteLocalRef(unused);
    }
    return true;
}

bool ApplyTransforms(jvmtiEnv* jvmti, JNIEnv* jni,
                     const std::string& cache_path,
                     const std::vector<const Transform*>& transforms) {
  std::vector<jclass> classes;
  std::vector<const Transform*> resolved_transforms;
  for (const auto& transform : transforms) {
    jclass klass = jni->FindClass(transform->GetClassName().c_str());
    if (klass == nullptr) {
      ALOGW("Optional hook transform class not found: %s",
            transform->GetClassName().c_str());
      jni->ExceptionClear();
      continue;
    }
    classes.push_back(klass);
    resolved_transforms.push_back(transform);
  }

  std::vector<std::string> failed_classes;
  for (int i = 0; i < classes.size(); ++i) {
    current_transform = resolved_transforms[i];
    jvmtiError error = jvmti->RetransformClasses(1, &classes[i]);
    current_transform = nullptr;
    bool success = CheckJvmti(
        error,
        "Could not retransform class: " + resolved_transforms[i]->GetClassName());
    jni->DeleteLocalRef(classes[i]);

    // We intentionally do not stop if one transformation fails, because it's
    // useful to collect data on every failing transform - and if one is failing
    // due to platform/OEM changes, others might as well.
    if (!success) {
      ALOGW("Optional hook transform retransform failed: %s",
            resolved_transforms[i]->GetClassName().c_str());
      failed_classes.push_back(resolved_transforms[i]->GetClassName());
      continue;
    }
    ALOGI("Optional hook transform retransform success: %s",
          resolved_transforms[i]->GetClassName().c_str());
  }
  if (!failed_classes.empty()) {
    ALOGW("Optional hook transform failed count: %zu", failed_classes.size());
  }
  return true;
}

bool Instrument(jvmtiEnv* jvmti, JNIEnv* jni, const std::string& jar,
                bool overlay_swap) {
    // The breadcrumb class stores some checks between runs of the agent.
    // We can't use the class from the FindClass call because it may not have
    // actually found the class.
    JniClass breadcrumb(jni, kBreadcrumbClass);

    // Ensure that the jar hasn't changed since we last instrumented. If it has,
    // fail out for now. This is an important scenario to guard against, since it
    // would likely cause silent failures.
    jstring jar_hash = jni->NewStringUTF(instrumentation_jar_hash);
    jboolean matches = breadcrumb.CallStaticBooleanMethod(
        "checkHash", "(Ljava/lang/String;)Z", jar_hash);
    jni->DeleteLocalRef(jar_hash);

    if (!matches) {
        ALOGE(
            "The instrumentation jar at %s does not match the jar previously used "
            "to instrument. The application must be restarted.",
            kInstrumentationJarName.c_str());
        return false;
    }

    // Check if we need to instrument, or if a previous agent successfully did.
    if (breadcrumb.CallStaticBooleanMethod("isFinishedInstrumenting", "()Z")) {
        return true;
    }

    const MethodHooks newApplication(
        /* target method */ "newApplication",
        /* target signature */"(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
        "handleNewApplicationEntry",
        MethodHooks::kNoHook
      );

    const MethodHooks newApplication2(
        /* target method */ "newApplication",
        /* target signature */"(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
        "handleNewApplicationEntry2",
        MethodHooks::kNoHook
      );

    const HookTransform application(
        "android/app/Instrumentation",
        { newApplication, newApplication2 }
    );

    const MethodHooks instantiateApplication(
        "instantiateApplication",
        "(Ljava/lang/ClassLoader;Ljava/lang/String;)Landroid/app/Application;",
        MethodHooks::kNoHook,
        "handleInstantiateApplicationExit"
    );

    const HookTransform appComponentFactory(
        "android/app/AppComponentFactory",
        { instantiateApplication }
    );

    const HookTransform resManager(
        "android/app/ResourcesManager",
        "createAssetManager",
        "(Landroid/content/res/ResourcesKey;)Landroid/content/res/AssetManager;", // used in Android 8 at least, 13 at most
        "createAssetManagerEnter", "createAssetManagerExit");

    const HookTransform resManagerNew(
        "android/app/ResourcesManager",
        "createAssetManager",
        "(Landroid/content/res/ResourcesKey;Landroid/app/ResourcesManager$ApkAssetsSupplier;)Landroid/content/res/AssetManager;", // used in Android 14 at least
        "createAssetManagerNewEnter", "createAssetManagerNewExit");

    const MethodHooks sendMessage(
        "sendMessage",
        "(ILjava/lang/Object;IIZ)V",
        "sendMessageEnter", "sendMessageExit");

    const MethodHooks handleApplicationInfoChanged(
        "handleApplicationInfoChanged",
        "(Landroid/content/pm/ApplicationInfo;)V",
        MethodHooks::kNoHook, "handleApplicationInfoChangedExit");

    const HookTransform activityThread(
        "android/app/ActivityThread",
        { sendMessage, handleApplicationInfoChanged }
    );

    bool success = true;
  success &=
      CheckJvmti(jvmti->SetEventNotificationMode(
                     JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL),
                 "Could not enable class file load hook event");

  if (success) {
    // Hook transforms are optional. Platform and OEM differences must not make
    // the whole agent unavailable.
    ApplyTransforms(
        jvmti,
        jni,
        kNoCache,
        { &application, &appComponentFactory, &resManager, &activityThread });
    ApplyTransforms(jvmti, jni, kNoCache, { &resManagerNew });
  }

  // Failing to disable this event does not actually have any bearing on
  // whether or not instrumentation was a success, so we do not modify the
  // success flag.
  CheckJvmti(jvmti->SetEventNotificationMode(
                 JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL),
             "Could not disable class file load hook event");

  if (success) {
    breadcrumb.CallStaticVoidMethod("setFinishedInstrumenting", "()V");
    LogEvent("Finished instrumenting");
  }

  return success;
}

// Event that fires when the agent loads a class file.
extern "C" void JNICALL Agent_ClassFileLoadHook(
    jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined, jobject loader,
    const char* name, jobject protection_domain, jint class_data_len,
    const unsigned char* class_data, jint* new_class_data_len,
    unsigned char** new_class_data) {
  if (current_transform == nullptr ||
      current_transform->GetClassName() != name) {
    return;
  }

  // The class name needs to be in JNI-format.
  string descriptor = "L" + current_transform->GetClassName() + ";";

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(descriptor.c_str());
  if (class_index == dex::kNoIndex) {
    ALOGW("ClassFileLoadHook could not find class index: %s", name);
    return;
  }

  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();
  current_transform->Apply(dex_ir);

  size_t new_image_size = 0;
  dex::u1* new_image = nullptr;
  dex::Writer writer(dex_ir);

  JvmtiAllocator allocator(jvmti);
  new_image = writer.CreateImage(&allocator, &new_image_size);

  last_class_bytes.clear();
  last_class_bytes.resize(new_image_size);
  memcpy(last_class_bytes.data(), new_image, new_image_size);

  *new_class_data_len = new_image_size;
  *new_class_data = new_image;
  ALOGI("ClassFileLoadHook transformed: %s, bytes=%zu", name, new_image_size);
}

bool InstrumentApplication(jvmtiEnv* jvmti, JNIEnv* jni,
                           const std::string& package_name, bool overlay_swap) {
    jvmtiEventCallbacks callbacks;
    callbacks.ClassFileLoadHook = Agent_ClassFileLoadHook;

    if (jvmti->SetEventCallbacks(&callbacks, sizeof(jvmtiEventCallbacks)) !=
        JVMTI_ERROR_NONE) {
        ALOGE("Error setting event callbacks.");
        return false;
    }

    std::string instrument_jar_path = GetInstrumentJarPath(package_name);

    if (!LoadInstrumentationJar(jvmti, jni, instrument_jar_path)) {
        ALOGE("Error loading instrumentation dex.");
        return false;
    }

    if (!Instrument(jvmti, jni, instrument_jar_path, overlay_swap)) {
        ALOGE("Error instrumenting application.");
        return false;
    }

    return true;
}


}  // namespace deploy
