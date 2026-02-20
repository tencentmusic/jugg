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

import java.util.ArrayList;
import java.util.List;

/**
 * NodeActivity one <activity>/<activity-alias> node with resolved names and parsed intent filters.
 * Collaboration: Built by ManifestActivityInfo.parseApplication and queried by default-activity resolution through hasAction/hasCategory/getRealActivityQname.
 * Data Contract: Short class names are resolved against packageName and parsed intent-filter children are stored in myIntentFilters.
 */
public class NodeActivity {

  protected String myName = "";
  protected String myQname = "";
  protected boolean myEnabled = true;
  protected boolean myExported = true;
  protected List<IntentFilter> myIntentFilters = new ArrayList<>();

  protected String myTargetActivity = null;
  protected String myTargetActivityQname = null;

  /**
   * Parse an activity xml element (see https://developer.android.com/guide/topics/manifest/activity-element).
   */
  public NodeActivity(@NotNull XmlNode node, @NotNull String packageName) {
    for (String attribute : node.attributes().keySet()) {
      String value = node.attributes().get(attribute);

      if ("name".equals(attribute)) {
        myName = value;
        if (myName.startsWith(".")) {
          myQname = packageName + myName;
        } else {
          myQname = myName;
        }
      } else if ("enabled".equals(attribute)) {
        myEnabled = value.isEmpty() || "true".equals(value);
      } else if ("exported".equals(attribute)) {
        myExported = value.isEmpty() || "true".equals(value);
      } else if ("targetActivity".equals(attribute)) {
        myTargetActivity = value;
        if (myTargetActivity.startsWith(".")) {
          myTargetActivityQname = packageName + myTargetActivity;
        } else {
          myTargetActivityQname = myTargetActivity;
        }
      }
    }

    for(XmlNode child : node.childs()) {
      if ("intent-filter".equals(child.name())) {
        IntentFilter intentFilter = parseIntentFilter(child);
        myIntentFilters.add(intentFilter);
      }
    }
  }

  @NotNull
  private static IntentFilter parseIntentFilter(@NotNull XmlNode node) {
    IntentFilter intentFilter = new IntentFilter();
    for(XmlNode child : node.childs()) {
      if ("action".equals(child.name())) {
        String action = getNameChildNodeValue(child);
        intentFilter.addAction(action);
      } else if ("category".equals(child.name())) {
        String category = getNameChildNodeValue(child);
        intentFilter.addCategory(category);
      }
    }
    return intentFilter;
  }

  @NotNull
  private static String getNameChildNodeValue(@NotNull XmlNode node) {
    for (String attribute : node.attributes().keySet()) {
      if ("name".equals(attribute)) {
        return node.attributes().get(attribute);
      }
    }
    return "";
  }

  public boolean hasCategory(@NotNull String name) {
    for(IntentFilter intentFilter : myIntentFilters) {
      if (intentFilter.hasCategory(name)) {
        return true;
      }
    }
    return false;
  }

  public boolean hasAction(@NotNull String name) {
    for(IntentFilter intentFilter : myIntentFilters) {
      if (intentFilter.hasAction(name)) {
        return true;
      }
    }
    return false;
  }

  public boolean isEnabled() {
    return myEnabled;
  }

  @Nullable
  public Boolean getExported() {
    return myExported;
  }

  public boolean hasIntentFilter() {
    return !myIntentFilters.isEmpty();
  }

  @Nullable
  public String getQualifiedName() {
    return myQname;
  }

  @NotNull
  String getName() {
    return myName;
  }

  @Nullable
  public String getRealActivityQname() {
    if (myTargetActivityQname != null) {
      return myTargetActivityQname;
    }
    return myQname;
  }

  @NotNull
  public List<IntentFilter> getIntentFilters() {
    return myIntentFilters;
  }
}
