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

#ifndef JNI_OBJECT_H
#define JNI_OBJECT_H

#include <jni.h>

#include <string>

namespace deploy {

class JniObject {
 public:
  JniObject(JNIEnv* jni, jobject object) : jni_(jni), object_(object) {
    // TODO - handle case where object is null.
    class_ = jni_->GetObjectClass(object);
  }

  ~JniObject() { jni_->DeleteLocalRef(class_); }

  JniObject(JniObject&&) = default;
  JniObject& operator=(JniObject&&) = default;

  std::string ToString();
  jclass GetClass() { return class_; }

  jboolean CallBooleanMethod(const char* name, const char* signature, ...);
  jbyte CallByteMethod(const char* name, const char* signature, ...);
  jchar CallCharMethod(const char* name, const char* signature, ...);
  jshort CallShortMethod(const char* name, const char* signature, ...);
  jint CallIntMethod(const char* name, const char* signature, ...);
  jlong CallLongMethod(const char* name, const char* signature, ...);
  jfloat CallFloatMethod(const char* name, const char* signature, ...);
  jdouble CallDoubleMethod(const char* name, const char* signature, ...);
  jobject CallObjectMethod(const char* name, const char* signature, ...);
  void CallVoidMethod(const char* name, const char* signature, ...);
  JniObject CallJniObjectMethod(const char* name, const char* signature, ...);

  jboolean GetBooleanField(const char* name, const char* signature);
  jbyte GetByteField(const char* name, const char* type);
  jchar GetCharField(const char* name, const char* type);
  jshort GetShortField(const char* name, const char* type);
  jint GetIntField(const char* name, const char* type);
  jlong GetLongField(const char* name, const char* type);
  jfloat GetFloatField(const char* name, const char* type);
  jdouble GetDoubleField(const char* name, const char* type);
  jobject GetObjectField(const char* name, const char* type);
  void GetVoidField(const char* name, const char* type);
  JniObject GetJniObjectField(const char* name, const char* type);

  void SetField(const char* name, const char* type, jobject value);

 private:
  JNIEnv* jni_;
  jclass class_;
  jobject object_;

  JniObject(const JniObject&) = delete;
  JniObject& operator=(const JniObject&) = delete;
};

}  // namespace deploy

#endif
