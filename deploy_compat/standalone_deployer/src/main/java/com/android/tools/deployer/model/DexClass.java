package com.android.tools.deployer.model;

import com.android.tools.deploy.proto.Deploy;
import com.google.common.collect.ImmutableList;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class DexClass {
   public final String name;
   public final long checksum;
   public final byte[] code;
   public final ApkEntry dex;
   public final ImmutableList<Deploy.ClassDef.FieldReInitState> variableStates;

   public DexClass(String name, long checksum, byte[] code, ApkEntry dex) {
      this(name, checksum, code, dex, ImmutableList.of());
   }

   public DexClass(String name, long checksum, byte[] code, ApkEntry dex, ImmutableList<Deploy.ClassDef.FieldReInitState> variableStates) {
      this.name = name;
      this.checksum = checksum;
      this.code = code;
      this.dex = dex;
      this.variableStates = variableStates;
   }

   public DexClass(DexClass old, ImmutableList<Deploy.ClassDef.FieldReInitState> variableStates) {
      this(old.name, old.checksum, old.code, old.dex, variableStates);
   }
}
