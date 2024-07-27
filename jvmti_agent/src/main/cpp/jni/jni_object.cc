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

#include "jni_object.h"

#include "jni_util.h"

#define CALL_METHOD(TYPE, FUNC)                                               \
  TYPE JniObject::Call##FUNC##Method(const char* name, const char* signature, \
                                     ...) {                                   \
    va_list args;                                                             \
    va_start(args, signature);                                                \
    jmethodID id = jni_->GetMethodID(class_, name, signature);                \
    return jni_->Call##FUNC##MethodV(object_, id, args);                      \
  }

#define GET_FIELD(TYPE, FUNC)                                            \
  TYPE JniObject::Get##FUNC##Field(const char* name, const char* type) { \
    jfieldID id = jni_->GetFieldID(class_, name, type);                  \
    return jni_->Get##FUNC##Field(object_, id);                          \
  }

namespace deploy {

std::string JniObject::ToString() {
  jstring value =
      (jstring)this->CallObjectMethod("toString", "()Ljava/lang/String;");
  std::string copy = JStringToString(jni_, value);
  jni_->DeleteLocalRef(value);
  return copy;
}

CALL_METHOD(jboolean, Boolean)
CALL_METHOD(jbyte, Byte)
CALL_METHOD(jchar, Char)
CALL_METHOD(jshort, Short)
CALL_METHOD(jint, Int)
CALL_METHOD(jlong, Long)
CALL_METHOD(jfloat, Float)
CALL_METHOD(jdouble, Double)
CALL_METHOD(jobject, Object)
CALL_METHOD(void, Void)

JniObject JniObject::CallJniObjectMethod(const char* name,
                                         const char* signature, ...) {
  va_list args;
  va_start(args, signature);
  jmethodID id = jni_->GetMethodID(class_, name, signature);
  return JniObject(jni_, jni_->CallObjectMethodV(object_, id, args));
}

GET_FIELD(jboolean, Boolean)
GET_FIELD(jbyte, Byte)
GET_FIELD(jchar, Char)
GET_FIELD(jshort, Short)
GET_FIELD(jint, Int)
GET_FIELD(jlong, Long)
GET_FIELD(jfloat, Float)
GET_FIELD(jdouble, Double)
GET_FIELD(jobject, Object)

JniObject JniObject::GetJniObjectField(const char* name, const char* type) {
  jfieldID id = jni_->GetFieldID(class_, name, type);
  return JniObject(jni_, jni_->GetObjectField(object_, id));
}

void JniObject::SetField(const char* name, const char* type, jobject value) {
  jfieldID id = jni_->GetFieldID(class_, name, type);
  jni_->SetObjectField(object_, id, value);
}

}  // namespace deploy