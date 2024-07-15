package com.intellij.openapi.project;

import com.intellij.openapi.Disposable;

public interface Project extends Disposable {

    String getName();

    String getBasePath();
}
