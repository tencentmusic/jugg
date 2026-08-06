package com.sickworm.intellij.jugg.deploy.run;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.DexComparator;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.DexClass;
import com.google.common.collect.ImmutableList;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/** Converts owned deployment values only at the standalone Quail boundary. */
final class StandaloneDeployApiConverter {
   private final Map<com.android.ddmlib.IDevice, WeakReference<StandaloneDeviceAdapter>> devices = new WeakHashMap<>();
   synchronized com.sickworm.intellij.jugg.deploy.api.IDevice toJuggDevice(com.android.ddmlib.IDevice device) {
      WeakReference<StandaloneDeviceAdapter> reference = this.devices.get(device);
      StandaloneDeviceAdapter existing = reference == null ? null : reference.get();
      if (existing != null) return existing;
      StandaloneDeviceAdapter result = new StandaloneDeviceAdapter(device);
      this.devices.put(device, new WeakReference<>(result));
      return result;
   }

   com.android.ddmlib.IDevice toStudioDevice(com.sickworm.intellij.jugg.deploy.api.IDevice device) {
      if (!(device instanceof com.sickworm.intellij.jugg.deploy.api.IRuntimeDevice)) {
         throw new IllegalArgumentException("Device does not belong to the standalone runtime");
      }
      Object runtimeDevice = ((com.sickworm.intellij.jugg.deploy.api.IRuntimeDevice)device).getRuntimeDevice();
      if (!(runtimeDevice instanceof com.android.ddmlib.IDevice)) {
         throw new IllegalArgumentException("Device does not belong to the standalone runtime");
      }
      return (com.android.ddmlib.IDevice)runtimeDevice;
   }

   com.android.utils.ILogger toStudioLogger(com.sickworm.intellij.jugg.deploy.api.ILogger logger) {
      if (logger instanceof com.android.utils.ILogger) return (com.android.utils.ILogger)logger;
      return new com.android.utils.ILogger() {
         @Override
         public void error(Throwable t, String msgFormat, Object... args) {
            logger.error(t, msgFormat, args);
         }

         @Override
         public void warning(String msgFormat, Object... args) {
            logger.warning(msgFormat, args);
         }

         @Override
         public void info(String msgFormat, Object... args) {
            logger.info(msgFormat, args);
         }

         @Override
         public void verbose(String msgFormat, Object... args) {
            logger.verbose(msgFormat, args);
         }
      };
   }

   com.sickworm.intellij.jugg.deploy.api.ILogger toJuggLogger(com.android.utils.ILogger logger) {
      if (logger instanceof com.sickworm.intellij.jugg.deploy.api.ILogger) {
         return (com.sickworm.intellij.jugg.deploy.api.ILogger)logger;
      }
      return new com.sickworm.intellij.jugg.deploy.api.ILogger() {
         @Override
         public void error(Throwable t, String msgFormat, Object... args) {
            logger.error(t, msgFormat, args);
         }

         @Override
         public void warning(String msgFormat, Object... args) {
            logger.warning(msgFormat, args);
         }

         @Override
         public void info(String msgFormat, Object... args) {
            logger.info(msgFormat, args);
         }

         @Override
         public void verbose(String msgFormat, Object... args) {
            logger.verbose(msgFormat, args);
         }
      };
   }

   com.sickworm.intellij.jugg.deploy.api.Apk toJuggApk(com.android.tools.deployer.model.Apk apk) {
      com.sickworm.intellij.jugg.deploy.api.Apk placeholder = createJuggApk(apk, Collections.emptyMap());
      Map<String, com.sickworm.intellij.jugg.deploy.api.ApkEntry> entries = new LinkedHashMap<>();
      apk.apkEntries.forEach((name, entry) -> entries.put(name,
            new com.sickworm.intellij.jugg.deploy.api.ApkEntry(entry.getName(), entry.getChecksum(), placeholder)));
      return createJuggApk(apk, entries);
   }

   com.android.tools.deployer.model.Apk toStudioApk(com.sickworm.intellij.jugg.deploy.api.Apk apk) {
      Object runtimeObject = apk.getRuntimeObject();
      if (!(runtimeObject instanceof com.android.tools.deployer.model.Apk)) {
         throw new IllegalArgumentException("APK does not belong to the standalone deployer runtime");
      }
      return (com.android.tools.deployer.model.Apk)runtimeObject;
   }

   ApkEntry toStudioApkEntry(com.sickworm.intellij.jugg.deploy.api.ApkEntry entry) {
      return new ApkEntry(entry.getName(), entry.getChecksum(), toStudioApk(entry.getApk()));
   }

   com.sickworm.intellij.jugg.deploy.api.ApkEntry toJuggApkEntry(ApkEntry entry) {
      return new com.sickworm.intellij.jugg.deploy.api.ApkEntry(
            entry.getName(), entry.getChecksum(), toJuggApk(entry.getApk()));
   }

   com.android.tools.idea.protobuf.ByteString toStudioByteString(com.sickworm.intellij.jugg.deploy.api.ByteString content) {
      return com.android.tools.idea.protobuf.ByteString.copyFrom(content.toByteArray());
   }

   com.sickworm.intellij.jugg.deploy.api.ByteString toJuggByteString(com.android.tools.idea.protobuf.ByteString content) {
      return com.sickworm.intellij.jugg.deploy.api.ByteString.copyFrom(content.toByteArray());
   }

   DexComparator.ChangedClasses toStudioChangedClasses(
         com.sickworm.intellij.jugg.deploy.api.DexComparator.ChangedClasses changes) {
      return new DexComparator.ChangedClasses(
            changes.getNewClasses().stream().map(this::toStudioDexClass).collect(Collectors.toList()),
            changes.getModifiedClasses().stream().map(this::toStudioDexClass).collect(Collectors.toList()));
   }

   com.sickworm.intellij.jugg.deploy.api.DexComparator.ChangedClasses toJuggChangedClasses(
         DexComparator.ChangedClasses changes) {
      return new com.sickworm.intellij.jugg.deploy.api.DexComparator.ChangedClasses(
            changes.newClasses.stream().map(this::toJuggDexClass).collect(Collectors.toList()),
            changes.modifiedClasses.stream().map(this::toJuggDexClass).collect(Collectors.toList()));
   }

   Deploy.Arch toStudioArch(com.sickworm.intellij.jugg.deploy.api.Deploy.Arch arch) {
      return Deploy.Arch.valueOf(arch.name());
   }

   private DexClass toStudioDexClass(com.sickworm.intellij.jugg.deploy.api.DexClass dexClass) {
      com.sickworm.intellij.jugg.deploy.api.ApkEntry dex = dexClass.getDex();
      return new DexClass(dexClass.getName(), dexClass.getChecksum(), dexClass.getCode(),
            dex == null ? null : toStudioApkEntry(dex), ImmutableList.copyOf(dexClass.getVariableStates().stream()
                  .map(this::toStudioFieldReInitState).collect(Collectors.toList())));
   }

   private com.sickworm.intellij.jugg.deploy.api.DexClass toJuggDexClass(DexClass dexClass) {
      return new com.sickworm.intellij.jugg.deploy.api.DexClass(
            dexClass.name, dexClass.checksum, dexClass.code,
            dexClass.dex == null ? null : toJuggApkEntry(dexClass.dex),
            dexClass.variableStates.stream().map(this::toJuggFieldReInitState).collect(Collectors.toList()));
   }

   private Deploy.ClassDef.FieldReInitState toStudioFieldReInitState(
         com.sickworm.intellij.jugg.deploy.api.FieldReInitState state) {
      return Deploy.ClassDef.FieldReInitState.newBuilder()
            .setName(state.getName())
            .setType(state.getType())
            .setStaticVar(state.getStaticVar())
            .setState(Deploy.ClassDef.FieldReInitState.VariableState.valueOf(state.getState().name()))
            .setValue(state.getValue())
            .build();
   }

   private com.sickworm.intellij.jugg.deploy.api.FieldReInitState toJuggFieldReInitState(
         Deploy.ClassDef.FieldReInitState state) {
      return new com.sickworm.intellij.jugg.deploy.api.FieldReInitState(
            state.getName(), state.getType(), state.getStaticVar(),
            com.sickworm.intellij.jugg.deploy.api.FieldReInitState.VariableState.valueOf(state.getState().name()),
            state.getValue());
   }

   private static com.sickworm.intellij.jugg.deploy.api.Apk createJuggApk(
         com.android.tools.deployer.model.Apk apk,
         Map<String, com.sickworm.intellij.jugg.deploy.api.ApkEntry> entries) {
      return new com.sickworm.intellij.jugg.deploy.api.Apk(
            apk.name, apk.checksum, apk.path, apk.packageName, apk.libraryAbis, apk.targetPackages,
            apk.sdkLibraries, entries, apk);
   }

   private static final class StandaloneDeviceAdapter implements com.sickworm.intellij.jugg.deploy.api.IRuntimeDevice {
      private final com.android.ddmlib.IDevice device;

      private StandaloneDeviceAdapter(com.android.ddmlib.IDevice device) {
         this.device = device;
      }

      @Override
      public Object getRuntimeDevice() {
         return this.device;
      }

      @Override
      public String getSerialNumber() {
         return this.device.getSerialNumber();
      }

      @Override
      public boolean isOnline() {
         return this.device.isOnline();
      }

      @Override
      public String getName() {
         return this.device.getName();
      }

      @Override
      public com.sickworm.intellij.jugg.deploy.api.AndroidVersion getVersion() {
         return new com.sickworm.intellij.jugg.deploy.api.AndroidVersion(
               this.device.getVersion().getApiLevel(), this.device.getVersion().getCodename());
      }

      @Override
      public List<String> getAbis() {
         return this.device.getAbis();
      }

      @Override
      public int getClientCount() {
         return this.device.getClients().length;
      }

      @Override
      public String getProperty(String name) {
         return this.device.getProperty(name);
      }

      @Override
      public boolean equals(Object other) {
         return other instanceof StandaloneDeviceAdapter && this.device.equals(((StandaloneDeviceAdapter)other).device);
      }

      @Override
      public int hashCode() {
         return this.device.hashCode();
      }
   }
}
