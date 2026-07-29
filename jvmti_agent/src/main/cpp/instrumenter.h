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

#ifndef INSTRUMENTER_H
#define INSTRUMENTER_H

#include "jvmti.h"

#include <memory>
#include <unordered_map>
#include <android/log.h>

#include "slicer/dex_ir.h"
#include "slicer/instrumentation.h"
#include "slicer/reader.h"
#include "slicer/writer.h"

#define LOG_TAG "jugg-jvmti"
#define ALOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using std::shared_ptr;
using std::string;
using std::unordered_map;

namespace deploy {

namespace {
// Converts a java-friendly class name into JNI format.
const std::string ToJniFormat(const std::string& class_name) {
  return "L" + class_name + ";";
}

}  // namespace

bool InstrumentApplication(jvmtiEnv* jvmti, JNIEnv* jni,
                           const std::string& package_name, bool overlay_swap);

// Probably should be in a utility header, but also only used here.
class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  JvmtiAllocator(jvmtiEnv* jvmti) : jvmti_(jvmti) {}

  virtual void* Allocate(size_t size) {
    unsigned char* alloc = nullptr;
    jvmti_->Allocate(size, &alloc);
    return (void*)alloc;
  }

  virtual void Free(void* ptr) {
    if (ptr == nullptr) {
      return;
    }

    jvmti_->Deallocate((unsigned char*)ptr);
  }

 private:
  jvmtiEnv* jvmti_;
};

class Transform {
 public:
  Transform(const std::string& class_name) : class_name_(class_name) {}
  virtual ~Transform() = default;

  std::string GetClassName() const { return class_name_; }
  virtual void Apply(std::shared_ptr<ir::DexFile> dex_ir) const = 0;

 private:
  const std::string class_name_;
};

struct MethodHooks {
  const std::string method_name;
  const std::string method_signature;
  const std::string entry_hook;
  const std::string exit_hook;

  static const std::string kNoHook;

  MethodHooks(const std::string& method_name,
              const std::string& method_signature,
              const std::string& entry_hook, const std::string& exit_hook)
      : method_name(method_name),
        method_signature(method_signature),
        entry_hook(entry_hook),
        exit_hook(exit_hook) {}
};

class HookTransform : public Transform {
 public:
  HookTransform(const std::string& class_name, const std::string& method_name,
                const std::string& method_signature,
                const std::string& entry_hook, const std::string& exit_hook)
      : Transform(class_name) {
    hooks_.emplace_back(method_name, method_signature, entry_hook, exit_hook);
  }

  HookTransform(const std::string& class_name,
                const std::vector<MethodHooks>& hooks)
      : Transform(class_name), hooks_(hooks) {}

  void Apply(std::shared_ptr<ir::DexFile> dex_ir) const override {
    for (const MethodHooks& hook : hooks_) {
      slicer::MethodInstrumenter mi(dex_ir);
      if (hook.entry_hook != MethodHooks::kNoHook) {
        const ir::MethodId entry_hook(kHookClassName, hook.entry_hook.c_str());
        mi.AddTransformation<slicer::EntryHook>(
        // covert this as object. slicer in AOSP has this feature, but my version doesn't.(compile fail)
//            entry_hook, slicer::EntryHook::Tweak::ThisAsObject);
            entry_hook);
      }
      if (hook.exit_hook != MethodHooks::kNoHook) {
        const ir::MethodId exit_hook(kHookClassName, hook.exit_hook.c_str());
        mi.AddTransformation<slicer::ExitHook>(exit_hook);
      }
      const std::string jni_name = ToJniFormat(GetClassName());
      const ir::MethodId target_method(jni_name.c_str(),
                                       hook.method_name.c_str(),
                                       hook.method_signature.c_str());
      if (!mi.InstrumentMethod(target_method)) {
          ALOGW("Optional hook method not found: class=%s method=%s signature=%s",
                GetClassName().c_str(), hook.method_name.c_str(),
                hook.method_signature.c_str());
      }
    }
  }

 private:
  const char* kHookClassName =
      "Lcom/sickworm/intellij/jugg/instrument/InstrumentationHooks;";
  std::vector<MethodHooks> hooks_;
};

// Applies an entry hook whose non-null object result bypasses the target method.
class EarlyReturnHookTransform : public Transform {
 public:
  EarlyReturnHookTransform(
      const std::string& class_name,
      const std::string& method_name,
      const std::string& method_signature,
      const std::string& hook_name)
      : Transform(class_name),
        method_name_(method_name),
        method_signature_(method_signature),
        hook_name_(hook_name) {}

  void Apply(std::shared_ptr<ir::DexFile> dex_ir) const override {
    slicer::MethodInstrumenter instrumenter(dex_ir);
    auto scratch_regs =
        instrumenter.AddTransformation<slicer::AllocateScratchRegs>(1);
    const ir::MethodId hook_method(kHookClassName, hook_name_.c_str());
    instrumenter.AddTransformation<slicer::EntryHookWithEarlyReturn>(
        hook_method, scratch_regs);
    const std::string jni_name = ToJniFormat(GetClassName());
    const ir::MethodId target_method(
        jni_name.c_str(), method_name_.c_str(), method_signature_.c_str());
    if (!instrumenter.InstrumentMethod(target_method)) {
      ALOGW("Optional hook method not found: class=%s method=%s signature=%s",
            GetClassName().c_str(), method_name_.c_str(),
            method_signature_.c_str());
    }
  }

 private:
  const char* kHookClassName =
      "Lcom/sickworm/intellij/jugg/instrument/InstrumentationHooks;";
  const std::string method_name_;
  const std::string method_signature_;
  const std::string hook_name_;
};

}  // namespace deploy

#endif
