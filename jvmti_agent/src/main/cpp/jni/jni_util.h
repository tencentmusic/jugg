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

#ifndef JNI_UTIL_H
#define JNI_UTIL_H

#include <string>

#include <jni.h>
#include "../jvmti.h"

namespace deploy {

// Gets an std::string from a jstring. Does not delete the jni local jstring.
std::string JStringToString(JNIEnv* jni, jstring str);

// Checks a jvmti return value and logs an error if it is a failure. Returns
// true if the operation was a success; false otherwise.
bool CheckJvmti(jvmtiError error, const std::string& error_message);

}  // namespace deploy

#endif