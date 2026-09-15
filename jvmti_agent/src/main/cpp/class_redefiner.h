#ifndef JUGG_CLASS_REDEFINER_H
#define JUGG_CLASS_REDEFINER_H

#include <jni.h>
#include "jvmti.h"
#include <string>

/** Handles one dynamic Hot Reload request and writes its terminal result file. */
jint HandleHotReloadRequest(jvmtiEnv* jvmti, JNIEnv* jni,
                            const std::string& request_dir);

#endif
