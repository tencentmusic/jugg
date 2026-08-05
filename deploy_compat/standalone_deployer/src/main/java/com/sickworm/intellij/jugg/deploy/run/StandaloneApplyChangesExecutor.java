package com.sickworm.intellij.jugg.deploy.run;

import com.android.ddmlib.IDevice;
import com.android.sdklib.AndroidVersion;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.AdbInstaller;
import com.android.tools.deployer.ClassRedefiner;
import com.android.tools.deployer.DexComparator;
import com.android.tools.deployer.MetricsRecorder;
import com.android.tools.deployer.OptimisticApkSwapper;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.ApplicationDumper;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.DeployerOption;
import com.android.tools.deployer.common.DeploymentCacheDatabase;
import com.android.tools.deployer.common.InstallOptions;
import com.android.tools.deployer.common.Installer;
import com.android.tools.deployer.common.OverlayId;
import com.android.tools.deployer.common.UIService;
import com.android.tools.deployer.install.ApkInstaller;
import com.android.tools.deployer.install.InstallMode;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.tools.deployer.model.ApkParser;
import com.android.tools.deployer.model.App;
import com.android.tools.deployer.model.AppState;
import com.android.tools.deployer.model.DeploymentPlan;
import com.android.tools.idea.protobuf.ByteString;
import com.android.utils.ILogger;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Executes the fixed Quail Apply Changes implementation in the standalone Java 11 runtime. */
public final class StandaloneApplyChangesExecutor implements IApplyChangesExecutor {
   private static final String MEMORY_DEVICE_SERIAL = "memory";
   private static final String MEMORY_PACKAGE_NAME = "entry";
   private final DeployerOption deployOptions = new DeployerOption.Builder().build();
   private final MetricsRecorder metrics = new MetricsRecorder();

   @Override
   public JuggInstallSession createInstallSession(String installersFolder, IDevice device, ILogger logger,
                                                  Function1<? super String, Boolean> onPrompt,
                                                  Function1<? super String, Unit> onMessage) {
      AdbInstaller installer = new AdbInstaller(installersFolder, new AdbClient(device, logger),
            this.metrics.getDeployMetrics(), logger, AdbInstaller.Mode.DAEMON);
      return new JuggInstallSession(installer, installer.getVersion(), onPrompt, onMessage);
   }

   @Override
   public boolean install(IDevice device, JuggInstallSession session, ILogger logger, String packageName,
                          List<String> apks, JuggInstallSession.Mode installMode) throws DeployerException {
      List<Path> paths = apks.stream().map(Path::of).collect(Collectors.toList());
      App app = App.fromPaths(packageName, paths);
      DeploymentPlan plan = new DeploymentPlan(app, new AppState(device.getAbis().stream().findFirst().orElse("")));
      ApkInstaller installer = new ApkInstaller(new AdbClient(device, logger), createUiService(session),
            (Installer)session.getRawInstaller(), logger);
      return installer.install(plan, new DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build(),
            createInstallOptions(device, packageName), toQuailMode(installMode), this.metrics.getDeployMetrics());
   }

   @Override
   public JuggInstallSession.Mode getInstallMode() {
      return JuggInstallSession.Mode.DELTA;
   }

   @Override
   public List<Apk> parseApks(List<String> paths) {
      return ApkParser.parsePaths(paths);
   }

   @Override
   public List<Apk> dumpApks(JuggInstallSession session, List<? extends Apk> apks) throws DeployerException {
      return new ApplicationDumper((Installer)session.getRawInstaller()).dump(List.copyOf(apks)).apks;
   }

   @Override
   public String getPackageName(List<? extends Apk> apks) throws DeployerException {
      return ApplicationDumper.getPackageName(List.copyOf(apks));
   }

   @Override
   public JuggOverlayId createBaseOverlayId(List<? extends Apk> apks) {
      return toJuggOverlayId(new OverlayId(List.copyOf(apks)));
   }

   @Override
   public JuggOverlayId buildOverlayId(JuggOverlayId base, List<JuggOverlayFile> addedFiles) {
      OverlayId.Builder builder = OverlayId.builder((OverlayId)base.getRaw());
      for (JuggOverlayFile file : addedFiles) {
         builder.addOverlayFile(file.getPath(), file.getChecksum());
      }
      return toJuggOverlayId(builder.build());
   }

   @Override
   public JuggOverlayUpdate createOverlayUpdate(JuggDeploymentCacheEntry cachedDump,
                                                 DexComparator.ChangedClasses dexOverlays,
                                                 Map<ApkEntry, ? extends ByteString> fileOverlays) {
      Map<ApkEntry, ByteString> rawFiles = new HashMap<>(fileOverlays);
      OptimisticApkSwapper.OverlayUpdate raw = new OptimisticApkSwapper.OverlayUpdate(
            (DeploymentCacheDatabase.Entry)cachedDump.getRaw(), dexOverlays, rawFiles);
      return new JuggOverlayUpdate(cachedDump, dexOverlays, rawFiles, raw);
   }

   @Override
   public JuggOverlayId optimisticSwap(JuggInstallSession session, Map<Integer, JuggClassRedefiner> redefiners,
                                       String packageName, boolean restartActivity, List<Integer> pids,
                                       Deploy.Arch arch, JuggOverlayUpdate overlayUpdate) throws DeployerException {
      Map<Integer, ClassRedefiner> rawRedefiners = new HashMap<>();
      redefiners.forEach((pid, redefiner) -> rawRedefiners.put(pid, (ClassRedefiner)redefiner.getRaw()));
      OptimisticApkSwapper swapper = new OptimisticApkSwapper((Installer)session.getRawInstaller(), rawRedefiners,
            restartActivity, this.deployOptions, this.metrics);
      OptimisticApkSwapper.SwapResult result = swapper.optimisticSwap(packageName, pids, arch,
            (OptimisticApkSwapper.OverlayUpdate)overlayUpdate.getRaw());
      return toJuggOverlayId(result.overlayId);
   }

   @Override
   public JuggDeploymentCacheEntry createDeploymentCacheEntry(List<? extends Apk> apks, JuggOverlayId overlayId) {
      List<Apk> rawApks = List.copyOf(apks);
      DeploymentCacheDatabase database = new DeploymentCacheDatabase(1);
      database.store(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME, rawApks, (OverlayId)overlayId.getRaw());
      return new JuggDeploymentCacheEntry(database.get(MEMORY_DEVICE_SERIAL, MEMORY_PACKAGE_NAME), rawApks, overlayId);
   }

   @Override
   public JuggDeployerException remoteApkNotFound() {
      return toJuggException(DeployerException.remoteApkNotFound());
   }

   @Override
   public JuggDeployerException overlayIdMismatch() {
      return toJuggException(DeployerException.overlayIdMismatch());
   }

   @Override
   public JuggDeployerException apiNotSupported() {
      return toJuggException(DeployerException.apiNotSupported());
   }

   @Override
   public JuggDeployerException wrapDeployerException(Throwable error) {
      return error instanceof DeployerException ? toJuggException((DeployerException)error) : null;
   }

   private static InstallOptions createInstallOptions(IDevice device, String applicationId) {
      InstallOptions.Builder options = InstallOptions.builder().setAllowDebuggable();
      if (device.supportsFeature(IDevice.HardwareFeature.EMBEDDED)) options.setGrantAllPermissions();
      if (device.getVersion().isGreaterOrEqualThan(28)) options.setInstallFullApk();
      if (device.getVersion().isGreaterOrEqualThan(AndroidVersion.VersionCodes.N)) options.setDontKill();
      return options.setSkipVerification(device, applicationId).build();
   }

   private static UIService createUiService(JuggInstallSession session) {
      return new UIService() {
         @Override
         public boolean prompt(String message) {
            return session.prompt(message);
         }

         @Override
         public void message(String message) {
            session.message(message);
         }
      };
   }

   private static InstallMode toQuailMode(JuggInstallSession.Mode mode) {
      switch (mode) {
         case DELTA:
            return InstallMode.DELTA;
         case DELTA_NO_SKIP:
            return InstallMode.DELTA_NO_SKIP;
         case FULL:
            return InstallMode.FULL;
         default:
            throw new IllegalArgumentException("Unsupported install mode: " + mode);
      }
   }

   private static JuggOverlayId toJuggOverlayId(OverlayId overlayId) {
      List<JuggOverlayFile> files = overlayId.getOverlayContents().allFiles().stream()
            .map(path -> new JuggOverlayFile(path, overlayId.getOverlayContents().getFileChecksum(path)))
            .collect(Collectors.toList());
      return new JuggOverlayId(overlayId, overlayId.getSha(), overlayId.isBaseInstall(), files);
   }

   private static JuggDeployerException toJuggException(DeployerException error) {
      return new JuggDeployerException(error.getError().ordinal(), error.getMessage(), error.getDetails(), error);
   }
}
