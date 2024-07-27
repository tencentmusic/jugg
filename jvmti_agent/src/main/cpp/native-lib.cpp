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

    const std::string package_name =
        app_data_dir.substr(app_data_dir.find_last_of('/') + 1);

    if (!InstrumentApplication(jvmti, jni, package_name, true)) {
        ALOGE("Could not instrument application");
        jvmti->DisposeEnvironment();
        return JNI_OK;
    }

    jvmti->DisposeEnvironment();
    return JNI_OK;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM *vm, char *options,
                                                 void *reserved) {
    ALOGI("==============Agent_OnAttach====================");
    jvmtiEnv* jvmti = nullptr;
    if (vm->GetEnv((void**)&jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        ALOGE("Error retrieving JVMTI function table.");
        return JNI_OK;
    }
    JNIEnv* jni = nullptr;
    if (vm->GetEnv((void**)&jni, JNI_VERSION_1_2) != JNI_OK) {
        ALOGE("Error retrieving JNI function table.");
        return JNI_OK;
    }
    return HandleStartupAgent(jvmti, jni, options);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    ALOGI("==============library load====================");
    return JNI_VERSION_1_6;
}