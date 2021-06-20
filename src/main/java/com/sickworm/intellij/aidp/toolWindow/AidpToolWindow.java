// Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.sickworm.intellij.aidp.toolWindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;

import javax.swing.*;

public class AidpToolWindow {

  private JButton refreshToolWindowButton;
  private JLabel currentDate;
  private JLabel currentTime;
  private JLabel timeZone;
  private JPanel myToolWindowContent;
  private JTextArea runningLog;

  private final Project project;

  @SuppressWarnings("unused")
  public AidpToolWindow(Project project, ToolWindow toolWindow) {
    this.project = project;
    refreshToolWindowButton.addActionListener(e -> apply());
  }

  public void apply() {
    runningLog.append("apply!!\napply!!\napply!!\napply!!\n");
//    AidpManager manager = AidpManager.Companion.getInstance(project);
//    if (manager != null) {
//      manager.apply();
//    }
  }

  public JPanel getContent() {
    return myToolWindowContent;
  }

}