// Copyright 2000-2020 JetBrains s.r.o. and other contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.sickworm.intellij.aidp.toolWindow;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.sickworm.intellij.aidp.AidpLogger;
import com.sickworm.intellij.aidp.AidpManager;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    AidpLogger.INSTANCE.listenProjectLog(project, new LoggerPrinter());
  }

  public void apply() {
    runningLog.append("apply!!\n");
    AidpManager manager = AidpManager.Companion.getInstance(project);
    if (manager != null) {
      manager.apply();
    }
  }

  public JPanel getContent() {
    return myToolWindowContent;
  }

  private void append(String message) {
    runningLog.append(message + "\n");
  }

  private class LoggerPrinter extends Logger {

    @Override
    public boolean isDebugEnabled() {
      return false;
    }

    @Override
    public void debug(String message) {
      append("DEBUG: " + message);
    }

    @Override
    public void debug(@Nullable Throwable t) {
      append("DEBUG: " + t);
    }

    @Override
    public void debug(String message, @Nullable Throwable t) {
      append("DEBUG: " + message + "\n" + t);
    }

    @Override
    public void info(String message) {
      append("INFO: " + message);
    }

    @Override
    public void info(String message, @Nullable Throwable t) {
      append("INFO: " + message + "\n" + t);
    }

    @Override
    public void warn(String message, @Nullable Throwable t) {
      append("WARN: " + message + "\n" + t);
    }

    @Override
    public void error(String message, @Nullable Throwable t, String @NotNull ... details) {
      append("ERROR: " + message + "\n" + t);
      for (String detail : details) {
        append(detail);
      }
    }

    @Override
    public void setLevel(@NotNull Level level) {

    }
  }
}