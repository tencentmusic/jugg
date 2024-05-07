/*
 * Copyright (C) 2015 The Android Open Source Project
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
 */

#include "IncLink.h"
#include "Link.cpp"

#include <iostream>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace aapt {

class IncLinker: private Linker {
  public:
  IncLinker(LinkContext* context, const LinkOptions& options) : Linker(context, options) {}
  
  void updateContextOnInc(LinkContext* context, LinkOptions& options) {
    options_ = options;
    context_->updateContextOnInc(context);
  }

  // Creates a SymbolTable that loads symbols from the various APKs.
  // Pre-condition: context_->GetCompilationPackage() needs to be set.
  bool LoadSymbolsFromIncludePathsApk() {
    for (const std::string& path : options_.include_paths) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage() << "including binary APK " << path);
      }

      std::string error;
      auto zip_collection = io::ZipFileCollection::Create(path, &error);
      if (zip_collection == nullptr) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed to open binary APK: " << error);
        return false;
      }

      if (zip_collection->FindFile(kApkResourceTablePath) != nullptr) {
        std::unique_ptr<LoadedApk> binaryApk = LoadedApk::LoadApkFromPath(path, context_->GetDiagnostics());
        if (!binaryApk) {
          context_->GetDiagnostics()->Error(
              DiagMessage() << "load binary APK failed");
          return false;
        }
        ResourceTable* table = binaryApk->GetResourceTable();

        context_->GetExternalSymbols()->AppendSource(
            util::make_unique<ResourceTableSymbolSource>(table));
        static_library_includes_.push_back(std::move(binaryApk));
      }
    }

    return true;
  }

  void processStyleableFile(const std::string& styleables_path) {
    std::ifstream file(styleables_path);
    if (!file.is_open()) {
          context_->GetDiagnostics()->Warn(DiagMessage() << "Error: Unable to open file " << styleables_path);
      return;
    }

    std::string line;
    while (std::getline(file, line)) {
      if (line.empty()) {
        continue;
      }

      for (const auto& pkg : final_table_.packages) {
        ResourceTableType* styleableType = pkg->FindOrCreateType(ResourceType::kStyleable);
        styleableType->visibility_level = Visibility::Level::kPublic;
        ResourceTableType* attrType = pkg->FindOrCreateType(ResourceType::kAttr);

        std::istringstream line_stream(line);
        std::string styleableName;
        std::string attrs;
        if (!std::getline(line_stream, styleableName, ':')) {
          context_->GetDiagnostics()->Error(DiagMessage() << "Error: Unable to parse line:" << line);
          continue;
        }
        ResourceEntry* entry = styleableType->FindOrCreateEntry(android::StringPiece(styleableName), Maybe<uint16_t>());
        ResourceConfigValue* value = entry->FindOrCreateValue({}, {});
        std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();

        if (!std::getline(line_stream, attrs, ':')) {
          // styleables without any attrs are allowed
          value->value.reset(styleable.release());
          continue;
        }
        
        std::istringstream attrs_stream(attrs);
        std::string attr;
        std::string originAttr;
        std::string packageName;
        while (std::getline(attrs_stream, attr, ',')) {
          originAttr = attr;
          android::StringPiece attrName = android::StringPiece(attr);
          ResourceNameRef referenceNameRef = ResourceNameRef(android::StringPiece(), ResourceType::kAttr, attrName);
          bool isFound = false;
          if (attrType->FindEntry(attrName)) {
              isFound = true;
          } else {
            size_t underscorePos = attr.find('_');
            if (underscorePos != std::string::npos) {
              // e.g. "android_layout_gravity"
              packageName = attr.substr(0, underscorePos);  // maybe "android"
              attr = attr.substr(underscorePos + 1);
              attrName = android::StringPiece(attr);
              referenceNameRef = ResourceNameRef(packageName, ResourceType::kAttr, attrName);
              if (context_->GetExternalSymbols()->FindByName(referenceNameRef.ToResourceName())) {
                isFound = true;
              }
            }
          }
          if (!isFound) {
            context_->GetDiagnostics()->Warn(DiagMessage() << "Unable to find attr " << originAttr << ", ignore attr");
            continue;
          }

          Reference reference = Reference(referenceNameRef);
          styleable->entries.push_back(std::move(reference));
        }

        value->value.reset(styleable.release());
      }
    }

    file.close();
  }

  int LoadFromApk(const std::vector<std::string>& input_files) {
    // load table from .apk
    if (input_files.size() == 0) {
      context_->GetDiagnostics()->Error(
          DiagMessage() << "no apk provided");
      return 1;
    }
    if (input_files.size() != 1) {
      context_->GetDiagnostics()->Error(
          DiagMessage() << "multiply apk load not supported");
      return 1;
    }

    // load table and manifest from apk
    std::unique_ptr<LoadedApk> apk_ = LoadedApk::LoadApkFromPath(input_files[0], context_->GetDiagnostics());
    if (!apk_) {
      context_->GetDiagnostics()->Error(
          DiagMessage() << "load apk failed");
      return 1;
    }


    // init compile context
    manifest_xml_ = apk_->GetManifest()->Clone();

    ResourceTable* apk_table = apk_->GetResourceTable();
    for (const auto& pkg : apk_table->packages) {
      ResourceTablePackage* new_pkg = final_table_.CreatePackage(pkg->name, pkg->id);
      for (const auto& type : pkg->types) {
        ResourceTableType* new_type = new_pkg->FindOrCreateType(type->type);
        new_type->id = type->id;
        new_type->visibility_level = type->visibility_level;

        for (const auto& entry : type->entries) {
          ResourceEntry* new_entry = new_type->FindOrCreateEntry(entry->name);
          new_entry->id = entry->id;
          new_entry->visibility = entry->visibility;
          new_entry->allow_new = entry->allow_new;
          new_entry->overlayable_item = entry->overlayable_item;

          for (const auto& config_value : entry->values) {
            ResourceConfigValue* new_value =
                new_entry->FindOrCreateValue(config_value->config, config_value->product);
            new_value->value.reset(config_value->value->Clone(&final_table_.string_pool));
          }
        }
      }
    }



    // import styleable
    if (!LoadSymbolsFromIncludePathsApk()) {
      return 1;
    }
    if (options_.styleable_path) {
      processStyleableFile(options_.styleable_path.value());
    }

    // origin Link process with out MergePath and LinkReference

    // First extract the Package name without modifying it (via --rename-manifest-package).
    if (Maybe<AppInfo> maybe_app_info =
            ExtractAppInfoFromBinaryManifest(*manifest_xml_.get(), context_->GetDiagnostics())) {
      const AppInfo& app_info = maybe_app_info.value();
      context_->SetCompilationPackage(app_info.package);
    }

    // Determine the package name under which to merge resources.
    if (options_.rename_resources_package) {
      if (!options_.custom_java_package) {
        // Generate the R.java under the original package name instead of the package name specified
        // through --rename-resources-package.
        options_.custom_java_package = context_->GetCompilationPackage();
      }
      context_->SetCompilationPackage(options_.rename_resources_package.value());
    }

    // Now that the compilation package is set, load the dependencies. This will also extract
    // the Android framework's versionCode and versionName, if they exist.
    if (!LoadSymbolsFromIncludePaths()) {
      return 1;
    }
    
    ManifestFixer manifest_fixer(options_.manifest_fixer_options);
    if (!manifest_fixer.Consume(context_, manifest_xml_.get())) {
      return 1;
    }

    Maybe<AppInfo> maybe_app_info =
        ExtractAppInfoFromBinaryManifest(*manifest_xml_.get(), context_->GetDiagnostics());
    if (!maybe_app_info) {
      return 1;
    }

    app_info_ = maybe_app_info.value();
    context_->SetMinSdkVersion(app_info_.min_sdk_version.value_or_default(0));

    context_->SetNameManglerPolicy(NameManglerPolicy{context_->GetCompilationPackage()});
    context_->SetSplitNameDependencies(app_info_.split_name_dependencies);

    // Override the package ID when it is "android".
    if (context_->GetCompilationPackage() == "android") {
      context_->SetPackageId(0x01);

      // Verify we're building a regular app.
      if (context_->GetPackageType() != PackageType::kApp) {
        context_->GetDiagnostics()->Error(
            DiagMessage() << "package 'android' can only be built as a regular app");
        return 1;
      }
    }

    if (context_->IsVerbose()) {
      context_->GetDiagnostics()->Note(DiagMessage()
                                       << StringPrintf("linking package '%s' using package ID %02x",
                                                       context_->GetCompilationPackage().data(),
                                                       context_->GetPackageId()));
    }

    // Extract symbols from AndroidManifest.xml, since this isn't merged like the other XML files
    // in res/**/*.
    {
      XmlIdCollector collector;
      if (!collector.Consume(context_, manifest_xml_.get())) {
        return false;
      }

      if (!MergeExportedSymbols(manifest_xml_->file.source, manifest_xml_->file.exported_symbols)) {
        return false;
      }
    }

    // Add our table to the symbol table.
    context_->GetExternalSymbols()->PrependSource(
        util::make_unique<ResourceTableSymbolSource>(&final_table_));

    // Workaround for pre-O runtime that would treat negative resource IDs
    // (any ID with a package ID > 7f) as invalid. Intercept any ID (PPTTEEEE) with PP > 0x7f
    // and type == 'id', and return the ID 0x7fPPEEEE. IDs don't need to be real resources, they
    // are just identifiers.
    if (context_->GetMinSdkVersion() < SDK_O && context_->GetPackageType() == PackageType::kApp) {
      if (context_->IsVerbose()) {
        context_->GetDiagnostics()->Note(DiagMessage()
                                         << "enabling pre-O feature split ID rewriting");
      }
      context_->GetExternalSymbols()->SetDelegate(
          util::make_unique<FeatureSplitSymbolTableDelegate>(context_));
    }

    // Before we process anything, remove the resources whose default values don't exist.
    // We want to force any references to these to fail the build.
    if (!options_.no_resource_removal) {
      if (!NoDefaultResourceRemover{}.Consume(context_, &final_table_)) {
        context_->GetDiagnostics()->Error(DiagMessage()
                                          << "failed removing resources with no defaults");
        return 1;
      }
    }

    return 0;
  }

  void readTableCount(std::map<ResourceType, int> &entry_count, std::map<ResourceType, int> &config_count) {
    for (const auto& pkg : final_table_.packages) {
      for (const auto& type : pkg->types) {
        entry_count[type->type] = 0;
        config_count[type->type] = 0;
        for (const auto& entry : type->entries) {
          entry_count[type->type]++;
          for (const auto& config_value : entry->values) {
            config_count[type->type]++;
          }
        }
      }
    }
  }

  int IncLinkAdv(const std::vector<std::string>& input_files) {
    proguard::KeepSet proguard_main_dex_keep_set;

    TableMergerOptions table_merger_options;
    table_merger_options.auto_add_overlay = true;
    table_merger_options.override_styles_instead_of_overlaying =
        options_.override_styles_instead_of_overlaying;
    table_merger_options.strict_visibility = options_.strict_visibility;
    table_merger_ = util::make_unique<TableMerger>(context_, &final_table_, table_merger_options);

    // read origin table count
    std::map<ResourceType, int> originEntryCount;
    std::map<ResourceType, int> originConfigCount;
    readTableCount(originEntryCount, originConfigCount);

    if (context_->IsVerbose()) {
      std::map<ResourceType, int> ::iterator it;
      std::map<ResourceType, int> ::iterator itEnd;
      it = originEntryCount.begin();
      itEnd = originEntryCount.end();
      while (it != itEnd) {
        context_->GetDiagnostics()->Note(DiagMessage("before_table_count") 
          << it->first 
          << " has entry count " 
          << it->second
          << ", config count "
          << originConfigCount[it->first]
          );
        it++;
      }
    }

    for (const std::string& input : input_files) {
      if (!MergePath(input, true)) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed parsing input");
        return 1;
      }
    }

    // read current table count
    std::map<ResourceType, int> currentEntryCount;
    std::map<ResourceType, int> currentConfigCount;
    readTableCount(currentEntryCount, currentConfigCount);

    if (context_->IsVerbose()) {
      std::map<ResourceType, int> ::iterator it;
      std::map<ResourceType, int> ::iterator itEnd;
      it = currentEntryCount.begin();
      itEnd = currentEntryCount.end();
      while (it != itEnd) {
        context_->GetDiagnostics()->Note(DiagMessage("after_table_count") 
          << it->first 
          << " has entry count " 
          << it->second
          << ", config count "
          << currentConfigCount[it->first]
          );
        it++;
      }
    }

    // print difference
    std::map<ResourceType, int> ::iterator it;
    std::map<ResourceType, int> ::iterator itEnd;
    it = currentEntryCount.begin();
    itEnd = currentEntryCount.end();
    bool isRJavaChange = false;
    while (it != itEnd) {
      std::map<ResourceType, int>::iterator iterator = originEntryCount.find(it->first);
      int originEntryCountValue = 0;
      if (iterator != originEntryCount.end()) {
        originEntryCountValue = iterator->second;
      }
      int currentEntryCountValue = it->second;
      if (originEntryCountValue != currentEntryCountValue) {
        isRJavaChange = true;
        context_->GetDiagnostics()->Note(DiagMessage("table_count_changes") 
          << it->first 
          << " entry count changed from " 
          << originEntryCountValue
          << " to "
          << currentEntryCountValue
          );
      }
      it++;
    }

    context_->GetDiagnostics()->Note(DiagMessage("table_count_changes") 
          << "isRJavaChange "
          << (isRJavaChange ? "true" : "false")
          );

    // Assign IDs if we are building a regular app.
    IdAssigner id_assigner(&options_.stable_id_map);
    if (!id_assigner.Consume(context_, &final_table_)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed assigning IDs");
      return 1;
    }

    ReferenceLinker linker;
    if (!options_.merge_only && !linker.Consume(context_, &final_table_)) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed linking references");
      return 1;
    }


    // Start writing the base APK.
    std::unique_ptr<IArchiveWriter> archive_writer = MakeArchiveWriter(options_.output_path);
    if (!archive_writer) {
      context_->GetDiagnostics()->Error(DiagMessage() << "failed to create archive");
      return 1;
    }

    // output manifest
    // Load the AndroidManifest.xml
    FileInputStream fin(options_.manifest_path);
    if (!fin.HadError()) {
      // file exists, only compile it when we need it
      std::unique_ptr<xml::XmlResource> manifest_xml =
          LoadXml(options_.manifest_path, context_->GetDiagnostics());
      if (!manifest_xml) {
        context_->GetDiagnostics()->Error(DiagMessage() << "failed to load AndroidManifest.xml");
        return 1;
      }

      bool error = false;
      {
        // AndroidManifest.xml has no resource name, but the CallSite is built from the name
        // (aka, which package the AndroidManifest.xml is coming from).
        // So we give it a package name so it can see local resources.
        manifest_xml->file.name.package = context_->GetCompilationPackage();

        XmlReferenceLinker manifest_linker;
        if (options_.merge_only || manifest_linker.Consume(context_, manifest_xml.get())) {
          if (options_.generate_proguard_rules_path &&
              !proguard::CollectProguardRulesForManifest(manifest_xml.get(), &proguard_keep_set_)) {
            error = true;
          }

          if (options_.generate_main_dex_proguard_rules_path &&
              !proguard::CollectProguardRulesForManifest(manifest_xml.get(),
                                                        &proguard_main_dex_keep_set, true)) {
            error = true;
          }

          if (options_.generate_java_class_path) {
            if (!WriteManifestJavaFile(manifest_xml.get())) {
              error = true;
            }
          }

          if (options_.no_xml_namespaces) {
            // PackageParser will fail if URIs are removed from
            // AndroidManifest.xml.
            XmlNamespaceRemover namespace_remover(true /* keepUris */);
            if (!namespace_remover.Consume(context_, manifest_xml.get())) {
              error = true;
            }
          }
        } else {
          error = true;
        }

        if (error) {
          context_->GetDiagnostics()->Error(DiagMessage() << "failed processing manifest");
          return 1;
        }

        manifest_xml_ = std::move(manifest_xml);
      }

    }

    if (!WriteApk(archive_writer.get(), &proguard_keep_set_, manifest_xml_.get(), &final_table_)) {
      return 1;
    }

    if (!CopyAssetsDirsToApk(archive_writer.get())) {
      return 1;
    }

    if (options_.generate_java_class_path || options_.generate_text_symbols_path) {
      if (isRJavaChange) {
        if (!GenerateJavaClasses()) {
          return 1;
        }
      }
    }

    if (!WriteProguardFile(options_.generate_proguard_rules_path, proguard_keep_set_)) {
      return 1;
    }

    if (!WriteProguardFile(options_.generate_main_dex_proguard_rules_path,
                           proguard_main_dex_keep_set)) {
      return 1;
    }
    return 0;
  }

  private:
    std::unique_ptr<xml::XmlResource> manifest_xml_;
    proguard::KeepSet proguard_keep_set_;
};
IncLinker *_cacheIncLink = NULL;

int IncLinkCommand::Action(const std::vector<std::string>& args) {
  TRACE_FLUSH(trace_folder_ ? trace_folder_.value() : "", "IncLinkCommand::Action");
  LinkContext* context = new LinkContext(diag_);

  // Expand all argument-files passed into the command line. These start with '@'.
  std::vector<std::string> arg_list;
  for (const std::string& arg : args) {
    if (util::StartsWith(arg, "@")) {
      const std::string path = arg.substr(1, arg.size() - 1);
      std::string error;
      if (!file::AppendArgsFromFile(path, &arg_list, &error)) {
        context->GetDiagnostics()->Error(DiagMessage(path) << error);
        delete context;
        return 1;
      }
    } else {
      arg_list.push_back(arg);
    }
  }

  // Expand all argument-files passed to -R.
  for (const std::string& arg : overlay_arg_list_) {
    if (util::StartsWith(arg, "@")) {
      const std::string path = arg.substr(1, arg.size() - 1);
      std::string error;
      if (!file::AppendArgsFromFile(path, &options_.overlay_files, &error)) {
        context->GetDiagnostics()->Error(DiagMessage(path) << error);
        delete context;
        return 1;
      }
    } else {
      options_.overlay_files.push_back(arg);
    }
  }

  if (verbose_) {
    context->SetVerbose(verbose_);
  }

  if (int{shared_lib_} + int{static_lib_} + int{proto_format_} > 1) {
    context->GetDiagnostics()->Error(
        DiagMessage()
            << "only one of --shared-lib, --static-lib, or --proto_format can be defined");
    delete context;
    return 1;
  }

  if (options_.merge_only && !static_lib_) {
    context->GetDiagnostics()->Error(
        DiagMessage() << "the --merge-only flag can be only used when building a static library");
    delete context;
    return 1;
  }

  // The default build type.
  context->SetPackageType(PackageType::kApp);
  context->SetPackageId(kAppPackageId);

  if (shared_lib_) {
    context->SetPackageType(PackageType::kSharedLib);
    context->SetPackageId(0x00);
  } else if (static_lib_) {
    context->SetPackageType(PackageType::kStaticLib);
    options_.output_format = OutputFormat::kProto;
  } else if (proto_format_) {
    options_.output_format = OutputFormat::kProto;
  }

  if (package_id_) {
    if (context->GetPackageType() != PackageType::kApp) {
      context->GetDiagnostics()->Error(
          DiagMessage() << "can't specify --package-id when not building a regular app");
      delete context;
      return 1;
    }

    const Maybe<uint32_t> maybe_package_id_int = ResourceUtils::ParseInt(package_id_.value());
    if (!maybe_package_id_int) {
      context->GetDiagnostics()->Error(DiagMessage() << "package ID '" << package_id_.value()
                                                    << "' is not a valid integer");
      delete context;
      return 1;
    }

    const uint32_t package_id_int = maybe_package_id_int.value();
    if (package_id_int > std::numeric_limits<uint8_t>::max()
        || package_id_int == kFrameworkPackageId
        || (!options_.allow_reserved_package_id && package_id_int < kAppPackageId)) {
      context->GetDiagnostics()->Error(
          DiagMessage() << StringPrintf(
              "invalid package ID 0x%02x. Must be in the range 0x7f-0xff.", package_id_int));
      delete context;
      return 1;
    }
    context->SetPackageId(static_cast<uint8_t>(package_id_int));
  }

  // Populate the set of extra packages for which to generate R.java.
  for (std::string& extra_package : extra_java_packages_) {
    // A given package can actually be a colon separated list of packages.
    for (StringPiece package : util::Split(extra_package, ':')) {
      options_.extra_java_packages.insert(package.to_string());
    }
  }

  if (product_list_) {
    for (StringPiece product : util::Tokenize(product_list_.value(), ',')) {
      if (product != "" && product != "default") {
        options_.products.insert(product.to_string());
      }
    }
  }

  std::unique_ptr<IConfigFilter> filter;
  if (!configs_.empty()) {
    filter = ParseConfigFilterParameters(configs_, context->GetDiagnostics());
    if (filter == nullptr) {
      delete context;
      return 1;
    }
    options_.table_splitter_options.config_filter = filter.get();
  }

  if (preferred_density_) {
    Maybe<uint16_t> density =
        ParseTargetDensityParameter(preferred_density_.value(), context->GetDiagnostics());
    if (!density) {
      delete context;
      return 1;
    }
    options_.table_splitter_options.preferred_densities.push_back(density.value());
  }

  // Parse the split parameters.
  for (const std::string& split_arg : split_args_) {
    options_.split_paths.push_back({});
    options_.split_constraints.push_back({});
    if (!ParseSplitParameter(split_arg, context->GetDiagnostics(), &options_.split_paths.back(),
        &options_.split_constraints.back())) {
      delete context;
      return 1;
    }
  }

  if (context->GetPackageType() != PackageType::kStaticLib && stable_id_file_path_) {
    if (!LoadStableIdMap(context->GetDiagnostics(), stable_id_file_path_.value(),
        &options_.stable_id_map)) {
      delete context;
      return 1;
    }
  }

  if (no_compress_regex) {
    std::string regex = no_compress_regex.value();
    if (util::StartsWith(regex, "@")) {
      const std::string path = regex.substr(1, regex.size() -1);
      std::string error;
      if (!file::AppendSetArgsFromFile(path, &options_.extensions_to_not_compress, &error)) {
        context->GetDiagnostics()->Error(DiagMessage(path) << error);
        delete context;
        return 1;
      }
    } else {
      options_.regex_to_not_compress = GetRegularExpression(no_compress_regex.value());
    }
  }

  // Populate some default no-compress extensions that are already compressed.
  options_.extensions_to_not_compress.insert({
      // Image extensions
      ".jpg", ".jpeg", ".png", ".gif", ".webp",
      // Audio extensions
      ".wav", ".mp2", ".mp3", ".ogg", ".aac", ".mid", ".midi", ".smf", ".jet", ".rtttl", ".imy",
      ".xmf", ".amr", ".awb",
      // Audio/video extensions
      ".mpg", ".mpeg", ".mp4", ".m4a", ".m4v", ".3gp", ".3gpp", ".3g2", ".3gpp2", ".wma", ".wmv",
      ".webm", ".mkv"});

  // Turn off auto versioning for static-libs.
  if (context->GetPackageType() == PackageType::kStaticLib) {
    options_.no_auto_version = true;
    options_.no_version_vectors = true;
    options_.no_version_transitions = true;
  }

  if (load_apk_) {
    delete _cacheIncLink;
    IncLinker *inclink = new IncLinker(context, options_);
    int result =  inclink->LoadFromApk(arg_list);
    if (result == 0) {
      _cacheIncLink = inclink;
    } else {
      delete context;
      delete inclink;
    }
    return result;
  } else {
    if (_cacheIncLink == NULL) {
      context->GetDiagnostics()->Error(DiagMessage() << "no cache data found, run with --load first");
      return 1;
    }
    _cacheIncLink->updateContextOnInc(context, options_);
    delete context;
    return _cacheIncLink->IncLinkAdv(arg_list);
  }
}

}  // namespace aapt
