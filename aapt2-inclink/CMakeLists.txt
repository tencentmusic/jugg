cmake_minimum_required(VERSION 3.19)
project(aapt2_inclink LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_FLAGS_DEBUG "-O0 -g -gdwarf-4")
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -g -DNDEBUG -UDEBUG")
set(AAPT2_DEPS_ROOT "${CMAKE_CURRENT_LIST_DIR}/build/deps14" CACHE PATH "AAPT2 source dependency directory")
set(D "${AAPT2_DEPS_ROOT}")
set(AAPT2_BUILD_NUMBER "" CACHE STRING "AAPT2 build number embedded in the version output")
set(AAPT2_PROTOC_EXECUTABLE "" CACHE FILEPATH "Host protoc used to generate AAPT2 protobuf sources during cross compilation")
set(AAPT2_LOCAL_DEPS_ROOT "${D}" CACHE PATH "Pinned third-party source dependencies")
set(AAPT2_LIBPNG_INCLUDE_DIR "${AAPT2_LOCAL_DEPS_ROOT}/libpng")
set(AAPT2_ZLIB_INCLUDE_DIR "${AAPT2_LOCAL_DEPS_ROOT}/zlib")
set(AAPT2_EXPAT_INCLUDE_DIR "${AAPT2_LOCAL_DEPS_ROOT}/expat/expat/lib")
set(AAPT2_LIBPNG_LIBRARY png_static)
set(AAPT2_Z_LIBRARY zlibstatic)
set(AAPT2_EXPAT_LIBRARY expat)
set(AAPT2_BASE_CMSG_SOURCE "${D}/base/cmsg.cpp")
if(WIN32)
    set(AAPT2_BASE_CMSG_SOURCE)
    set(AAPT2_BASE_PLATFORM_SOURCES
        "${D}/base/errors_windows.cpp"
        "${D}/base/utf8.cpp")
else()
    set(AAPT2_BASE_PLATFORM_SOURCES "${D}/base/errors_unix.cpp")
endif()

foreach(required_path IN ITEMS AAPT2_LIBPNG_INCLUDE_DIR AAPT2_ZLIB_INCLUDE_DIR AAPT2_EXPAT_INCLUDE_DIR)
    if(NOT EXISTS "${${required_path}}")
        message(FATAL_ERROR "Missing AAPT2 dependency: ${${required_path}}")
    endif()
endforeach()
set(CMAKE_SKIP_INSTALL_RULES TRUE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(EXPAT_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(PNG_SHARED OFF CACHE BOOL "" FORCE)
set(PNG_BUILD_ZLIB ON CACHE BOOL "" FORCE)
set(ZLIB_INCLUDE_DIR "${AAPT2_LOCAL_DEPS_ROOT}/zlib" CACHE PATH "" FORCE)
set(ZLIB_INCLUDE_DIRS "${AAPT2_LOCAL_DEPS_ROOT}/zlib;${CMAKE_CURRENT_BINARY_DIR}/zlib" CACHE STRING "" FORCE)
set(ZLIB_LIBRARIES zlibstatic CACHE STRING "" FORCE)
set(protobuf_WITH_ZLIB OFF CACHE BOOL "" FORCE)
add_subdirectory("${AAPT2_LOCAL_DEPS_ROOT}/zlib" "${CMAKE_CURRENT_BINARY_DIR}/zlib" EXCLUDE_FROM_ALL)
add_subdirectory("${AAPT2_LOCAL_DEPS_ROOT}/expat/expat" "${CMAKE_CURRENT_BINARY_DIR}/expat" EXCLUDE_FROM_ALL)
set(PNG_TESTS OFF CACHE BOOL "" FORCE)
set(PNG_HARDWARE_OPTIMIZATIONS OFF CACHE BOOL "" FORCE)
if(APPLE)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -UTARGET_OS_MAC")
endif()
include_directories("${AAPT2_LOCAL_DEPS_ROOT}/zlib" "${CMAKE_CURRENT_BINARY_DIR}/zlib"
    "${AAPT2_LOCAL_DEPS_ROOT}/libpng" "${CMAKE_CURRENT_BINARY_DIR}/libpng")
add_subdirectory("${AAPT2_LOCAL_DEPS_ROOT}/libpng" "${CMAKE_CURRENT_BINARY_DIR}/libpng" EXCLUDE_FROM_ALL)
set_property(TARGET png_static PROPERTY INTERFACE_LINK_LIBRARIES zlibstatic)
include_directories(${CMAKE_CURRENT_LIST_DIR}/source ${CMAKE_CURRENT_BINARY_DIR})
include_directories(${D}/androidfw/include)
include_directories(${D}/idmap/include)
include_directories(${D}/base/include)
include_directories(${D}/utils/include)
include_directories(${D}/cutils/include)
include_directories(${D}/log/include)
include_directories(${D}/zip/include)
include_directories(${D}/system/include)
include_directories(${D}/buildversion/include)
include_directories(${D}/include ${D}/protobuf-3.20.3/third_party/googletest/googletest/include)

set(AAPT2_PROTOBUF_SOURCE_ROOT "${D}/protobuf-3.20.3")
include_directories(${AAPT2_LIBPNG_INCLUDE_DIR} ${AAPT2_ZLIB_INCLUDE_DIR} ${AAPT2_EXPAT_INCLUDE_DIR} ${AAPT2_PROTOBUF_SOURCE_ROOT}/src)
add_compile_definitions(STATIC_ANDROIDFW_FOR_TOOLS ZLIB_CONST)
if(APPLE)
    add_compile_definitions(_DARWIN_UNLIMITED_STREAMS)
elseif(WIN32)
    add_compile_definitions(_POSIX_THREAD_SAFE_FUNCTIONS)
    add_compile_options(-mno-ms-bitfields)
endif()
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_definitions(NDEBUG)
endif()
set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${AAPT2_PROTOBUF_SOURCE_ROOT}/cmake ${CMAKE_CURRENT_BINARY_DIR}/protobuf)
include_directories(${D}/androidfw/include_pathutils)

include_directories(${D}/utils/binder/include)

if(AAPT2_BUILD_NUMBER)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/buildversion_override.cpp"
         "#include <string>\n"
         "namespace android { namespace build {\n"
         "extern \"C\" char soong_build_number[128] = \"${AAPT2_BUILD_NUMBER}\";\n"
         "std::string GetBuildNumber() { return soong_build_number; }\n"
         "}}\n")
    set(AAPT2_BUILD_VERSION_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/buildversion_override.cpp")
else()
    set(AAPT2_BUILD_VERSION_SOURCE "${D}/buildversion/libbuildversion.cpp")
endif()

include_directories(${D}/fmt/include ${D}/incfs/util/include ${D}/incfs/kernel-headers ${D}/zip/incfs_support/include)
# Android 14 fmt needs runtime format checking with current Apple Clang.
add_compile_definitions(FMT_HEADER_ONLY FMT_CONSTEVAL= ENABLE_STRING8_OBSOLETE_METHODS ENABLE_STRING16_OBSOLETE_METHODS)
add_library(android_deps STATIC
    ${D}/incfs/util/map_ptr.cpp
    ${D}/cutils/trace-host.cpp

    ${D}/utils/FileMap.cpp
    ${D}/utils/JenkinsHash.cpp
    ${D}/utils/LightRefBase.cpp
    ${D}/utils/NativeHandle.cpp
    ${D}/utils/Printer.cpp
    ${D}/utils/StopWatch.cpp
    ${D}/utils/SystemClock.cpp
    ${D}/utils/Threads.cpp
    ${D}/utils/Timers.cpp
    ${D}/utils/Tokenizer.cpp
    ${D}/utils/misc.cpp
    ${D}/utils/binder/VectorImpl.cpp
    ${D}/utils/binder/String16.cpp
    ${D}/utils/binder/StrongPointer.cpp
    ${D}/utils/binder/RefBase.cpp
    ${D}/utils/binder/String8.cpp
    ${D}/utils/binder/SharedBuffer.cpp
    ${D}/utils/binder/Errors.cpp
    ${D}/utils/binder/Unicode.cpp
    ${D}/androidfw/ApkAssets.cpp
    ${D}/androidfw/ApkParsing.cpp
    ${D}/androidfw/Asset.cpp
    ${D}/androidfw/AssetDir.cpp
    ${D}/androidfw/AssetManager.cpp
    ${D}/androidfw/AssetManager2.cpp
    ${D}/androidfw/AssetsProvider.cpp
    ${D}/androidfw/AttributeResolution.cpp
    ${D}/androidfw/BigBuffer.cpp
    ${D}/androidfw/BigBufferStream.cpp
    ${D}/androidfw/ChunkIterator.cpp
    ${D}/androidfw/ConfigDescription.cpp
    ${D}/androidfw/FileStream.cpp
    ${D}/androidfw/Idmap.cpp
    ${D}/androidfw/LoadedArsc.cpp
    ${D}/androidfw/Locale.cpp
    ${D}/androidfw/LocaleData.cpp
    ${D}/androidfw/misc.cpp
    ${D}/androidfw/NinePatch.cpp
    ${D}/androidfw/ObbFile.cpp
    ${D}/androidfw/PosixUtils.cpp
    ${D}/androidfw/Png.cpp
    ${D}/androidfw/PngChunkFilter.cpp
    ${D}/androidfw/PngCrunch.cpp
    ${D}/androidfw/ResourceTimer.cpp
    ${D}/androidfw/ResourceTypes.cpp
    ${D}/androidfw/ResourceUtils.cpp
    ${D}/androidfw/StreamingZipInflater.cpp
    ${D}/androidfw/StringPool.cpp
    ${D}/androidfw/TypeWrappers.cpp
    ${D}/androidfw/Util.cpp
    ${D}/androidfw/ZipFileRO.cpp
    ${D}/androidfw/ZipUtils.cpp
    ${D}/base/abi_compatibility.cpp
    ${D}/base/chrono_utils.cpp
    ${AAPT2_BASE_CMSG_SOURCE}
    ${D}/base/file.cpp
    ${D}/base/hex.cpp
    ${D}/base/logging.cpp
    ${D}/base/mapped_file.cpp
    ${D}/base/parsebool.cpp
    ${D}/base/parsenetaddress.cpp
    ${D}/base/posix_strerror_r.cpp
    ${D}/base/process.cpp
    ${D}/base/properties.cpp
    ${D}/base/stringprintf.cpp
    ${D}/base/strings.cpp
    ${D}/base/threads.cpp
    ${D}/base/test_utils.cpp
    ${D}/zip/zip_archive.cc
    ${D}/zip/zip_archive_stream_entry.cc
    ${D}/zip/zip_cd_entry_map.cc
    ${D}/zip/zip_error.cpp
    ${D}/zip/zip_writer.cc
    ${AAPT2_BASE_PLATFORM_SOURCES}
    ${AAPT2_BUILD_VERSION_SOURCE}
    ${D}/zip/incfs_support/signal_handling.cpp
    ${D}/log/log_event_list.cpp
    ${D}/log/log_event_write.cpp
    ${D}/log/logger_name.cpp
    ${D}/log/logger_read.cpp
    ${D}/log/logger_write.cpp
    ${D}/log/logprint.cpp
    ${D}/log/properties.cpp
    ${D}/cutils/ashmem-host.cpp
    ${D}/cutils/native_handle.cpp
)
# Match AOSP host dependencies; inclink itself retains Debug assertions.
target_compile_definitions(android_deps PRIVATE NDEBUG)

file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/proto)
foreach(name Configuration Resources ResourcesInternal)
    file(READ ${CMAKE_CURRENT_LIST_DIR}/source/${name}.proto content)
    string(REPLACE "frameworks/base/tools/aapt2/" "" content "${content}")
    file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/proto/${name}.proto "${content}")
endforeach()
set(PROTOS)
if(AAPT2_PROTOC_EXECUTABLE)
    set(AAPT2_PROTOC_COMMAND "${AAPT2_PROTOC_EXECUTABLE}")
    set(AAPT2_PROTOC_DEPENDENCY "${AAPT2_PROTOC_EXECUTABLE}")
else()
    set(AAPT2_PROTOC_COMMAND "$<TARGET_FILE:protoc>")
    set(AAPT2_PROTOC_DEPENDENCY protoc)
endif()
foreach(name Configuration Resources ResourcesInternal)
    add_custom_command(OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/${name}.pb.cc ${CMAKE_CURRENT_BINARY_DIR}/${name}.pb.h
        COMMAND ${AAPT2_PROTOC_COMMAND} --proto_path=${CMAKE_CURRENT_BINARY_DIR}/proto --cpp_out=${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_BINARY_DIR}/proto/${name}.proto
        DEPENDS ${AAPT2_PROTOC_DEPENDENCY} ${CMAKE_CURRENT_LIST_DIR}/source/${name}.proto)
    list(APPEND PROTOS ${CMAKE_CURRENT_BINARY_DIR}/${name}.pb.cc)
endforeach()
add_library(aapt2_core STATIC ${PROTOS}
    source/compile/IdAssigner.cpp
    source/compile/InlineXmlFormatParser.cpp
    source/compile/NinePatch.cpp
    source/compile/Png.cpp
    source/compile/PngChunkFilter.cpp
    source/compile/PngCrunch.cpp
    source/compile/PseudolocaleGenerator.cpp
    source/compile/Pseudolocalizer.cpp
    source/compile/XmlIdCollector.cpp
    source/configuration/ConfigurationParser.cpp
    source/dump/DumpManifest.cpp
    source/filter/AbiFilter.cpp
    source/filter/ConfigFilter.cpp
    source/format/Archive.cpp
    source/format/Container.cpp
    source/format/binary/BinaryResourceParser.cpp
    source/format/binary/ResChunkPullParser.cpp
    source/format/binary/TableFlattener.cpp
    source/format/binary/XmlFlattener.cpp
    source/format/proto/ProtoDeserialize.cpp
    source/format/proto/ProtoSerialize.cpp
    source/io/BigBufferStream.cpp
    source/io/File.cpp
    source/io/FileStream.cpp
    source/io/FileSystem.cpp
    source/io/StringStream.cpp
    source/io/Util.cpp
    source/io/ZipArchive.cpp
    source/link/AutoVersioner.cpp
    source/link/ManifestFixer.cpp
    source/link/NoDefaultResourceRemover.cpp
    source/link/ProductFilter.cpp
    source/link/PrivateAttributeMover.cpp
    source/link/ReferenceLinker.cpp
    source/link/ResourceExcluder.cpp
    source/link/TableMerger.cpp
    source/link/XmlCompatVersioner.cpp
    source/link/XmlNamespaceRemover.cpp
    source/link/XmlReferenceLinker.cpp
    source/optimize/MultiApkGenerator.cpp
    source/optimize/ResourceDeduper.cpp
    source/optimize/ResourceFilter.cpp
    source/optimize/ResourcePathShortener.cpp
    source/optimize/VersionCollapser.cpp
    source/process/SymbolTable.cpp
    source/split/TableSplitter.cpp
    source/text/Printer.cpp
    source/text/Unicode.cpp
    source/text/Utf8Iterator.cpp
    source/util/BigBuffer.cpp
    source/util/Files.cpp
    source/util/Util.cpp
    source/Debug.cpp
    source/DominatorTree.cpp
    source/java/AnnotationProcessor.cpp
    source/java/ClassDefinition.cpp
    source/java/JavaClassGenerator.cpp
    source/java/ManifestClassGenerator.cpp
    source/java/ProguardRules.cpp
    source/LoadedApk.cpp
    source/Resource.cpp
    source/ResourceParser.cpp
    source/ResourceTable.cpp
    source/ResourceUtils.cpp
    source/ResourceValues.cpp
    source/SdkConstants.cpp
    source/StringPool.cpp
    source/trace/TraceBuffer.cpp
    source/xml/XmlActionExecutor.cpp
    source/xml/XmlDom.cpp
    source/xml/XmlPullParser.cpp
    source/xml/XmlUtil.cpp
    source/cmd/Command.cpp
    source/cmd/Compile.cpp
    source/cmd/Convert.cpp
    source/cmd/Diff.cpp
    source/cmd/Dump.cpp
    source/cmd/Optimize.cpp
    source/cmd/Util.cpp
    source/cmd/IncLink.cpp
)
target_compile_options(aapt2_core PRIVATE -fno-rtti -fno-exceptions)
set(AAPT2_PROTOBUF_LINK_LIBRARY protobuf::libprotobuf)
target_link_libraries(aapt2_core PUBLIC android_deps ${AAPT2_PROTOBUF_LINK_LIBRARY} ${AAPT2_LIBPNG_LIBRARY} ${AAPT2_Z_LIBRARY} ${AAPT2_EXPAT_LIBRARY})

add_executable(aapt2-inclink source/Main.cpp)
target_compile_options(aapt2-inclink PRIVATE -fno-rtti -fno-exceptions)
target_link_libraries(aapt2-inclink PRIVATE aapt2_core)

option(AAPT2_BUILD_TESTS "Build native resource-link regression tests" OFF)
if(AAPT2_BUILD_TESTS)
    enable_testing()
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    # Reuse GoogleTest from the already pinned protobuf source archive.
    add_subdirectory(${AAPT2_PROTOBUF_SOURCE_ROOT}/third_party/googletest
        ${CMAKE_CURRENT_BINARY_DIR}/googletest EXCLUDE_FROM_ALL)
    set_target_properties(gtest gtest_main gmock gmock_main PROPERTIES CXX_STANDARD 17)
    add_executable(aapt2_link_tests
        source/test/Builders.cpp
        source/test/Common.cpp
        source/link/ReferenceLinker_test.cpp
        source/link/TableMerger_test.cpp)
    target_compile_definitions(aapt2_link_tests PRIVATE _LIBCPP_ENABLE_CXX20_REMOVED_TYPE_TRAITS)
    target_compile_options(aapt2_link_tests PRIVATE -fno-rtti -fno-exceptions)
    target_link_libraries(aapt2_link_tests PRIVATE aapt2_core gmock_main)
    add_test(NAME aapt2_link_tests COMMAND aapt2_link_tests)
endif()

if(WIN32)
    target_link_options(aapt2-inclink PRIVATE -static)
endif()
