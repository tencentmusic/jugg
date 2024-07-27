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

#include "jni_class.h"

#define CALL_STATIC_METHOD(TYPE, FUNC)                                  \
  TYPE JniClass::CallStatic##FUNC##Method(const char* name,             \
                                          const char* signature, ...) { \
    va_list args;                                                       \
    va_start(args, signature);                                          \
    jmethodID id = jni_->GetStaticMethodID(class_, name, signature);    \
    return jni_->CallStatic##FUNC##MethodV(class_, id, args);           \
  }

#define GET_STATIC_FIELD(TYPE, FUNC)                                          \
  TYPE JniClass::GetStatic##FUNC##Field(const char* name, const char* type) { \
    jfieldID id = jni_->GetStaticFieldID(class_, name, type);                 \
    return jni_->GetStatic##FUNC##Field(class_, id);                          \
  }

namespace deploy {

CALL_STATIC_METHOD(jboolean, Boolean)
CALL_STATIC_METHOD(jbyte, Byte)
CALL_STATIC_METHOD(jchar, Char)
CALL_STATIC_METHOD(jshort, Short)
CALL_STATIC_METHOD(jint, Int)
CALL_STATIC_METHOD(jlong, Long)
CALL_STATIC_METHOD(jfloat, Float)
CALL_STATIC_METHOD(jdouble, Double)
CALL_STATIC_METHOD(jobject, Object)
CALL_STATIC_METHOD(void, Void)

JniObject JniClass::CallStaticJniObjectMethod(const char* name,
                                              const char* signature, ...) {
  va_list args;
  va_start(args, signature);
  jmethodID id = jni_->GetStaticMethodID(class_, name, signature);
  return JniObject(jni_, jni_->CallStaticObjectMethodV(class_, id, args));
}

GET_STATIC_FIELD(jboolean, Boolean)
GET_STATIC_FIELD(jbyte, Byte)
GET_STATIC_FIELD(jchar, Char)
GET_STATIC_FIELD(jshort, Short)
GET_STATIC_FIELD(jint, Int)
GET_STATIC_FIELD(jlong, Long)
GET_STATIC_FIELD(jfloat, Float)
GET_STATIC_FIELD(jdouble, Double)
GET_STATIC_FIELD(jobject, Object)

JniObject JniClass::GetStaticJniObjectField(const char* name,
                                            const char* type) {
  jfieldID id = jni_->GetStaticFieldID(class_, name, type);
  return JniObject(jni_, jni_->GetStaticObjectField(class_, id));
}
}  // namespace deploy