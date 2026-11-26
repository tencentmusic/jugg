package com.android.tools.deployer.common;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.DumpRequest;
import com.android.tools.deploy.proto.Deploy.FindDexRequest;
import com.android.tools.deploy.proto.Deploy.InstallCoroutineAgentRequest;
import com.android.tools.deploy.proto.Deploy.InstallerRequest;
import com.android.tools.deploy.proto.Deploy.OverlayIdPush;
import com.android.tools.deploy.proto.Deploy.TimeoutRequest;
import com.android.tools.deployer.Version;
import com.android.utils.ILogger;
import com.android.utils.NullLogger;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public abstract class Installer {
   public static final long FIRST_ID = 1000L;
   private AtomicLong id;
   protected final ILogger logger;

   protected abstract Deploy.InstallerResponse sendInstallerRequest(Deploy.InstallerRequest request, long timeOutMs) throws IOException;

   protected abstract void onAsymetry(Deploy.InstallerRequest req, Deploy.InstallerResponse resp) throws IOException;

   protected Installer() {
      this(new NullLogger());
   }

   protected Installer(ILogger logger) {
      this.id = new AtomicLong(1000L);
      this.logger = logger;
   }

   private void errorAsymetry(Deploy.InstallerRequest req, Deploy.InstallerResponse resp) throws IOException {
      this.onAsymetry(req, resp);
      String msg = String.format(Locale.US, "Unexpected response '%s' for request '%s'", resp.getExtraCase().name(), req.getRequestCase().name());
      if (mismatch(req, resp)) {
         msg = msg + String.format(Locale.US, ", SeqNumber mismatched req=(%d), res=(%d)", resp.getId(), req.getId());
      }

      throw new IOException(msg);
   }

   public Deploy.InstallCoroutineAgentResponse installCoroutineAgent(String packageName, Deploy.Arch arch) throws IOException {
      Deploy.InstallCoroutineAgentRequest.Builder installCoroutineAgentRequestBuilder = InstallCoroutineAgentRequest.newBuilder();
      installCoroutineAgentRequestBuilder.setPackageName(packageName).setArch(arch);
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("installcoroutineagent").setInstallCoroutineAgentRequest(installCoroutineAgentRequestBuilder);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_INSTALL_COROUTINE);
      if (!resp.hasInstallCoroutineAgentResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.InstallCoroutineAgentResponse response = resp.getInstallCoroutineAgentResponse();
      this.logger.verbose("installer install coroutine agent: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.DumpResponse dump(List<String> packageNames) throws IOException {
      Deploy.DumpRequest.Builder dumpRequestBuilder = DumpRequest.newBuilder();

      for(String packageName : packageNames) {
         dumpRequestBuilder.addPackageNames(packageName);
      }

      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("dump").setDumpRequest(dumpRequestBuilder);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_DUMP_MS);
      if (!resp.hasDumpResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.DumpResponse response = resp.getDumpResponse();
      this.logger.verbose("installer dump: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.FindDexResponse findDex(String packageName, String classSignature) throws IOException {
      Deploy.FindDexRequest.Builder dexRequestBuilder = FindDexRequest.newBuilder();
      dexRequestBuilder.setPackageName(packageName);
      dexRequestBuilder.setClassSignature(classSignature);
      long fileSizeLimit = 104857600L;
      dexRequestBuilder.setDexFileSizeLimit(fileSizeLimit);
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("finddex").setFindDexRequest(dexRequestBuilder);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_FIND_DEX_MS);
      if (!resp.hasFindDexResponse()) {
         this.errorAsymetry(req, resp);
      }

      return resp.getFindDexResponse();
   }

   public Deploy.SwapResponse swap(Deploy.SwapRequest swapRequest) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("swap");
      reqBuilder.setSwapRequest(swapRequest);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_SWAP_MS);
      if (!resp.hasSwapResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.SwapResponse response = resp.getSwapResponse();
      this.logger.verbose("installer swap: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.SwapResponse overlaySwap(Deploy.OverlaySwapRequest overlaySwapRequest) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("overlayswap");
      reqBuilder.setOverlaySwapRequest(overlaySwapRequest);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_OSWAP_MS);
      if (!resp.hasSwapResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.SwapResponse response = resp.getSwapResponse();
      this.logger.verbose("installer overlayswap: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.OverlayInstallResponse overlayInstall(Deploy.OverlayInstallRequest overlayInstallRequest) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("overlayinstall");
      reqBuilder.setOverlayInstall(overlayInstallRequest);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_OINSTALL_MS);
      if (!resp.hasOverlayInstallResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.OverlayInstallResponse response = resp.getOverlayInstallResponse();
      this.logger.verbose("installer overlayinstall: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.RootPushInstallResponse rootPushInstall(Deploy.RootPushInstallRequest request) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("rootpushinstall");
      reqBuilder.setRootPushInstallRequest(request);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_ROOT_PUSH_INSTALL);
      if (!resp.hasRootPushInstallResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.RootPushInstallResponse response = resp.getRootPushInstallResponse();
      this.logger.verbose("installer pushinstall: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.OverlayIdPushResponse verifyOverlayId(String packageName, String oid) throws IOException {
      Deploy.OverlayIdPush overlayIdPushRequest = createOidPushRequest(packageName, oid, oid, false);
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("overlayidpush");
      reqBuilder.setOverlayIdPush(overlayIdPushRequest);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_VERIFY_OID_MS);
      if (!resp.hasOverlayidpushResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.OverlayIdPushResponse response = resp.getOverlayidpushResponse();
      this.logger.verbose("installer overlayidpush: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.NetworkTestResponse networkTest(Deploy.NetworkTestRequest testParams) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("networktest");
      reqBuilder.setNetworkTestRequest(testParams);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_NETTEST);
      if (!resp.hasNetworkTestResponse()) {
         this.errorAsymetry(req, resp);
      }

      return resp.getNetworkTestResponse();
   }

   public Deploy.RestartActivityResponse restartActivity(Deploy.RestartActivityRequest request) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("restartactivity");
      reqBuilder.setRestartActivityRequest(request);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_RESTART_ACTIVITY_MS);
      if (!resp.hasRestartActivityResponse()) {
         this.errorAsymetry(req, resp);
      }

      return resp.getRestartActivityResponse();
   }

   private static Deploy.OverlayIdPush createOidPushRequest(String packageName, String prevOid, String nextOid, boolean wipeOverlays) {
      return OverlayIdPush.newBuilder().setPackageName(packageName).setPrevOid(prevOid).setNextOid(nextOid).setWipeOverlays(wipeOverlays).build();
   }

   public Deploy.DeltaPreinstallResponse deltaPreinstall(Deploy.InstallInfo info) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("deltapreinstall");
      reqBuilder.setInstallInfoRequest(info);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_DELTA_PREINSTALL_MS);
      if (!resp.hasDeltapreinstallResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.DeltaPreinstallResponse response = resp.getDeltapreinstallResponse();
      this.logger.verbose("installer deltapreinstall: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.DeltaInstallResponse deltaInstall(Deploy.InstallInfo info) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("deltainstall");
      reqBuilder.setInstallInfoRequest(info);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_DELTA_INSTALL_MS);
      if (!resp.hasDeltainstallResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.DeltaInstallResponse response = resp.getDeltainstallResponse();
      this.logger.verbose("installer deltainstall: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.LiveLiteralUpdateResponse updateLiveLiterals(Deploy.LiveLiteralUpdateRequest liveLiterals) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("liveliteralupdate");
      reqBuilder.setLiveLiteralRequest(liveLiterals);
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_UPDATE_LL);
      if (!resp.hasLiveLiteralResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.LiveLiteralUpdateResponse response = resp.getLiveLiteralResponse();
      this.logger.verbose("installer liveliteralupdate: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.LiveEditResponse liveEdit(Deploy.LiveEditRequest ler) throws IOException {
      Deploy.InstallerRequest.Builder requestBuilder = this.buildRequest("liveedit");
      requestBuilder.setLeRequest(ler);
      Deploy.InstallerRequest req = requestBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_LIVE_EDIT);
      if (!resp.hasLeResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.LiveEditResponse response = resp.getLeResponse();
      this.logger.verbose("installer liveEdit: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.ComposeStatusResponse composeStatus(Deploy.ComposeStatusRequest csr) throws IOException {
      Deploy.InstallerRequest.Builder requestBuilder = this.buildRequest("composestatus");
      requestBuilder.setComposeStatusRequest(csr);
      Deploy.InstallerRequest req = requestBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_COMPOSE_STATUS);
      if (!resp.hasComposeStatusResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.ComposeStatusResponse response = resp.getComposeStatusResponse();
      this.logger.verbose("installer composeStatus:: " + response.getStatus().toString(), new Object[0]);
      return response;
   }

   public Deploy.TimeoutResponse timeout(long timeoutMs) throws IOException {
      Deploy.InstallerRequest.Builder reqBuilder = this.buildRequest("timeout");
      Deploy.TimeoutRequest.Builder timeoutBuilder = TimeoutRequest.newBuilder();
      timeoutBuilder.setTimeoutMs(timeoutMs);
      reqBuilder.setTimeoutRequest(timeoutBuilder.build());
      Deploy.InstallerRequest req = reqBuilder.build();
      Deploy.InstallerResponse resp = this.send(req, Timeouts.CMD_TIMEOUT);
      if (!resp.hasTimeoutResponse()) {
         this.errorAsymetry(req, resp);
      }

      Deploy.TimeoutResponse response = resp.getTimeoutResponse();
      return response;
   }

   public String getVersion() {
      return Version.hash();
   }

   private Deploy.InstallerRequest.Builder buildRequest(String commandName) {
      Deploy.InstallerRequest.Builder request = InstallerRequest.newBuilder().setCommandName(commandName).setId(this.id.getAndIncrement()).setVersion(this.getVersion());
      return request;
   }

   private static boolean mismatch(Deploy.InstallerRequest req, Deploy.InstallerResponse resp) {
      return req.getId() != resp.getId();
   }

   private Deploy.InstallerResponse send(Deploy.InstallerRequest req, long timeOutMs) throws IOException {
      Deploy.InstallerResponse resp = this.sendInstallerRequest(req, timeOutMs);
      this.logger.verbose("Sent request %s", new Object[]{req.getRequestCase().name()});
      Deploy.InstallerResponse.ExtraCase respCase = resp.getExtraCase();
      this.logger.verbose("Received response %s", new Object[]{respCase.name()});
      if (mismatch(req, resp)) {
         this.errorAsymetry(req, resp);
      }

      return resp;
   }
}
