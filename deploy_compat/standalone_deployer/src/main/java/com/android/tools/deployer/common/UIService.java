package com.android.tools.deployer.common;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public interface UIService {
   boolean prompt(String result);

   void message(String message);
}
