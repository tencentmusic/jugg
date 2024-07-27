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

#ifndef JNI_CLASS_H
#define JNI_CLASS_H

#define NO_DEFAULT_SPECIALIZATION(T) static_assert(sizeof(T) == 0, "");

#include <jni.h>

#include "jni_object.h"

namespace deploy {

class JniClass {
 public:
  JniClass(JNIEnv* jni, const char* name) : jni_(jni) {
    // TODO: handle class not found.
    class_ = jni_->FindClass(name);
  }

  JniClass(JNIEnv* jni, jclass klass) : jni_(jni) { class_ = klass; }

  ~JniClass() { jni_->DeleteLocalRef(class_); }

  JniClass& operator=(JniClass&&) = default;

  jboolean CallStaticBooleanMethod(const char* name, const char* signature,
                                   ...);
  jbyte CallStaticByteMethod(const char* name, const char* signature, ...);
  jchar CallStaticCharMethod(const char* name, const char* signature, ...);
  jshort CallStaticShortMethod(const char* name, const char* signature, ...);
  jint CallStaticIntMethod(const char* name, const char* signature, ...);
  jlong CallStaticLongMethod(const char* name, const char* signature, ...);
  jfloat CallStaticFloatMethod(const char* name, const char* signature, ...);
  jdouble CallStaticDoubleMethod(const char* name, const char* signature, ...);
  jobject CallStaticObjectMethod(const char* name, const char* signature, ...);
  void CallStaticVoidMethod(const char* name, const char* signature, ...);
  JniObject CallStaticJniObjectMethod(const char* name, const char* signature,
                                      ...);

  jboolean GetStaticBooleanField(const char* name, const char* signature);
  jbyte GetStaticByteField(const char* name, const char* type);
  jchar GetStaticCharField(const char* name, const char* type);
  jshort GetStaticShortField(const char* name, const char* type);
  jint GetStaticIntField(const char* name, const char* type);
  jlong GetStaticLongField(const char* name, const char* type);
  jfloat GetStaticFloatField(const char* name, const char* type);
  jdouble GetStaticDoubleField(const char* name, const char* type);
  jobject GetStaticObjectField(const char* name, const char* type);
  void GetStaticVoidField(const char* name, const char* type);
  JniObject GetStaticJniObjectField(const char* name, const char* type);

 private:
  JNIEnv* jni_;
  jclass class_;

  JniClass(const JniClass&) = delete;
  JniClass& operator=(const JniClass&) = delete;
};

}  // namespace deploy

#endif
