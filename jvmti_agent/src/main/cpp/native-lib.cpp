#include <jni.h>
#include <string>
#include "jvmti.h"
#include <android/log.h>
#include <slicer/dex_ir_builder.h>
#include <slicer/code_ir.h>
#include <slicer/reader.h>
#include <slicer/writer.h>
#include <sstream>
#include "instrumenter.h"
#include "capabilities.h"
#include "class_redefiner.h"
#include "jni/jni_class.h"

using namespace dex;
using namespace lir;
using namespace deploy;

void SetAllCapabilities(jvmtiEnv *jvmti) {
    jvmtiCapabilities caps;
    jvmtiError error;
    error = jvmti->GetPotentialCapabilities(&caps);
    error = jvmti->AddCapabilities(&caps);
}

jint HandleStartupAgent(jvmtiEnv* jvmti, JNIEnv* jni,
                        const std::string& app_data_dir) {
    ALOGI("Startup agent attached to VM");

    SetAllCapabilities(jvmti);
    if (jvmti->AddCapabilities(&REQUIRED_CAPABILITIES) != JVMTI_ERROR_NONE) {
        ALOGE("Error setting capabilities.");
        jvmti->DisposeEnvironment();
        return JNI_OK;
    }

    if (!InstrumentApplication(jvmti, jni, app_data_dir, true)) {
        ALOGE("Could not instrument application");
        jvmti->DisposeEnvironment();
        return JNI_OK;
    }

    JniClass hooks(jni, "com/sickworm/intellij/jugg/instrument/InstrumentationHooks");
    jstring data_dir = jni->NewStringUTF(app_data_dir.c_str());
    hooks.CallStaticVoidMethod("initializeDirectResourceOverlays", "(Ljava/lang/String;)V", data_dir);
    jni->DeleteLocalRef(data_dir);
    if (jni->ExceptionCheck()) {
        ALOGW("Could not initialize Direct resource overlays");
        jni->ExceptionDescribe();
        jni->ExceptionClear();
    }

    jvmti->DisposeEnvironment();
    return JNI_OK;
}

const char* kPathSeparator =
#ifdef _WIN32
"\\";
#else
"/";
#endif

bool markAsJvmtiIsAvailable(bool isAvailable, char* app_data_dir) {
    std::string flag_file_path;

    if (isAvailable) {
        flag_file_path.append(app_data_dir)
            .append(kPathSeparator).append("code_cache")
            .append(kPathSeparator).append(".jugg_jvmti_available");
        ALOGD("Creating flag file: %s", flag_file_path.c_str());
    } else {
        flag_file_path.append(app_data_dir)
            .append(kPathSeparator).append("code_cache")
            .append(kPathSeparator).append(".jugg_jvmti_not_available");
        ALOGE("Creating flag file: %s", flag_file_path.c_str());
    }

    FILE* flag_file = fopen(flag_file_path.c_str(), "w");
    if (flag_file == nullptr) {
        ALOGE("Could not create flag file to mark JVMTI as unavailable.");
        return false;
    }
    return true;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options,
                                                 void *reserved) {
    ALOGI("==============Agent_OnAttach====================");
    const std::string agent_options = options == nullptr ? "" : options;
    const bool is_startup_agent = !agent_options.empty() && agent_options[0] == '/';
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv((void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        ALOGE("Error retrieving JVMTI function table.");
        if (is_startup_agent && !markAsJvmtiIsAvailable(false, options)) {
            ALOGE("Could not mark JVMTI as unavailable.");
        }
        return JNI_OK;
    }
    JNIEnv* jni = nullptr;
    if (vm->GetEnv((void**)&jni, JNI_VERSION_1_2) != JNI_OK) {
        ALOGE("Error retrieving JNI function table.");
        if (is_startup_agent && !markAsJvmtiIsAvailable(false, options)) {
            ALOGE("Could not mark JVMTI as unavailable.");
        }
        return JNI_OK;
    }

    if (agent_options.find("jugg_hot_reload:") == 0) {
        const jint result = HandleHotReloadRequest(
            jvmti, jni, agent_options.substr(std::string("jugg_hot_reload:").size()));
        jvmti->DisposeEnvironment();
        return result;
    }

    // refer from Apply Changes agent.cc
    // Startup agents are passed the path to the app data directory.
    if (is_startup_agent) {
        // run by ActivityThread.java
        markAsJvmtiIsAvailable(true, options);
        return HandleStartupAgent(jvmti, jni, agent_options);
    }
    ALOGE("Unknown Jugg JVMTI agent options: %s", agent_options.c_str());
    jvmti->DisposeEnvironment();
    return JNI_OK;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ALOGI("==============library load====================");
    return JNI_VERSION_1_6;
}
