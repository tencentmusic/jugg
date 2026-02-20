/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.sickworm.intellij.jugg.apk.manifest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ManifestActivityInfo AndroidManifest model focused on package, split, debuggable, and activity entries.
 * Collaboration: Created by BinaryXmlParser.parseBinaryFromStream, populated via parseNode, and consumed by APK/deploy activity selection logic.
 * Data Contract: packageName defaults to empty string; featureSplit and debuggable may remain null when absent.
 */
public class ManifestActivityInfo {

  private List<NodeActivity> myActivities;
  private String myPackageName;
  private String myFeatureSplit;
  private String myDebuggable;

  public ManifestActivityInfo() {
    myActivities = new ArrayList<>();
    myPackageName = "";
  }

  @NotNull
  public String packageName() {
    return myPackageName;
  }

  @NotNull
  public List<NodeActivity> activities() {
    return myActivities;
  }

  @Nullable
  public String featureSplit() {
    return myFeatureSplit;
  }

  @Nullable
  public String debuggable() {
    return myDebuggable;
  }

  public void parseNode(@NotNull XmlNode node) {
    for (String attribute : node.attributes().keySet()) {
      String value = node.attributes().get(attribute);
      if ("package".equals(attribute)) {
        myPackageName = value;
      }
      if ("split".equals(attribute)) {
        myFeatureSplit = value;
      }
    }

    for(XmlNode child : node.childs()) {
      if ("application".equals(child.name())) {
        parseApplication(child);
      }
    }
  }

  private void parseApplication(@NotNull XmlNode node) {
    for(XmlNode child : node.childs()) {
      if ("activity".equals(child.name()) || "activity-alias".equals(child.name())) {
        NodeActivity activity = new NodeActivity(child, myPackageName);
        myActivities.add(activity);
      }
    }

    for (String attribute : node.attributes().keySet()) {
      String value = node.attributes().get(attribute);
      if ("debuggable".equals(attribute)) {
          myDebuggable = value;
      }
    }
  }
}
