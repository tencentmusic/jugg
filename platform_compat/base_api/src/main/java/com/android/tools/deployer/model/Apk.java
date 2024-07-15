
package com.android.tools.deployer.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class Apk implements Serializable {
    public final String name;
    public final String checksum;
    public final String path;
    public final String packageName;
    public final List<String> libraryAbis;
    public final List<String> targetPackages;
    public final Map<String, ApkEntry> apkEntries;
    public final List<String> sdkLibraries;

    private Apk(String name, String checksum, String path, String packageName, List<String> libraryAbis, List<String> targetPackages, List<String> sdkLibraries, Map<String, ApkEntry> apkEntries) {
        this.name = name;
        this.checksum = checksum;
        this.path = path;
        this.packageName = packageName;
        this.libraryAbis = libraryAbis;
        this.targetPackages = targetPackages;
        this.sdkLibraries = sdkLibraries;
        this.apkEntries = apkEntries;
    }

}
