package com.android.tools.deployer.model;

public class DexClass {
    public final String name;
    public final long checksum;
    public final byte[] code;
    public final ApkEntry dex;

    public DexClass(String name, long checksum, byte[] code, ApkEntry dex) {
        this.name = name;
        this.checksum = checksum;
        this.code = code;
        this.dex = dex;
    }
}
