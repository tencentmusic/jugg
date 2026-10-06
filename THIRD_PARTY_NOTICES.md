# Third-Party Software Notices

Jugg is distributed under the repository license. Third-party software remains under its own license; the Jugg license does not replace or narrow those terms.

The machine-readable inventory is `third_party/components.csv`. Corresponding license texts are in `third_party/licenses/`. The plugin distribution records the exact public source revision and source checksums in `third_party/SOURCE.md` and `third_party/SOURCE_SHA256SUMS`.

## 1. OpenJDK JVMTI header N/A

- License: GPL-2.0-only WITH Classpath-exception-2.0
- Copyright: Copyright © 2003, 2011 Oracle and/or its affiliates
- License/source reference: https://openjdk.org/legal/gplv2+ce.html
- Download/source: https://github.com/openjdk/jdk8u/blob/jdk8u202-b08/jdk/src/share/javavm/export/jvmti.h
- Modified by Jugg: Yes
- Notes: Used as a build header for the JVMTI agent; only JNINativeInterface_ was changed to JNINativeInterface; subject to GPL-2.0-only WITH Classpath-exception-2.0; the distributed SOURCE.md identifies a public commit containing the upstream baseline, modified source, and patch.

## 2. rsync 3.4.1

- License: GPL-3.0-or-later
- Copyright: Copyright © 1996-2025 Andrew Tridgell, Wayne Davison, and others
- License/source reference: https://github.com/RsyncProject/rsync/blob/v3.4.1/COPYING
- Download/source: https://download.samba.org/pub/rsync/src/rsync-3.4.1.tar.gz
- Modified by Jugg: No
- Notes: Distributed with the plugin as a universal macOS executable and invoked as a separate process, without static or dynamic linking to plugin code; the distributed SOURCE.md identifies a public commit containing the corresponding source, and the GPL-3.0-or-later text is included in the distribution.

## 3. sshpass 1.10

- License: GPL-2.0-or-later
- Copyright: Copyright © 2006-2011 Lingnu Open Source Consulting Ltd.; 2015-2016, 2021-2022 Shachar Shemesh
- License/source reference: https://sourceforge.net/p/sshpass/code-git/ci/v1.10/tree/COPYING
- Download/source: https://sourceforge.net/projects/sshpass/files/sshpass/1.10/sshpass-1.10.tar.gz/download
- Modified by Jugg: No
- Notes: Distributed with the plugin as a macOS arm64 executable and invoked as a separate process, without static or dynamic linking to plugin code; the distributed SOURCE.md identifies a public commit containing the corresponding source, and the GPL-2.0-or-later text is included in the distribution.

## 4. Trove4J, JetBrains fork 1.0.20200330

- License: LGPL-2.1-or-later
- Copyright: Copyright © 2002 Eric D. Friedman and Trove contributors
- License/source reference: https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.pom
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR loaded by the JVM; the distributed SOURCE.md identifies a public commit containing the corresponding source JAR, and the LGPL-2.1-or-later license text is included in the distribution.

## 5. juniversalchardet 1.0.3

- License: MPL-1.1
- Copyright: Copyright © 1998 Netscape Communications Corporation; Kohei TAKETA and Java port contributors
- License/source reference: https://repo1.maven.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom
- Download/source: https://repo1.maven.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR; this distribution selects MPL-1.1 according to the upstream POM; the distributed SOURCE.md identifies a public commit containing the corresponding source JAR, and the license text is included in the distribution.

## 6. JavaBeans Activation Framework 1.2.0

- License: CDDL-1.1
- Copyright: Copyright © 1997-2017 Oracle and/or its affiliates
- License/source reference: https://github.com/javaee/activation/blob/JAF-1_2_0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 as an Android Tools Repository dependency; this distribution selects CDDL-1.1; the distributed SOURCE.md identifies a public commit containing the corresponding source JAR, and the license text is included in the distribution.

## 7. Xerial SQLite JDBC 3.42.0.0

- License: Apache-2.0 and BSD-2-Clause; SQLite core is public domain
- Copyright: Copyright © Taro L. Saito and Xerial contributors; Copyright © 2006 David Crawshaw; SQLite authors
- License/source reference: https://github.com/xerial/sqlite-jdbc/blob/3.42.0.0/LICENSE
- Download/source: https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar
- Modified by Jugg: Yes
- Notes: Distributed with the plugin as a trimmed JAR; only native libraries for unsupported platforms were removed, while those needed for macOS, Linux, and Windows were retained.

## 8. AAPT2 inclink, Jugg custom build N/A

- License: Apache-2.0; includes statically linked third-party components
- Copyright: Copyright © 2015 The Android Open Source Project; Copyright © 2024-2026 Jugg contributors
- License/source reference: https://android.googlesource.com/platform/frameworks/base/+/a707013b78cea3586fdadf9a2f04932e823d7504/NOTICE
- Download/source: https://android.googlesource.com/platform/frameworks/base/+/a707013b78cea3586fdadf9a2f04932e823d7504/tools/aapt2/
- Modified by Jugg: Yes
- Notes: Distributed with the plugin as executables for three platforms and invoked as separate processes; based on AOSP AAPT2 with customized incremental linking and Android 14/Linux compatibility; statically linked components are listed separately; no unique version can be identified.

## 9. Apache XML Commons XML APIs 1.4.01

- License: Apache-2.0, SAX License, and W3C Software Notice and License
- Copyright: Copyright © 1999-2009 The Apache Software Foundation; Copyright © 2000 W3C and others
- License/source reference: https://repo1.maven.org/maven2/xml-apis/xml-apis/1.4.01/xml-apis-1.4.01.pom
- Download/source: https://repo1.maven.org/maven2/xml-apis/xml-apis/1.4.01/xml-apis-1.4.01.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 as a transitive dependency of Xerces2 Java; the component includes content under Apache-2.0, SAX, and W3C licenses.

## 10. Apache Commons Compress 1.21

- License: Apache-2.0; some sevenz code comes from the public-domain LZMA SDK
- Copyright: Copyright © 2002-2021 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/commons-compress-1.21.pom
- Download/source: https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.21/commons-compress-1.21.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR; some sevenz code originates from the public-domain LZMA SDK.

## 11. R8 8.4.21

- License: BSD-3-Clause; includes additional third-party licenses
- Copyright: Copyright © 2016 The R8 project authors
- License/source reference: https://r8.googlesource.com/r8/+/refs/tags/8.4.21/LICENSE
- Download/source: https://storage.googleapis.com/r8-releases/raw/8.4.21/r8.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR; third-party components embedded in the JAR are listed separately in this inventory.

## 12. kXML2 2.3.0

- License: BSD-style AND Public Domain
- Copyright: Copyright © 2002-2004 Stefan Haustein
- License/source reference: https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.pom
- Download/source: https://repo1.maven.org/maven2/net/sf/kxml/kxml2/2.3.0/kxml2-2.3.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 as an Android Tools dependency; org.kxml2 code is subject to BSD-style terms, while the org.xmlpull.v1 API is public domain.

## 13. Android JVMTI Apply Changes / Slicer source N/A

- License: Apache-2.0
- Copyright: Copyright © 2017-2018 The Android Open Source Project
- License/source reference: https://android.googlesource.com/platform/tools/base/+/refs/heads/studio-main/deploy/agent/native/instrumenter.cc
- Download/source: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main/deploy/agent/native.tar.gz
- Modified by Jugg: Yes
- Notes: The project includes and modifies AOSP JVMTI Apply Changes/Slicer source, compiled into the distributed JVMTI agent; Jugg adds Android 8-15 Application, ResourcesManager, ActivityThread, and ClassLoader hooks, plus best-effort fallback for optional transforms and diagnostic logging; the exact upstream commit cannot be identified, so no version is specified; modified source remains in the repository.

## 14. Android Studio / Android Plugin APIs N/A

- License: Apache-2.0
- Copyright: Copyright © 2000-2022 JetBrains s.r.o. and contributors; Copyright © 2005-2022 Google LLC and The Android Open Source Project
- License/source reference: https://android.googlesource.com/platform/tools/base/+/refs/heads/studio-main/deploy/agent/native/instrumenter.cc / https://github.com/JetBrains/intellij-community/blob/idea/223.7571.182/LICENSE.txt
- Download/source: https://android.googlesource.com/platform/tools/base/+archive/refs/heads/studio-main.tar.gz / https://github.com/JetBrains/intellij-community/archive/refs/tags/idea/223.7571.182.zip
- Modified by Jugg: Yes
- Notes: Generated and trimmed compile stubs from Android Studio/Android Plugin APIs, used only to build compatibility modules; upstream implementation code is not bundled in the plugin distribution; only required API declarations remain, and the version cannot be fully reconstructed.

## 15. AOSP framework class stubs N/A

- License: Apache-2.0
- Copyright: Copyright © 2006-2017 The Android Open Source Project
- License/source reference: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/NOTICE
- Download/source: https://android.googlesource.com/platform/frameworks/base/+archive/refs/heads/master/core/java/android.tar.gz
- Modified by Jugg: Yes
- Notes: Seven minimal compile declarations written from AOSP framework APIs, used only to build the JVMTI agent; AOSP implementation code is not bundled in the plugin distribution; API declarations were selected and simplified, and no version is specified.

## 16. Gradle Wrapper launch files 7.0.2

- License: Apache-2.0
- Copyright: Copyright © 2015 the original author or authors
- License/source reference: https://github.com/gradle/gradle/blob/v7.0.2/LICENSE
- Download/source: https://github.com/gradle/gradle/archive/refs/tags/v7.0.2.zip
- Modified by Jugg: Yes
- Notes: Distributed with the plugin and copied when a target project has gradle-wrapper.properties but lacks the launch files; gradlew and gradlew.bat only remove the default -Dfile.encoding=UTF-8 JVM argument; Gradle Distribution is not included.

## 17. Kotlin Android Extensions 1.9.23

- License: Apache-2.0
- Copyright: Copyright © 2010-2023 JetBrains s.r.o. and respective authors and developers
- License/source reference: https://github.com/JetBrains/kotlin/blob/v1.9.23/license/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-android-extensions/1.9.23/kotlin-android-extensions-1.9.23.jar
- Modified by Jugg: Yes
- Notes: Distributed with the plugin as a modified JAR; compared with the official version, the reportRemovedError call in AndroidComponentRegistrar was removed, and three other classes only gained an empty parameter-annotation attribute.

## 18. Aalto XML 1.3.0

- License: Apache-2.0
- Copyright: Copyright © 2006-present Tatu Saloranta and contributors
- License/source reference: https://repo1.maven.org/maven2/com/fasterxml/aalto-xml/1.3.0/aalto-xml-1.3.0.pom
- Download/source: https://repo1.maven.org/maven2/com/fasterxml/aalto-xml/1.3.0/aalto-xml-1.3.0.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 19. AAPT2 Proto 8.2.0-rc01-10154469

- License: Apache-2.0
- Copyright: Copyright © 2005-2008 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2-proto/8.2.0-rc01-10154469/aapt2-proto-8.2.0-rc01-10154469.pom
- Download/source: https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2-proto/8.2.0-rc01-10154469/aapt2-proto-8.2.0-rc01-10154469.jar
- Modified by Jugg: No
- Notes: AAPT2 Proto is embedded in R8 8.4.21 and distributed with the R8 JAR; dependency resolution actually uses 8.2.0-rc01-10154469.

## 20. Android Data Binding compiler suite 7.4.2

- License: Apache-2.0
- Copyright: Copyright © 2005-2017 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/7.4.2/databinding-compiler-7.4.2.pom
- Download/source: https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/7.4.2/databinding-compiler-7.4.2.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin and cmd_line as separate compiler, compiler-common, common, and baseLibrary JARs. Jugg uses 7.4.2 instead of 8.7.3 because the latter contains class file version 61.0, which cannot load in Java 11 IDEs.

## 21. Android Data Binding compiler suite 8.7.3

- License: Apache-2.0
- Copyright: Copyright © 2005-2017 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/8.7.3/databinding-compiler-8.7.3.pom
- Download/source: https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-compiler/8.7.3/databinding-compiler-8.7.3.jar
- Modified by Jugg: No
- Notes: Not distributed in the current plugin or cmd_line release; replaced by 7.4.2 (see component 20). Version 8.7.3 contains Java 17 bytecode, so XMLParser$ElementContext cannot load in Java 11 IDEs such as Electric Eel.

## 22. Android Tools Annotations 30.4.2

- License: Apache-2.0
- Copyright: Copyright © 2005-2013 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/com/android/tools/annotations/30.4.2/annotations-30.4.2.pom
- Download/source: https://dl.google.com/dl/android/maven2/com/android/tools/annotations/30.4.2/annotations-30.4.2.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Data Binding 7.4.2; coexists with Android Tools Annotations 31.7.3 in the plugin distribution.

## 23. Android Tools Annotations 31.7.3

- License: Apache-2.0
- Copyright: Copyright © 2005-2013 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/com/android/tools/annotations/31.7.3/annotations-31.7.3.pom
- Download/source: https://dl.google.com/dl/android/maven2/com/android/tools/annotations/31.7.3/annotations-31.7.3.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of the Android toolchain.

## 24. Android Tools libraries 31.2.0-rc01

- License: Apache-2.0
- Copyright: Copyright © 2005-2013 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/com/android/tools/sdk-common/31.2.0-rc01/sdk-common-31.2.0-rc01.pom
- Download/source: https://dl.google.com/dl/android/maven2/com/android/tools/sdk-common/31.2.0-rc01/sdk-common-31.2.0-rc01.jar
- Modified by Jugg: No
- Notes: Several Android Tools components are embedded in R8 8.4.21 and grouped here because they share the same version, license, and modification status.

## 25. AOSP native support libraries suite N/A

- License: Apache-2.0
- Copyright: Copyright © 2005-2024 The Android Open Source Project
- License/source reference: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/NOTICE / https://android.googlesource.com/platform/system/core/+/refs/tags/android-14.0.0_r1/libutils/NOTICE / https://android.googlesource.com/platform/system/libbase/+/refs/tags/android-14.0.0_r1/NOTICE
- Download/source: https://android.googlesource.com/platform/frameworks/base/+archive/refs/tags/android-14.0.0_r1.tar.gz / https://android.googlesource.com/platform/system/core/+archive/refs/tags/android-14.0.0_r1.tar.gz / https://android.googlesource.com/platform/system/libbase/+archive/refs/tags/android-14.0.0_r1.tar.gz
- Modified by Jugg: No
- Notes: libandroidfw, libutils, liblog, libcutils, libziparchive, libbase, libbuildversion, and libidmap2_policies are statically linked into the AAPT2 executable; the JVMTI agent separately links dynamically to liblog supplied by the Android device.

## 26. Apache Commons Codec 1.10

- License: Apache-2.0
- Copyright: Copyright © 2002-2014 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.pom
- Download/source: https://repo1.maven.org/maven2/commons-codec/commons-codec/1.10/commons-codec-1.10.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 27. Apache Commons Codec 1.16.0

- License: Apache-2.0
- Copyright: Copyright © 2002-2023 The Apache Software Foundation
- License/source reference: https://github.com/apache/commons-codec/blob/rel/commons-codec-1.16.0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.0/commons-codec-1.16.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of JGit.

## 28. Apache Commons IO 2.13.0

- License: Apache-2.0
- Copyright: Copyright © 2002-2023 The Apache Software Foundation
- License/source reference: https://github.com/apache/commons-io/blob/rel/commons-io-2.13.0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/commons-io/commons-io/2.13.0/commons-io-2.13.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 29. Apache Commons IO 2.4

- License: Apache-2.0
- Copyright: Copyright © 2002-2012 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/commons-io/commons-io/2.4/commons-io-2.4.pom
- Download/source: https://repo1.maven.org/maven2/commons-io/commons-io/2.4/commons-io-2.4.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Data Binding 7.4.2; coexists with Commons IO 2.13.0 in the plugin distribution.

## 30. Apache Commons Logging 1.2

- License: Apache-2.0
- Copyright: Copyright © 2003-2014 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.pom
- Download/source: https://repo1.maven.org/maven2/commons-logging/commons-logging/1.2/commons-logging-1.2.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 31. Apache HttpClient / HttpMime 4.5.6

- License: Apache-2.0
- Copyright: Copyright © 1999-2018 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.6/httpclient-4.5.6.pom
- Download/source: https://repo1.maven.org/maven2/org/apache/httpcomponents/httpclient/4.5.6/httpclient-4.5.6.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 32. Apache HttpCore 4.4.16

- License: Apache-2.0
- Copyright: Copyright © 2005-2022 The Apache Software Foundation
- License/source reference: https://repo1.maven.org/maven2/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.pom
- Download/source: https://repo1.maven.org/maven2/org/apache/httpcomponents/httpcore/4.4.16/httpcore-4.4.16.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 33. Apache Log4j, JetBrains dependency build 1.2.17.2

- License: Apache-2.0
- Copyright: Copyright © 2007 The Apache Software Foundation
- License/source reference: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/log4j/1.2.17.2/log4j-1.2.17.2.pom
- Download/source: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/log4j/1.2.17.2/log4j-1.2.17.2.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 34. ArscBlamer 1.2.0

- License: Apache-2.0
- Copyright: Copyright © 2016 Google Inc.
- License/source reference: https://repo1.maven.org/maven2/io/github/shiqos/arscblamer/1.2.0/arscblamer-1.2.0.pom
- Download/source: https://repo1.maven.org/maven2/io/github/shiqos/arscblamer/1.2.0/arscblamer-1.2.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of Android Data Binding; Apache-2.0 was determined from the versioned POM and source file headers.

## 35. Auto Common 0.10

- License: Apache-2.0
- Copyright: Copyright © 2013-2017 Google, Inc.; Copyright © 2013 Square, Inc.
- License/source reference: https://repo1.maven.org/maven2/com/google/auto/auto-common/0.10/auto-common-0.10.pom
- Download/source: https://repo1.maven.org/maven2/com/google/auto/auto-common/0.10/auto-common-0.10.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of Android Data Binding.

## 36. dex2jar dex-reader/dex-writer 2.1

- License: Apache-2.0
- Copyright: Copyright © 2009-2014 Panxiaobo and contributors
- License/source reference: https://github.com/pxb1988/dex2jar/blob/v2.1/LICENSE.txt
- Download/source: https://github.com/pxb1988/dex2jar/archive/refs/tags/v2.1.zip
- Modified by Jugg: No
- Notes: Distributed with the plugin as separate dex-reader, dex-reader-api, and dex-writer JARs.

## 37. Error Prone Annotations 2.18.0

- License: Apache-2.0
- Copyright: Copyright © 2014-2021 The Error Prone Authors
- License/source reference: https://github.com/google/error-prone/blob/v2.18.0/COPYING
- Download/source: https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.18.0/error_prone_annotations-2.18.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 38. Error Prone Annotations 2.3.4

- License: Apache-2.0
- Copyright: Copyright © 2014-2019 The Error Prone Authors
- License/source reference: https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.3.4/error_prone_annotations-2.3.4.pom
- Download/source: https://repo1.maven.org/maven2/com/google/errorprone/error_prone_annotations/2.3.4/error_prone_annotations-2.3.4.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Guava 30.1-jre; coexists with Error Prone Annotations 2.18.0 embedded in R8.

## 39. Fast Infoset 1.2.16

- License: Apache-2.0
- Copyright: Copyright © 2012-2018 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/com/sun/xml/fastinfoset/FastInfoset/1.2.16/FastInfoset-1.2.16.pom
- Download/source: https://repo1.maven.org/maven2/com/sun/xml/fastinfoset/FastInfoset/1.2.16/FastInfoset-1.2.16.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of JAXB Runtime; this distribution selects Apache-2.0 from the upstream dual-license options.

## 40. fastutil 7.2.1

- License: Apache-2.0
- Copyright: Copyright © 2002-2017 Sebastiano Vigna, Paolo Boldi and contributors
- License/source reference: https://github.com/vigna/fastutil/blob/7.2.1/LICENSE-2.0
- Download/source: https://repo1.maven.org/maven2/it/unimi/dsi/fastutil/7.2.1/fastutil-7.2.1.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 41. Gson 2.10.1

- License: Apache-2.0
- Copyright: Copyright © 2008-2021 Google Inc., The Android Open Source Project and Gson authors
- License/source reference: https://github.com/google/gson/blob/gson-parent-2.10.1/LICENSE
- Download/source: https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 42. Guava 29.0-jre

- License: Apache-2.0
- Copyright: Copyright © 2005-2020 The Guava Authors
- License/source reference: https://repo1.maven.org/maven2/com/google/guava/guava/29.0-jre/guava-29.0-jre.pom
- Download/source: https://repo1.maven.org/maven2/com/google/guava/guava/29.0-jre/guava-29.0-jre.jar
- Modified by Jugg: No
- Notes: Guava 29.0-jre is embedded and relocated in Kotlin compiler 1.9.23; it coexists with Guava 32.1.2-jre embedded in R8.

## 43. Guava 30.1-jre

- License: Apache-2.0
- Copyright: Copyright © 2005-2021 The Guava Authors
- License/source reference: https://repo1.maven.org/maven2/com/google/guava/guava/30.1-jre/guava-30.1-jre.pom
- Download/source: https://repo1.maven.org/maven2/com/google/guava/guava/30.1-jre/guava-30.1-jre.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Data Binding 7.4.2; coexists with other Guava versions embedded in R8 and Kotlin compiler.

## 44. Guava 32.1.2-jre

- License: Apache-2.0
- Copyright: Copyright © 2005-2021 The Guava Authors
- License/source reference: https://github.com/google/guava/blob/v32.1.2/guava/pom.xml
- Download/source: https://repo1.maven.org/maven2/com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 45. Guava FailureAccess 1.0.1

- License: Apache-2.0
- Copyright: Copyright © 2018 The Guava Authors
- License/source reference: https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.pom
- Download/source: https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 46. IntelliJ fastutil fork 8.5.4-9

- License: Apache-2.0
- Copyright: Copyright © 2002-2021 Sebastiano Vigna, Paolo Boldi and contributors
- License/source reference: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/fastutil/intellij-deps-fastutil/8.5.4-9/intellij-deps-fastutil-8.5.4-9.pom
- Download/source: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/fastutil/intellij-deps-fastutil/8.5.4-9/intellij-deps-fastutil-8.5.4-9.jar
- Modified by Jugg: No
- Notes: The IntelliJ fastutil fork is embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 47. IntelliJ IDEA Annotations 13.0

- License: Apache-2.0
- Copyright: Copyright © 2000-2012 JetBrains s.r.o.
- License/source reference: https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of Kotlin compiler.

## 48. IntelliJ Platform Core / JPS Model 213.7172.53

- License: Apache-2.0
- Copyright: Copyright © 2000-2021 JetBrains s.r.o. and contributors
- License/source reference: https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/jps-model/213.7172.53/jps-model-213.7172.53.pom
- Download/source: https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/platform/jps-model/213.7172.53/jps-model-213.7172.53.jar
- Modified by Jugg: No
- Notes: IntelliJ Platform Core and JPS Model are embedded in Kotlin compiler 1.9.23 and grouped here because they share the same license and modification status.

## 49. J2ObjC Annotations 1.3

- License: Apache-2.0
- Copyright: Copyright © 2012 Google Inc.
- License/source reference: https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/1.3/j2objc-annotations-1.3.pom
- Download/source: https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/1.3/j2objc-annotations-1.3.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Guava 30.1-jre; coexists with J2ObjC Annotations 2.8 embedded in R8.

## 50. J2ObjC Annotations 2.8

- License: Apache-2.0
- Copyright: Copyright © 2012 Google Inc.
- License/source reference: https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/2.8/j2objc-annotations-2.8.pom
- Download/source: https://repo1.maven.org/maven2/com/google/j2objc/j2objc-annotations/2.8/j2objc-annotations-2.8.jar
- Modified by Jugg: No
- Notes: An optional compile-time dependency of Guava, but explicitly listed as distributed with R8 in the R8 license inventory.

## 51. Jansi 1.16

- License: Apache-2.0
- Copyright: Copyright © 2009-2017 the original authors
- License/source reference: https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.16/jansi-1.16.pom
- Download/source: https://repo1.maven.org/maven2/org/fusesource/jansi/jansi/1.16/jansi-1.16.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 52. Java Native Access / JNA Platform 5.6.0

- License: Apache-2.0
- Copyright: Copyright © 2007-2020 Timothy Wall, Olivier Chafik and contributors
- License/source reference: https://github.com/java-native-access/jna/blob/5.6.0/LICENSE
- Download/source: https://repo1.maven.org/maven2/net/java/dev/jna/jna-platform/5.6.0/jna-platform-5.6.0.jar
- Modified by Jugg: No
- Notes: JNA and JNA Platform are embedded in R8 8.4.21; this distribution selects Apache-2.0 from the upstream dual-license options.

## 53. Java Native Access, JetBrains dependency build 5.9.0.26

- License: Apache-2.0
- Copyright: Copyright © 2007-2021 Timothy Wall, Olivier Chafik and contributors
- License/source reference: https://github.com/java-native-access/jna/blob/5.9.0/LICENSE
- Download/source: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/jna/jna/5.9.0.26/jna-5.9.0.26.jar
- Modified by Jugg: No
- Notes: JNA and JNA Platform are embedded and relocated in Kotlin compiler 1.9.23; this distribution selects Apache-2.0 from the upstream dual-license options.

## 54. JavaEWAH 1.2.3

- License: Apache-2.0
- Copyright: Copyright © 2009-2016 Daniel Lemire, Cliff Moon, David McIntosh and contributors
- License/source reference: https://repo1.maven.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.pom
- Download/source: https://repo1.maven.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of JGit.

## 55. JavaParser Core 3.17.0

- License: Apache-2.0
- Copyright: Copyright © 2007-2010 Júlio Vilmar Gesser; 2011-2020 The JavaParser Team
- License/source reference: https://github.com/javaparser/javaparser/blob/javaparser-parent-3.17.0/LICENSE
- Download/source: https://repo1.maven.org/maven2/com/github/javaparser/javaparser-core/3.17.0/javaparser-core-3.17.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency; this distribution selects Apache-2.0 from the upstream dual-license options.

## 56. JavaPoet 1.10.0

- License: Apache-2.0
- Copyright: Copyright © 2014-2016 Google, Inc. and Square, Inc.
- License/source reference: https://github.com/square/javapoet/blob/javapoet-1.10.0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/com/squareup/javapoet/1.10.0/javapoet-1.10.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of Android Data Binding.

## 57. Javaslang 2.0.6

- License: Apache-2.0
- Copyright: Copyright © 2014-2017 Javaslang contributors
- License/source reference: https://repo1.maven.org/maven2/io/javaslang/javaslang/2.0.6/javaslang-2.0.6.pom
- Download/source: https://repo1.maven.org/maven2/io/javaslang/javaslang/2.0.6/javaslang-2.0.6.jar
- Modified by Jugg: No
- Notes: Embedded in Kotlin compiler 1.9.23, retaining the javaslang.* package name, and distributed with the compiler JAR.

## 58. javax.inject / JSR-330 1

- License: Apache-2.0
- Copyright: Copyright © 2009 The JSR-330 Expert Group
- License/source reference: https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.pom
- Download/source: https://repo1.maven.org/maven2/javax/inject/javax.inject/1/javax.inject-1.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 59. Jetifier Core 1.0.0-beta10

- License: Apache-2.0
- Copyright: Copyright © 2018 The Android Open Source Project
- License/source reference: https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-core/1.0.0-beta10/jetifier-core-1.0.0-beta10.pom
- Download/source: https://dl.google.com/dl/android/maven2/com/android/tools/build/jetifier/jetifier-core/1.0.0-beta10/jetifier-core-1.0.0-beta10.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of Android Data Binding.

## 60. Jimfs 1.1

- License: Apache-2.0
- Copyright: Copyright © 2013-2016 Google Inc.
- License/source reference: https://github.com/google/jimfs/blob/v1.1/LICENSE
- Download/source: https://repo1.maven.org/maven2/com/google/jimfs/jimfs/1.1/jimfs-1.1.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 61. JSR-305 Annotations 3.0.2

- License: Apache-2.0
- Copyright: Copyright © 2005 Brian Goetz and contributors
- License/source reference: https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom
- Download/source: https://repo1.maven.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 62. Kotlin compiler/runtime suite 1.9.23

- License: Apache-2.0
- Copyright: Copyright © 2010-2023 JetBrains s.r.o. and respective authors and developers
- License/source reference: https://github.com/JetBrains/kotlin/blob/v1.9.23/license/LICENSE.txt
- Download/source: https://github.com/JetBrains/kotlin/archive/refs/tags/v1.9.23.zip
- Modified by Jugg: No
- Notes: Distributed with the plugin as multiple separate JARs including compiler, daemon, reflect, script runtime, and stdlib-jdk7/jdk8; components embedded in the compiler are listed separately.

## 63. Kotlin Metadata JVM 2.2.21

- License: Apache-2.0
- Copyright: Copyright © 2010-2024 JetBrains s.r.o. and respective authors and developers
- License/source reference: https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-metadata-jvm/2.2.21/kotlin-metadata-jvm-2.2.21.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 64. Kotlin Reflect 1.9.0

- License: Apache-2.0
- Copyright: Copyright © 2010-2023 JetBrains s.r.o. and Kotlin contributors
- License/source reference: https://github.com/JetBrains/kotlin/blob/v1.9.0/license/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-reflect/1.9.0/kotlin-reflect-1.9.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 65. Kotlin Standard Library 1.9.21

- License: Apache-2.0
- Copyright: Copyright © 2010-2023 JetBrains s.r.o. and Kotlin contributors
- License/source reference: https://github.com/JetBrains/kotlin/blob/v1.9.21/license/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.21/kotlin-stdlib-1.9.21.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 through kotlinx-metadata-jvm; coexists with the plugin's top-level Kotlin Standard Library 2.2.21.

## 66. Kotlin Standard Library 2.2.21

- License: Apache-2.0
- Copyright: Copyright © 2010-2024 JetBrains s.r.o. and respective authors and developers
- License/source reference: https://github.com/JetBrains/kotlin/blob/v2.2.21/license/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.21/kotlin-stdlib-2.2.21.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of Kotlin Metadata JVM.

## 67. kotlinx-coroutines-core-jvm 1.6.4

- License: Apache-2.0
- Copyright: Copyright © 2016-2022 JetBrains s.r.o.
- License/source reference: https://github.com/Kotlin/kotlinx.coroutines/blob/1.6.4/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 68. kotlinx-metadata-jvm 0.9.0

- License: Apache-2.0
- Copyright: Copyright © 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors
- License/source reference: https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-metadata-jvm/0.9.0/kotlinx-metadata-jvm-0.9.0.pom
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-metadata-jvm/0.9.0/kotlinx-metadata-jvm-0.9.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 69. kotlinx.collections.immutable JVM 0.3.1

- License: Apache-2.0
- Copyright: Copyright © 2016-2019 JetBrains s.r.o.
- License/source reference: https://github.com/Kotlin/kotlinx.collections.immutable/blob/v0.3.1/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-collections-immutable-jvm/0.3.1/kotlinx-collections-immutable-jvm-0.3.1.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 70. LZ4 Java 1.7.1

- License: Apache-2.0
- Copyright: Copyright © 2011-2019 Adrien Grand, Yann Collet and contributors
- License/source reference: https://github.com/lz4/lz4-java/blob/1.7.1/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/lz4/lz4-java/1.7.1/lz4-java-1.7.1.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 71. OkHttp 4.12.0

- License: Apache-2.0
- Copyright: Copyright © 2012-2019 Square, Inc., The Android Open Source Project and contributors
- License/source reference: https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency for networking.

## 72. Okio 3.6.0

- License: Apache-2.0
- Copyright: Copyright © 2014-2021 Square, Inc. and others
- License/source reference: https://github.com/square/okio/blob/parent-3.6.0/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR transitive dependency of OkHttp.

## 73. StreamEx 0.7.2

- License: Apache-2.0
- Copyright: Copyright © 2015, 2019 StreamEx contributors
- License/source reference: https://repo1.maven.org/maven2/one/util/streamex/0.7.2/streamex-0.7.2.pom
- Download/source: https://repo1.maven.org/maven2/one/util/streamex/0.7.2/streamex-0.7.2.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 74. Xerces2 Java 2.12.0

- License: Apache-2.0
- Copyright: Copyright © 1999-2018 The Apache Software Foundation; IBM, Sun and other contributors
- License/source reference: https://repo1.maven.org/maven2/xerces/xercesImpl/2.12.0/xercesImpl-2.12.0.pom
- Download/source: https://repo1.maven.org/maven2/xerces/xercesImpl/2.12.0/xercesImpl-2.12.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 75. LLVM libc++, Android toolchain build N/A

- License: Apache-2.0 WITH LLVM-exception
- Copyright: Copyright © 2009-2024 LLVM Project contributors
- License/source reference: https://android.googlesource.com/toolchain/llvm-project/+/477610d4d0d988e69dbc3fae4fe86bff3f07f2b5/LICENSE.TXT
- Download/source: https://android.googlesource.com/toolchain/llvm-project/+/477610d4d0d988e69dbc3fae4fe86bff3f07f2b5/libcxx/
- Modified by Jugg: No
- Notes: Statically linked as libc++_static into the distributed AAPT2 executable; corresponds to Android clang r510928 / LLVM commit 477610d4d0d988e69dbc3fae4fe86bff3f07f2b5; no independent release version can be mapped.

## 76. ASM 9.8

- License: BSD-3-Clause
- Copyright: Copyright © 2000-2011 INRIA, France Telecom
- License/source reference: https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_8/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.jar
- Modified by Jugg: Yes
- Notes: Distributed with the plugin as three repackaged JARs; org.objectweb.asm was relocated into a Jugg-private package, and module-info.class was additionally removed from asm-commons.

## 77. StAX2 API 4.2.1

- License: BSD License
- Copyright: Copyright © 2005-present Tatu Saloranta and contributors
- License/source reference: https://repo1.maven.org/maven2/org/codehaus/woodstox/stax2-api/4.2.1/stax2-api-4.2.1.pom
- Download/source: https://repo1.maven.org/maven2/org/codehaus/woodstox/stax2-api/4.2.1/stax2-api-4.2.1.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 78. ANTLR 4 Runtime, relocated in Data Binding 4.5.3

- License: BSD-3-Clause
- Copyright: Copyright © 2015 Terence Parr, Sam Harwell
- License/source reference: https://github.com/antlr/antlr4/blob/4.5.3/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/antlr/antlr4-runtime/4.5.3/antlr4-runtime-4.5.3.jar
- Modified by Jugg: No
- Notes: Relocated code is embedded in Data Binding compiler-common 7.4.2 and distributed with it; packaged by upstream Data Binding.

## 79. ASM 9.6

- License: BSD-3-Clause
- Copyright: Copyright © 2000-2011 INRIA, France Telecom
- License/source reference: https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_6/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar
- Modified by Jugg: No
- Notes: ASM 9.6 core, tree, analysis, commons, and util code is embedded in R8 8.4.21; it differs in version and modification status from separately repackaged ASM 9.8.

## 80. ASM all, JetBrains dependency build 9.0

- License: BSD-3-Clause
- Copyright: Copyright © 2000-2011 INRIA, France Telecom
- License/source reference: https://gitlab.ow2.org/asm/asm/-/blob/ASM_9_0/LICENSE.txt
- Download/source: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/asm-all/9.0/asm-all-9.0.jar
- Modified by Jugg: No
- Notes: ASM 9.0 is embedded and relocated in Kotlin compiler 1.9.23; it coexists with ASM 9.6 embedded in R8 and separately repackaged ASM 9.8.

## 81. JLine 3 3.3.1

- License: BSD-3-Clause
- Copyright: Copyright © 2002-2017 the original author or authors
- License/source reference: https://github.com/jline/jline3/blob/jline-3.3.1/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/jline/jline/3.3.1/jline-3.3.1.jar
- Modified by Jugg: No
- Notes: Embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 82. JSch, mwiede fork 0.2.16

- License: BSD-3-Clause
- Copyright: Copyright © 2002-2015 Atsuhiko Yamanaka, JCraft, Inc.
- License/source reference: https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency for SSH; JZlib and jBCrypt embedded in the JAR are listed separately.

## 83. JZlib, embedded in JSch N/A

- License: BSD-3-Clause
- Copyright: Copyright © 2000-2011 ymnk, JCraft, Inc.
- License/source reference: https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.JZlib.txt
- Download/source: https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar
- Modified by Jugg: No
- Notes: Code is embedded in JSch 0.2.16 and distributed with its JAR; upstream does not identify the embedded version, so no version is specified.

## 84. PicoContainer, IntelliJ slimmed-down fork N/A

- License: BSD-3-Clause
- Copyright: Copyright © PicoContainer Organization; Copyright © 2003 NanoContainer Organization
- License/source reference: https://github.com/JetBrains/intellij-community/blob/idea/213.7172.25/license/picoContainer_license.txt
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.23/kotlin-compiler-embeddable-1.9.23.jar
- Modified by Jugg: No
- Notes: A reduced and relocated PicoContainer API is embedded in Kotlin compiler 1.9.23; upstream did not retain a reliably mappable standalone version, so no version is specified.

## 85. Protocol Buffers Java 3.19.3

- License: BSD-3-Clause
- Copyright: Copyright © 2008 Google Inc. and protobuf contributors
- License/source reference: https://github.com/protocolbuffers/protobuf/blob/v3.19.3/LICENSE
- Download/source: https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.19.3/protobuf-java-3.19.3.jar
- Modified by Jugg: No
- Notes: Embedded in R8 Resource Shrinker and distributed with the R8 JAR; listed separately from native Protocol Buffers statically linked into AAPT2 inclink.

## 86. Protocol Buffers, AOSP native runtime N/A

- License: BSD-3-Clause
- Copyright: Copyright © 2008 Google Inc. and protobuf contributors
- License/source reference: https://android.googlesource.com/platform/external/protobuf/+/refs/heads/master/LICENSE
- Download/source: https://android.googlesource.com/platform/external/protobuf/+archive/refs/heads/master.tar.gz
- Modified by Jugg: No
- Notes: Statically linked as libprotobuf-cpp-full into the distributed AAPT2 executable; the binary does not expose a reliably mappable version, so no version is specified.

## 87. Protocol Buffers, Kotlin relocated fork 2.6.1-1

- License: BSD-3-Clause
- Copyright: Copyright © 2008 Google Inc.
- License/source reference: https://github.com/protocolbuffers/protobuf/blob/v2.6.1/LICENSE
- Download/source: https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.23/kotlin-compiler-embeddable-1.9.23.jar
- Modified by Jugg: No
- Notes: This fork is embedded and relocated in Kotlin compiler 1.9.23; no standalone public JAR exists, and the code is distributed with the compiler JAR.

## 88. Eclipse JGit 6.8.0.202311291450-r

- License: EDL-1.0
- Copyright: Copyright © 2007 Eclipse Foundation, Inc. and its licensors; JGit contributors
- License/source reference: https://github.com/eclipse-jgit/jgit/blob/v6.8.0.202311291450-r/LICENSE
- Download/source: https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/6.8.0.202311291450-r/org.eclipse.jgit-6.8.0.202311291450-r.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency for Git.

## 89. istack-commons-runtime 3.0.8

- License: EDL-1.0 (BSD-3-Clause)
- Copyright: Copyright © 2017 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/com/sun/istack/istack-commons-runtime/3.0.8/istack-commons-runtime-3.0.8.pom
- Download/source: https://repo1.maven.org/maven2/com/sun/istack/istack-commons-runtime/3.0.8/istack-commons-runtime-3.0.8.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of JAXB Runtime.

## 90. Jakarta Activation API 1.2.1

- License: EDL-1.0 (BSD-3-Clause)
- Copyright: Copyright © 2018 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/jakarta/activation/jakarta.activation-api/1.2.1/jakarta.activation-api-1.2.1.pom
- Download/source: https://repo1.maven.org/maven2/jakarta/activation/jakarta.activation-api/1.2.1/jakarta.activation-api-1.2.1.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of JAXB Runtime.

## 91. Jakarta XML Binding API 2.3.2

- License: EDL-1.0 (BSD-3-Clause)
- Copyright: Copyright © 2017-2018 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/2.3.2/jakarta.xml.bind-api-2.3.2.pom
- Download/source: https://repo1.maven.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/2.3.2/jakarta.xml.bind-api-2.3.2.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of JAXB Runtime.

## 92. JAXB Runtime and TXW2 2.3.2

- License: EDL-1.0 (BSD-3-Clause)
- Copyright: Copyright © 2018 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/org/glassfish/jaxb/jaxb-runtime/2.3.2/jaxb-runtime-2.3.2.pom
- Download/source: https://repo1.maven.org/maven2/org/glassfish/jaxb/jaxb-runtime/2.3.2/jaxb-runtime-2.3.2.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as separate JAXB Runtime and TXW2 JARs.

## 93. StAX-Ex 1.8.1

- License: EDL-1.0 (BSD-3-Clause)
- Copyright: Copyright © 2017 Oracle and/or its affiliates
- License/source reference: https://repo1.maven.org/maven2/org/jvnet/staxex/stax-ex/1.8.1/stax-ex-1.8.1.pom
- Download/source: https://repo1.maven.org/maven2/org/jvnet/staxex/stax-ex/1.8.1/stax-ex-1.8.1.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR dependency of JAXB Runtime.

## 94. JDOM 2.0.6

- License: JDOM License (BSD-style)
- Copyright: Copyright © 2000-2012 Jason Hunter, Brett McLaughlin and JDOM contributors
- License/source reference: https://github.com/hunterhacker/jdom/blob/JDOM-2.0.6/LICENSE.txt
- Download/source: https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/intellij/deps/jdom/2.0.6/jdom-2.0.6.jar
- Modified by Jugg: No
- Notes: JDOM 2.0.6 is embedded and relocated in Kotlin compiler 1.9.23 and distributed with the compiler JAR.

## 95. Bouncy Castle Provider / PKIX 1.67

- License: MIT
- Copyright: Copyright © 2000-2020 The Legion of the Bouncy Castle Inc.
- License/source reference: https://github.com/bcgit/bc-java/blob/r1rv67/LICENSE.html
- Download/source: https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.67/bcprov-jdk15on-1.67.jar
- Modified by Jugg: No
- Notes: Bouncy Castle Provider and PKIX code is embedded in R8 8.4.21 and grouped here because it shares the same version, license, and modification status.

## 96. Checker Qual 3.33.0

- License: MIT
- Copyright: Copyright © 2004-present Checker Framework developers
- License/source reference: https://github.com/typetools/checker-framework/blob/checker-framework-3.33.0/checker-qual/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.33.0/checker-qual-3.33.0.jar
- Modified by Jugg: No
- Notes: Embedded in R8 8.4.21 and distributed with the R8 JAR.

## 97. Checker Qual 3.5.0

- License: MIT
- Copyright: Copyright © 2004-present by the Checker Framework developers
- License/source reference: https://github.com/typetools/checker-framework/blob/checker-framework-3.5.0/checker-qual/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/checkerframework/checker-qual/3.5.0/checker-qual-3.5.0.jar
- Modified by Jugg: No
- Notes: Distributed in the cmd_line standalone distribution as a transitive dependency of Guava 30.1-jre; coexists with Checker Qual 3.33.0 embedded in R8.

## 98. ClassGraph 4.8.110

- License: MIT
- Copyright: Copyright © 2019 Luke Hutchison
- License/source reference: https://github.com/classgraph/classgraph/blob/classgraph-4.8.110/LICENSE-ClassGraph.txt
- Download/source: https://repo1.maven.org/maven2/io/github/classgraph/classgraph/4.8.110/classgraph-4.8.110.jar
- Modified by Jugg: No
- Notes: Distributed with the plugin as a separate JAR direct dependency.

## 99. Expat 2.6.0

- License: MIT
- Copyright: Copyright © 1998-2000 Thai Open Source Software Center Ltd.; Expat contributors
- License/source reference: https://github.com/libexpat/libexpat/blob/R_2_6_0/expat/COPYING
- Download/source: https://github.com/libexpat/libexpat/archive/refs/tags/R_2_6_0.tar.gz
- Modified by Jugg: No
- Notes: Statically linked into the distributed AAPT2 executable; the binary includes Expat 2.6.0.

## 100. SLF4J API / NOP binding 1.7.36

- License: MIT
- Copyright: Copyright © 2004-2017 QOS.ch
- License/source reference: https://github.com/qos-ch/slf4j/blob/v_1.7.36/LICENSE.txt
- Download/source: https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar
- Modified by Jugg: No
- Notes: SLF4J API and NOP binding are distributed as separate JARs in the cmd_line distribution to provide a logging binding that emits no output.

## 101. jBCrypt, embedded in JSch N/A

- License: ISC
- Copyright: Copyright © 2006 Damien Miller
- License/source reference: https://github.com/mwiede/jsch/blob/jsch-0.2.16/LICENSE.jBCrypt.txt
- Download/source: https://repo1.maven.org/maven2/com/github/mwiede/jsch/0.2.16/jsch-0.2.16.jar
- Modified by Jugg: No
- Notes: Code is embedded in JSch 0.2.16 and distributed with its JAR; upstream does not identify the embedded version, so no version is specified.

## 102. zlib, Android platform API N/A

- License: zlib License
- Copyright: Copyright © 1995-2023 Jean-loup Gailly and Mark Adler
- License/source reference: https://android.googlesource.com/platform/external/zlib/+/refs/heads/master/zlib.h
- Download/source: https://android.googlesource.com/platform/external/zlib/+archive/refs/heads/master.tar.gz
- Modified by Jugg: No
- Notes: At runtime the JVMTI agent dynamically links to libz.so supplied by the Android device; the plugin does not bundle that platform library; its runtime version depends on the device, so no version is specified.

## 103. zlib, AOSP motley variant 1.3.0.1-motley

- License: zlib License
- Copyright: Copyright © 1995-2023 Jean-loup Gailly and Mark Adler
- License/source reference: https://android.googlesource.com/platform/external/zlib/+/932a58803c3d13295215446fa2bbf1aba5d327ef/zlib.h
- Download/source: https://android.googlesource.com/platform/external/zlib/+archive/932a58803c3d13295215446fa2bbf1aba5d327ef.tar.gz
- Modified by Jugg: No
- Notes: The AOSP motley variant is statically linked into the distributed AAPT2 executable; version 1.3.0.1-motley.

## 104. libpng 1.6.40

- License: Libpng-2.0
- Copyright: Copyright © 1995-2023 libpng authors
- License/source reference: https://github.com/pnggroup/libpng/blob/v1.6.40/LICENSE
- Download/source: https://github.com/pnggroup/libpng/archive/refs/tags/v1.6.40.tar.gz
- Modified by Jugg: No
- Notes: Statically linked into the distributed AAPT2 executable; the binary includes libpng 1.6.40.
