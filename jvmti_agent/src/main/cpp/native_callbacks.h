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

#ifndef NATIVE_CALLBACKS_H
#define NATIVE_CALLBACKS_H

#include <vector>

#include <jni.h>
#include "jvmti.h"

namespace deploy {

struct NativeBinding {
  const char* class_name;
  JNINativeMethod native_method;

  NativeBinding(const char* name, const char* method_name,
                const char* method_signature, void* native_ptr)
      : class_name(const_cast<char*>(name)) {
    native_method.name = const_cast<char*>(method_name);
    native_method.signature = const_cast<char*>(method_signature);
    native_method.fnPtr = native_ptr;
  }
};

bool RegisterNative(JNIEnv* jni, const NativeBinding& binding);

// This method is bound to InstrumentationHooks#fixAppContext()
jobject Native_FixAppContext(JNIEnv* jni, jobject object,
                             jobject activity_thread);

// This method is bound to InstrumentationHooks#getActivities()
jobject Native_GetActivityClientRecords(JNIEnv* jni, jobject object,
                                        jobject activity_thread);

// This method is bound to InstrumentationHooks#fixActivityContext()
void Native_FixActivityContext(JNIEnv* jni, jobject object,
                               jobject activity_record,
                               jobject new_resources_impl);

// This method is bound to InstrumentationHooks#updateApplicationInfo()
void Native_UpdateApplicationInfo(JNIEnv* jni, jobject object,
                                  jobject activity_thread);

// This method is bound to InstrumentationHooks#logUnhandledException()
void Native_LogUnhandledException(JNIEnv* jni, jobject object, jobject thread,
                                  jthrowable throwable);

// This method is bound to DexUtility#makeInMemoryDexElements()
jarray Native_MakeInMemoryDexElements(JNIEnv* jni, jobject object,
                                      jarray dex_files,
                                      jobject suppressed_exceptions);

// This method is bound to Phase#start()
void Native_Phase_Start(JNIEnv* jni, jobject this_object, jstring text);

// This method is bound to Phase#end()
void Native_Phase_End(JNIEnv* jni, jobject this_object);
}  // namespace deploy

#endif
