#include "class_redefiner.h"

#include <android/log.h>
#include <unistd.h>

#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

#include "capabilities.h"
#include "instrumenter.h"

namespace {

const char* kProtocol = "JUGG_HOT_RELOAD_V3";
const char* kRefreshResources = "REFRESH_RESOURCES";
const char* kRestartActivity = "RESTART_ACTIVITY";
const int kMaxClassCount = 512;
const size_t kMaxDexSize = 16 * 1024 * 1024;
const size_t kMaxTotalDexSize = 64 * 1024 * 1024;

struct RequestEntry {
  std::string descriptor;
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
                  std::vector<RequestEntry>* entries,
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
    const size_t separator = line.find('\t');
    if (separator == std::string::npos || entries->size() >= kMaxClassCount) {
      *error = "invalid request entry";
      return false;
    }
    RequestEntry entry;
    entry.descriptor = line.substr(0, separator);
    entry.relative_path = line.substr(separator + 1);
    if (entry.descriptor.size() < 3 || entry.descriptor[0] != 'L' ||
        entry.descriptor[entry.descriptor.size() - 1] != ';' ||
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
    entries->push_back(std::move(entry));
  }
  if (entries->empty() && !*refresh_resources) {
    *error = "empty request";
    return false;
  }
  return true;
}

std::string GetAppDataDir(const std::string& request_dir) {
  const std::string marker = "/code_cache/jugg_hot_reload/";
  const size_t marker_index = request_dir.find(marker);
  return marker_index == std::string::npos ? "" : request_dir.substr(0, marker_index);
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
  std::vector<RequestEntry> entries;
  bool refresh_resources = false;
  bool restart_activity = false;
  std::string parse_error;
  if (!ParseRequest(request_dir, &entries, &refresh_resources,
                    &restart_activity, &parse_error)) {
    WriteResult(request_dir, "ERROR\tparse\t0\t" + CleanDetail(parse_error));
    return JNI_OK;
  }

  if (!entries.empty()) {
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

    for (size_t entry_index = 0; entry_index < entries.size(); ++entry_index) {
      RequestEntry& entry = entries[entry_index];
      for (jint class_index = 0; class_index < loaded_count; ++class_index) {
        char* signature = nullptr;
        if (jvmti->GetClassSignature(loaded_classes[class_index], &signature,
                                     nullptr) == JVMTI_ERROR_NONE) {
          const bool matched = signature != nullptr && entry.descriptor == signature;
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
        WriteResult(request_dir, "MISSING\t" + entry.descriptor);
        return JNI_OK;
      }
      jboolean modifiable = JNI_FALSE;
      error = jvmti->IsModifiableClass(entry.klass, &modifiable);
      if (error != JVMTI_ERROR_NONE || modifiable != JNI_TRUE) {
        ReleaseLoadedClasses(jvmti, jni, loaded_count, loaded_classes);
        WriteResult(request_dir, "UNMODIFIABLE\t" + entry.descriptor);
        return JNI_OK;
      }
    }

    std::vector<jvmtiClassDefinition> definitions(entries.size());
    for (size_t i = 0; i < entries.size(); ++i) {
      definitions[i].klass = entries[i].klass;
      definitions[i].class_byte_count = static_cast<jint>(entries[i].dex.size());
      definitions[i].class_bytes = entries[i].dex.data();
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

  WriteResult(request_dir, "OK\t" + std::to_string(entries.size()) + "\t" +
      std::to_string(getpid()));
  return JNI_OK;
}
