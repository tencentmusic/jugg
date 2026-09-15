#include "class_redefiner.h"

#include <android/log.h>
#include <unistd.h>

#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "capabilities.h"
#include "instrumenter.h"
#include "native_callbacks.h"

namespace {

const char* kProtocol = "JUGG_HOT_RELOAD_V4";
const char* kRefreshResources = "REFRESH_RESOURCES";
const char* kRestartActivity = "RESTART_ACTIVITY";
const char* kNewClass = "NEW";
const char* kModifiedClass = "MODIFIED";
const char* kDexUtilityClass =
    "com/sickworm/intellij/jugg/instrument/DexUtility";
const int kMaxClassCount = 512;
const size_t kMaxDexSize = 16 * 1024 * 1024;
const size_t kMaxTotalDexSize = 64 * 1024 * 1024;

struct RequestEntry {
  std::string name;
  std::string relative_path;
  std::vector<unsigned char> dex;
  jclass klass = nullptr;
};

std::string CleanDetail(const std::string& detail) {
  std::string result = detail;
  for (size_t i = 0; i < result.size(); ++i) {
    if (result[i] == '\t' || result[i] == '\n' || result[i] == '\r') {
      result[i] = ' ';
    }
  }
  return result;
}

bool WriteResult(const std::string& request_dir, const std::string& result) {
  const std::string temp_path = request_dir + "/result.tmp";
  const std::string final_path = request_dir + "/result.txt";
  FILE* file = fopen(temp_path.c_str(), "w");
  if (file == nullptr) {
    return false;
  }
  const size_t written = fwrite(result.data(), 1, result.size(), file);
  fflush(file);
  fsync(fileno(file));
  fclose(file);
  return written == result.size() &&
         rename(temp_path.c_str(), final_path.c_str()) == 0;
}

bool IsSafeRequestDir(const std::string& request_dir) {
  return !request_dir.empty() && request_dir[0] == '/' &&
         request_dir.find("/code_cache/jugg_hot_reload/") != std::string::npos &&
         request_dir.find("..") == std::string::npos;
}

bool IsSafeRelativePath(const std::string& path) {
  return !path.empty() && path[0] != '/' && path.find("..") == std::string::npos &&
         path.find('\\') == std::string::npos;
}

bool ReadFile(const std::string& path, size_t max_size,
              std::vector<unsigned char>* content) {
  std::ifstream stream(path.c_str(), std::ios::binary | std::ios::ate);
  if (!stream) {
    return false;
  }
  const std::streamsize size = stream.tellg();
  if (size <= 0 || static_cast<size_t>(size) > max_size) {
    return false;
  }
  content->resize(static_cast<size_t>(size));
  stream.seekg(0, std::ios::beg);
  return stream.read(reinterpret_cast<char*>(content->data()), size).good();
}

bool ParseRequest(const std::string& request_dir,
                  std::vector<RequestEntry>* new_classes,
                  std::vector<RequestEntry>* modified_classes,
                  bool* refresh_resources,
                  bool* restart_activity,
                  std::string* error) {
  std::ifstream request((request_dir + "/request.txt").c_str());
  std::string line;
  if (!request || !std::getline(request, line) || line != kProtocol) {
    *error = "invalid protocol";
    return false;
  }
  if (!std::getline(request, line) ||
      (line != std::string(kRefreshResources) + "\t0" &&
       line != std::string(kRefreshResources) + "\t1")) {
    *error = "invalid refresh resources flag";
    return false;
  }
  *refresh_resources = line.back() == '1';
  if (!std::getline(request, line) ||
      (line != std::string(kRestartActivity) + "\t0" &&
       line != std::string(kRestartActivity) + "\t1")) {
    *error = "invalid restart activity flag";
    return false;
  }
  *restart_activity = line.back() == '1';
  size_t total_size = 0;
  while (std::getline(request, line)) {
    if (line.empty()) {
      continue;
    }
    const size_t first_separator = line.find('\t');
    const size_t second_separator = line.find('\t', first_separator + 1);
    if (first_separator == std::string::npos ||
        second_separator == std::string::npos ||
        new_classes->size() + modified_classes->size() >= kMaxClassCount) {
      *error = "invalid request entry";
      return false;
    }
    const std::string type = line.substr(0, first_separator);
    RequestEntry entry;
    entry.name = line.substr(first_separator + 1,
                             second_separator - first_separator - 1);
    entry.relative_path = line.substr(second_separator + 1);
    const bool is_new_class = type == kNewClass;
    const bool is_modified_class = type == kModifiedClass;
    const bool is_valid_modified_name = entry.name.size() >= 3 &&
        entry.name[0] == 'L' && entry.name[entry.name.size() - 1] == ';';
    if ((!is_new_class && !is_modified_class) || entry.name.empty() ||
        (is_modified_class && !is_valid_modified_name) ||
        !IsSafeRelativePath(entry.relative_path)) {
      *error = "invalid class entry";
      return false;
    }
    if (!ReadFile(request_dir + "/" + entry.relative_path, kMaxDexSize,
                  &entry.dex) ||
        entry.dex.size() < 4 || entry.dex[0] != 'd' || entry.dex[1] != 'e' ||
        entry.dex[2] != 'x' || entry.dex[3] != '\n') {
      *error = "invalid dex";
      return false;
    }
    total_size += entry.dex.size();
    if (total_size > kMaxTotalDexSize) {
      *error = "request too large";
      return false;
    }
    if (is_new_class) {
      new_classes->push_back(std::move(entry));
    } else {
      modified_classes->push_back(std::move(entry));
    }
  }
  return true;
}

std::string GetThrowableDetail(JNIEnv* jni, jthrowable throwable) {
  if (throwable == nullptr) {
    return "";
  }
  jclass throwable_class = jni->GetObjectClass(throwable);
  jmethodID to_string = throwable_class == nullptr ? nullptr :
      jni->GetMethodID(throwable_class, "toString", "()Ljava/lang/String;");
  jstring value = to_string == nullptr ? nullptr : static_cast<jstring>(
      jni->CallObjectMethod(throwable, to_string));
  if (jni->ExceptionCheck() || value == nullptr) {
    jni->ExceptionClear();
    if (throwable_class != nullptr) {
      jni->DeleteLocalRef(throwable_class);
    }
    return "";
  }
  const char* chars = jni->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? "" : chars;
  if (chars != nullptr) {
    jni->ReleaseStringUTFChars(value, chars);
  } else if (jni->ExceptionCheck()) {
    jni->ExceptionClear();
  }
  jni->DeleteLocalRef(value);
  jni->DeleteLocalRef(throwable_class);
  return result;
}

bool ConsumeJniException(JNIEnv* jni, const std::string& detail,
                         std::string* error) {
  if (!jni->ExceptionCheck()) {
    return false;
  }
  jthrowable throwable = jni->ExceptionOccurred();
  jni->ExceptionDescribe();
  jni->ExceptionClear();
  *error = detail;
  const std::string throwable_detail = GetThrowableDetail(jni, throwable);
  if (!throwable_detail.empty()) {
    *error += ": " + throwable_detail;
  }
  jni->DeleteLocalRef(throwable);
  return true;
}

std::string GetAppDataDir(const std::string& request_dir) {
  const std::string marker = "/code_cache/jugg_hot_reload/";
  const size_t marker_index = request_dir.find(marker);
  return marker_index == std::string::npos ? "" : request_dir.substr(0, marker_index);
}

jobjectArray CreateDexByteArrays(JNIEnv* jni,
                                 const std::vector<RequestEntry>& entries,
                                 std::string* error) {
  if (jni->PushLocalFrame(3) < 0) {
    ConsumeJniException(jni, "creating new class local frame failed", error);
    return nullptr;
  }
  jclass byte_array_class = jni->FindClass("[B");
  if (ConsumeJniException(jni, "finding byte array class failed", error) ||
      byte_array_class == nullptr) {
    jni->PopLocalFrame(nullptr);
    return nullptr;
  }
  jobjectArray dex_bytes = jni->NewObjectArray(
      static_cast<jsize>(entries.size()), byte_array_class, nullptr);
  if (ConsumeJniException(jni, "creating new class dex array failed", error) ||
      dex_bytes == nullptr) {
    jni->PopLocalFrame(nullptr);
    return nullptr;
  }
  for (size_t i = 0; i < entries.size(); ++i) {
    jbyteArray bytes = jni->NewByteArray(
        static_cast<jsize>(entries[i].dex.size()));
    if (ConsumeJniException(jni, "creating new class dex bytes failed", error) ||
        bytes == nullptr) {
      jni->PopLocalFrame(nullptr);
      return nullptr;
    }
    jni->SetByteArrayRegion(
        bytes, 0, static_cast<jsize>(entries[i].dex.size()),
        reinterpret_cast<const jbyte*>(entries[i].dex.data()));
    if (ConsumeJniException(jni, "copying new class dex bytes failed", error)) {
      jni->DeleteLocalRef(bytes);
      jni->PopLocalFrame(nullptr);
      return nullptr;
    }
    jni->SetObjectArrayElement(dex_bytes, static_cast<jsize>(i), bytes);
    jni->DeleteLocalRef(bytes);
    if (ConsumeJniException(jni, "storing new class dex bytes failed", error)) {
      jni->PopLocalFrame(nullptr);
      return nullptr;
    }
  }
  return static_cast<jobjectArray>(jni->PopLocalFrame(dex_bytes));
}

/** Returns the Application ClassLoader through the same native path as Apply Changes. */
jobject GetApplicationClassLoader(JNIEnv* jni, std::string* error) {
  jclass activity_thread = jni->FindClass("android/app/ActivityThread");
  if (ConsumeJniException(jni, "finding ActivityThread failed", error) ||
      activity_thread == nullptr) {
    return nullptr;
  }
  jmethodID current_application = jni->GetStaticMethodID(
      activity_thread, "currentApplication", "()Landroid/app/Application;");
  if (ConsumeJniException(
          jni, "finding ActivityThread.currentApplication failed", error) ||
      current_application == nullptr) {
    return nullptr;
  }
  jobject application = jni->CallStaticObjectMethod(
      activity_thread, current_application);
  if (ConsumeJniException(jni, "getting current application failed", error)) {
    return nullptr;
  }
  if (application == nullptr) {
    *error = "current application unavailable";
    return nullptr;
  }
  jclass application_class = jni->GetObjectClass(application);
  jfieldID loaded_apk_field = application_class == nullptr ? nullptr :
      jni->GetFieldID(
          application_class, "mLoadedApk", "Landroid/app/LoadedApk;");
  if (ConsumeJniException(jni, "finding Application.mLoadedApk failed", error) ||
      loaded_apk_field == nullptr) {
    return nullptr;
  }
  jobject loaded_apk = jni->GetObjectField(application, loaded_apk_field);
  if (loaded_apk == nullptr) {
    *error = "Application.mLoadedApk unavailable";
    return nullptr;
  }
  jclass loaded_apk_class = loaded_apk == nullptr ? nullptr :
      jni->GetObjectClass(loaded_apk);
  jmethodID get_class_loader = loaded_apk_class == nullptr ? nullptr :
      jni->GetMethodID(
          loaded_apk_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
  if (ConsumeJniException(jni, "finding LoadedApk.getClassLoader failed", error) ||
      get_class_loader == nullptr) {
    return nullptr;
  }
  jobject class_loader = jni->CallObjectMethod(loaded_apk, get_class_loader);
  if (ConsumeJniException(jni, "getting application class loader failed", error)) {
    return nullptr;
  }
  if (class_loader == nullptr) {
    *error = "application class loader unavailable";
    return nullptr;
  }
  return class_loader;
}

/** Appends new in-memory DEX elements to the Application ClassLoader. */
bool AppendNewDexElements(JNIEnv* jni, jobject class_loader,
                          jobjectArray dex_bytes, std::string* error) {
  jclass class_loader_class = jni->GetObjectClass(class_loader);
  jfieldID path_list_field = class_loader_class == nullptr ? nullptr :
      jni->GetFieldID(
          class_loader_class, "pathList", "Ldalvik/system/DexPathList;");
  if (ConsumeJniException(jni, "finding ClassLoader pathList failed", error) ||
      path_list_field == nullptr) {
    return false;
  }
  jobject path_list = jni->GetObjectField(class_loader, path_list_field);
  if (path_list == nullptr) {
    *error = "ClassLoader pathList unavailable";
    return false;
  }
  jclass path_list_class = path_list == nullptr ? nullptr :
      jni->GetObjectClass(path_list);
  jfieldID dex_elements_field = path_list_class == nullptr ? nullptr :
      jni->GetFieldID(path_list_class, "dexElements",
                      "[Ldalvik/system/DexPathList$Element;");
  if (ConsumeJniException(jni, "finding DexPathList.dexElements failed", error) ||
      dex_elements_field == nullptr) {
    return false;
  }
  jobject old_elements = jni->GetObjectField(path_list, dex_elements_field);
  if (old_elements == nullptr) {
    *error = "DexPathList.dexElements unavailable";
    return false;
  }
  jclass dex_utility = jni->FindClass(kDexUtilityClass);
  jmethodID create = dex_utility == nullptr ? nullptr :
      jni->GetStaticMethodID(
          dex_utility, "createNewDexElements",
          "([[B[Ljava/lang/Object;)[Ljava/lang/Object;");
  if (ConsumeJniException(jni, "finding DexUtility.createNewDexElements failed", error) ||
      create == nullptr) {
    return false;
  }
  jobject new_elements = jni->CallStaticObjectMethod(
      dex_utility, create, dex_bytes, old_elements);
  if (ConsumeJniException(jni, "creating new DEX elements failed", error) ||
      new_elements == nullptr) {
    return false;
  }
  jni->SetObjectField(path_list, dex_elements_field, new_elements);
  return !ConsumeJniException(jni, "updating DexPathList.dexElements failed", error);
}

/** Defines new classes with the same in-memory DexPathList flow as Apply Changes. */
bool DefineNewClasses(jvmtiEnv* jvmti, JNIEnv* jni,
                      const std::string& request_dir,
                      const std::vector<RequestEntry>& entries,
                      std::string* error) {
  if (entries.empty()) {
    return true;
  }
  const std::string app_data_dir = GetAppDataDir(request_dir);
  if (app_data_dir.empty() ||
      !deploy::LoadInstrumentationJarForApp(jvmti, jni, app_data_dir)) {
    *error = "instrumentation runtime unavailable";
    return false;
  }
  if (!deploy::RegisterNative(
          jni, {kDexUtilityClass, "makeInMemoryDexElements",
                "([Ljava/nio/ByteBuffer;Ljava/util/List;)[Ljava/lang/Object;",
                reinterpret_cast<void*>(&deploy::Native_MakeInMemoryDexElements)})) {
    *error = "DexUtility native registration failed";
    return false;
  }
  if (jni->PushLocalFrame(16) < 0) {
    ConsumeJniException(jni, "creating new class local frame failed", error);
    return false;
  }
  jobjectArray dex_bytes = CreateDexByteArrays(jni, entries, error);
  if (dex_bytes == nullptr) {
    jni->PopLocalFrame(nullptr);
    return false;
  }
  jobject class_loader = GetApplicationClassLoader(jni, error);
  if (class_loader == nullptr) {
    jni->PopLocalFrame(nullptr);
    return false;
  }
  const bool success = AppendNewDexElements(
      jni, class_loader, dex_bytes, error);
  jni->PopLocalFrame(nullptr);
  return success;
}

int ApplyRuntimeChanges(jvmtiEnv* jvmti, JNIEnv* jni,
                        const std::string& request_dir,
                        bool refresh_resources, bool restart_activity) {
  const std::string app_data_dir = GetAppDataDir(request_dir);
  if (app_data_dir.empty() ||
      !deploy::LoadInstrumentationJarForApp(jvmti, jni, app_data_dir)) {
    return refresh_resources ? 1 : 2;
  }
  jclass hooks = jni->FindClass(
      "com/sickworm/intellij/jugg/instrument/DirectActivityRelauncher");
  if (hooks == nullptr || jni->ExceptionCheck()) {
    jni->ExceptionClear();
    return refresh_resources ? 1 : 2;
  }
  jmethodID apply = jni->GetStaticMethodID(
      hooks, "applyChanges", "(Ljava/lang/String;ZZ)I");
  if (apply == nullptr || jni->ExceptionCheck()) {
    jni->ExceptionClear();
    jni->DeleteLocalRef(hooks);
    return refresh_resources ? 1 : 2;
  }
  jstring data_dir = jni->NewStringUTF(app_data_dir.c_str());
  const jint result = jni->CallStaticIntMethod(
      hooks, apply, data_dir, refresh_resources ? JNI_TRUE : JNI_FALSE,
      restart_activity ? JNI_TRUE : JNI_FALSE);
  if (jni->ExceptionCheck()) {
    jni->ExceptionDescribe();
    jni->ExceptionClear();
    jni->DeleteLocalRef(data_dir);
    jni->DeleteLocalRef(hooks);
    return refresh_resources ? 1 : 2;
  }
  jni->DeleteLocalRef(data_dir);
  jni->DeleteLocalRef(hooks);
  return result;
}

void ReleaseLoadedClasses(jvmtiEnv* jvmti, JNIEnv* jni, jint count,
                          jclass* classes) {
  if (classes == nullptr) {
    return;
  }
  for (jint i = 0; i < count; ++i) {
    jni->DeleteLocalRef(classes[i]);
  }
  jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
}

}  // namespace

jint HandleHotReloadRequest(jvmtiEnv* jvmti, JNIEnv* jni,
                            const std::string& request_dir) {
  if (!IsSafeRequestDir(request_dir)) {
    __android_log_print(ANDROID_LOG_ERROR, "jugg-jvmti",
                        "Invalid Hot Reload request dir: %s",
                        request_dir.c_str());
    return JNI_OK;
  }
  std::vector<RequestEntry> new_classes;
  std::vector<RequestEntry> modified_classes;
  bool refresh_resources = false;
  bool restart_activity = false;
  std::string parse_error;
  if (!ParseRequest(request_dir, &new_classes, &modified_classes, &refresh_resources,
                    &restart_activity, &parse_error)) {
    WriteResult(request_dir, "ERROR\tparse\t0\t" + CleanDetail(parse_error));
    return JNI_OK;
  }

  std::string define_error;
  if (!DefineNewClasses(jvmti, jni, request_dir, new_classes, &define_error)) {
    WriteResult(request_dir, "ERROR\tdefine_new_classes\t0\t" +
        CleanDetail(define_error));
    return JNI_OK;
  }

  if (!modified_classes.empty()) {
    jvmtiCapabilities potential = {};
    jvmti->GetPotentialCapabilities(&potential);
    jvmti->AddCapabilities(&potential);
    jvmtiError error = jvmti->AddCapabilities(&deploy::REQUIRED_CAPABILITIES);
    if (error != JVMTI_ERROR_NONE) {
      WriteResult(request_dir, "ERROR\tcapabilities\t" +
          std::to_string(error) + "\tAddCapabilities failed");
      return JNI_OK;
    }

    jint loaded_count = 0;
    jclass* loaded_classes = nullptr;
    error = jvmti->GetLoadedClasses(&loaded_count, &loaded_classes);
    if (error != JVMTI_ERROR_NONE) {
      WriteResult(request_dir, "ERROR\tfind_classes\t" +
          std::to_string(error) + "\tGetLoadedClasses failed");
      return JNI_OK;
    }

    for (size_t entry_index = 0;
         entry_index < modified_classes.size(); ++entry_index) {
      RequestEntry& entry = modified_classes[entry_index];
      for (jint class_index = 0; class_index < loaded_count; ++class_index) {
        char* signature = nullptr;
        if (jvmti->GetClassSignature(loaded_classes[class_index], &signature,
                                     nullptr) == JVMTI_ERROR_NONE) {
          const bool matched = signature != nullptr && entry.name == signature;
          if (signature != nullptr) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
          }
          if (matched) {
            entry.klass = loaded_classes[class_index];
            break;
          }
        }
      }
      if (entry.klass == nullptr) {
        ReleaseLoadedClasses(jvmti, jni, loaded_count, loaded_classes);
        WriteResult(request_dir, "CLASS_NOT_FOUND\t" + entry.name);
        return JNI_OK;
      }
      jboolean modifiable = JNI_FALSE;
      error = jvmti->IsModifiableClass(entry.klass, &modifiable);
      if (error != JVMTI_ERROR_NONE || modifiable != JNI_TRUE) {
        ReleaseLoadedClasses(jvmti, jni, loaded_count, loaded_classes);
        WriteResult(request_dir, "UNMODIFIABLE\t" + entry.name);
        return JNI_OK;
      }
    }

    std::vector<jvmtiClassDefinition> definitions(modified_classes.size());
    for (size_t i = 0; i < modified_classes.size(); ++i) {
      definitions[i].klass = modified_classes[i].klass;
      definitions[i].class_byte_count =
          static_cast<jint>(modified_classes[i].dex.size());
      definitions[i].class_bytes = modified_classes[i].dex.data();
    }
    error = jvmti->RedefineClasses(static_cast<jint>(definitions.size()),
                                   definitions.data());
    ReleaseLoadedClasses(jvmti, jni, loaded_count, loaded_classes);
    if (error != JVMTI_ERROR_NONE) {
      WriteResult(request_dir, "ERROR\tredefine\t" + std::to_string(error) +
          "\tRedefineClasses failed");
      return JNI_OK;
    }
  }

  if (refresh_resources || restart_activity) {
    const int apply_result = ApplyRuntimeChanges(
        jvmti, jni, request_dir, refresh_resources, restart_activity);
    if (apply_result == 1) {
      WriteResult(request_dir, "ERROR\trefresh_resources\t0\tResource refresh failed");
      return JNI_OK;
    }
    if (apply_result != 0) {
      WriteResult(request_dir, "ERROR\trestart_activity\t0\tActivity relaunch failed");
      return JNI_OK;
    }
  }

  WriteResult(request_dir, "OK\t" + std::to_string(new_classes.size()) + "\t" +
      std::to_string(modified_classes.size()) + "\t" + std::to_string(getpid()));
  return JNI_OK;
}
