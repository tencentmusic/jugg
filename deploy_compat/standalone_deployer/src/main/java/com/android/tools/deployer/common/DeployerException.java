package com.android.tools.deployer.common;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.SwapResponse.Status;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.List;

/** Maps Quail installer and agent failures into stable deployer error categories. */
public class DeployerException extends RuntimeException {
   private Error error;
   private String code;
   private String details;
   private static String[] NO_ARGS = new String[0];
   private static final ImmutableMap<JvmtiErrorCode, Error> ERROR_CODE_TO_ERROR;

   private DeployerException(Error error) {
      this(error, (Enum)null, NO_ARGS, NO_ARGS);
   }

   private DeployerException(Error error, String[] messageArgs, String... detailArgs) {
      this(error, (Enum)null, messageArgs, detailArgs);
   }

   private DeployerException(Error error, Enum code, String[] messageArgs, String... detailArgs) {
      super(String.format(error.message, (Object[])messageArgs));
      this.error = error;
      this.code = code == null ? error.name() : error.name() + "." + code.name();
      this.details = String.format(error.details, (Object[])detailArgs);
   }

   public Error getError() {
      return this.error;
   }

   public String getId() {
      return this.code;
   }

   public String getDetails() {
      return this.details;
   }

   public static DeployerException unknownPackage(String packageName) {
      return new DeployerException(DeployerException.Error.DUMP_UNKNOWN_PACKAGE, NO_ARGS, new String[]{packageName});
   }

   public static DeployerException dumpBadResponse(List<String> packages, int status) {
      String[] args = new String[2];
      args[0] = String.join(",", packages);
      args[1] = Integer.toString(status);
      return new DeployerException(DeployerException.Error.DUMP_ERROR, args, new String[]{args[0], args[1]});
   }

   public static DeployerException unknownProcess() {
      return new DeployerException(DeployerException.Error.DUMP_UNKNOWN_PROCESS);
   }

   public static DeployerException remoteApkNotFound() {
      return new DeployerException(DeployerException.Error.REMOTE_APK_NOT_FOUND_IN_DB);
   }

   public static DeployerException apkCountMismatch() {
      return new DeployerException(DeployerException.Error.DIFFERENT_NUMBER_OF_APKS);
   }

   public static DeployerException apkNameMismatch() {
      return new DeployerException(DeployerException.Error.DIFFERENT_APK_NAMES);
   }

   public static DeployerException processCrashing(String processName) {
      return new DeployerException(DeployerException.Error.PROCESS_CRASHING, NO_ARGS, new String[]{processName});
   }

   public static DeployerException processNotResponding(String processName) {
      return new DeployerException(DeployerException.Error.PROCESS_NOT_RESPONDING, NO_ARGS, new String[]{processName});
   }

   public static DeployerException processTerminated(String pid) {
      return new DeployerException(DeployerException.Error.PROCESS_TERMINATED, NO_ARGS, new String[]{pid});
   }

   public static DeployerException entryNotFound(String fileName, String apkName) {
      return new DeployerException(DeployerException.Error.ENTRY_NOT_FOUND, NO_ARGS, new String[]{fileName, apkName});
   }

   public static DeployerException isolatedServiceNotSupported(List<String> serviceNames) {
      return new DeployerException(DeployerException.Error.ISOLATED_SERVICE_NOT_SUPPORTED, NO_ARGS, new String[]{String.join(", ", serviceNames)});
   }

   public static DeployerException entryUnzipFailed(Throwable exception) {
      return new DeployerException(DeployerException.Error.ENTRY_UNZIP_FAILED, NO_ARGS, new String[]{exception.getMessage()});
   }

   public static DeployerException changedSharedObject(String filePath) {
      return new DeployerException(DeployerException.Error.CANNOT_SWAP_STATIC_LIB, NO_ARGS, new String[]{filePath});
   }

   public static DeployerException changedManifest(String filePath) {
      return new DeployerException(DeployerException.Error.CANNOT_SWAP_MANIFEST, NO_ARGS, new String[]{filePath});
   }

   public static DeployerException changedResources(String filePath) {
      return new DeployerException(DeployerException.Error.CANNOT_SWAP_RESOURCE, NO_ARGS, new String[]{filePath});
   }

   public static DeployerException changedCrashlyticsBuildId(String filePath) {
      return new DeployerException(DeployerException.Error.CANNOT_SWAP_CRASHLYTICS_PROPERTY, NO_ARGS, new String[]{filePath});
   }

   public static DeployerException addedResources(String name, String type) {
      return new DeployerException(DeployerException.Error.CANNOT_ADD_RESOURCE, NO_ARGS, new String[]{name, type});
   }

   public static DeployerException removedResources(String name, String type) {
      return new DeployerException(DeployerException.Error.CANNOT_REMOVE_RESOURCE, NO_ARGS, new String[]{name, type});
   }

   public static DeployerException classNotFound(String className) {
      return new DeployerException(DeployerException.Error.CLASS_NOT_FOUND, new String[]{className}, new String[]{className});
   }

   public static DeployerException unsupportedVariableReinit(Deploy.AgentSwapResponse.Status status, String msg) {
      return new DeployerException(DeployerException.Error.UNSUPPORTED_REINIT, status, new String[]{msg}, NO_ARGS);
   }

   public static DeployerException unsupportedRClassReassignment(Deploy.AgentSwapResponse.Status status, String msg) {
      return new DeployerException(DeployerException.Error.UNSUPPORTED_R_REASSIGNMENT, status, NO_ARGS, new String[]{msg});
   }

   public static DeployerException jvmtiError(JvmtiErrorCode code, boolean androidRAndUp) {
      if (ERROR_CODE_TO_ERROR.containsKey(code)) {
         Error error = (Error)ERROR_CODE_TO_ERROR.get(code);
         return error == DeployerException.Error.CANNOT_AND_OR_REMOVE_FIELDS && androidRAndUp ? new DeployerException(DeployerException.Error.CANNOT_REMOVE_FIELDS) : new DeployerException((Error)ERROR_CODE_TO_ERROR.get(code));
      } else {
         return new DeployerException(DeployerException.Error.JVMTI_ERROR, code, new String[]{code.name()}, new String[0]);
      }
   }

   public static DeployerException dumpFailed(String reason) {
      return new DeployerException(DeployerException.Error.DUMP_FAILED, NO_ARGS, new String[]{reason});
   }

   public static DeployerException dumpMixedArch(String reason) {
      return new DeployerException(DeployerException.Error.DUMP_MIXED_ARCH, NO_ARGS, new String[]{reason});
   }

   public static DeployerException unsupportedArch() {
      return new DeployerException(DeployerException.Error.UNSUPPORTED_ARCH, NO_ARGS, NO_ARGS);
   }

   public static DeployerException parseFailed(String reason) {
      return new DeployerException(DeployerException.Error.PARSE_FAILED, NO_ARGS, new String[]{reason});
   }

   public static DeployerException preinstallFailed(String reason) {
      return new DeployerException(DeployerException.Error.PREINSTALL_FAILED, NO_ARGS, new String[]{reason});
   }

   public static DeployerException installFailed(Enum<?> code, String reason) {
      String suffix = code != InstallStatus.UNKNOWN_ERROR ? ": " + code.name() : ".";
      return new DeployerException(DeployerException.Error.INSTALL_FAILED, code, new String[]{suffix}, new String[]{reason});
   }

   public static DeployerException swapFailed(Deploy.SwapResponse.Status code) {
      String suffix = code != Status.UNKNOWN ? ": " + code.name() : ".";
      return new DeployerException(DeployerException.Error.SWAP_FAILED, code, new String[]{suffix}, new String[]{""});
   }

   public static DeployerException agentFailed(Deploy.AgentResponse.Status code) {
      String suffix = code != com.android.tools.deploy.proto.Deploy.AgentResponse.Status.UNKNOWN ? ": " + code.name() : ".";
      return new DeployerException(DeployerException.Error.AGENT_FAILED, code, new String[]{suffix}, new String[]{""});
   }

   public static DeployerException agentSwapFailed(Deploy.AgentSwapResponse.Status code) {
      String suffix = code != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNKNOWN ? ": " + code.name() : ".";
      return new DeployerException(DeployerException.Error.AGENT_SWAP_FAILED, code, new String[]{suffix}, new String[]{""});
   }

   public static DeployerException swapAfterLeNotSupported() {
      return new DeployerException(DeployerException.Error.SWAP_AFTER_LIVE_EDIT_NOT_SUPPORTED, NO_ARGS, NO_ARGS);
   }

   public static DeployerException appIdChanged(String before, String after) {
      return new DeployerException(DeployerException.Error.PREINSTALL_APPID_CHANGED, new String[]{before, after}, new String[]{""});
   }

   public static DeployerException swapMultiplePackages() {
      return new DeployerException(DeployerException.Error.SWAP_MULTIPLE_PACKAGES, NO_ARGS, NO_ARGS);
   }

   public static DeployerException installerIoException(IOException e) {
      return new DeployerException(DeployerException.Error.INSTALLER_IO_EXCEPTION, NO_ARGS, new String[]{e.getMessage()});
   }

   public static DeployerException overlayIdMismatch() {
      return new DeployerException(DeployerException.Error.APP_OVERLAY_IN_UNKNOWN_STATE, NO_ARGS, NO_ARGS);
   }

   public static DeployerException unknownJvmtiError(String type) {
      return new DeployerException(DeployerException.Error.UNKNOWN_JVMTI_ERROR, new String[]{type}, NO_ARGS);
   }

   public static DeployerException jdwpRedefineClassesException(Throwable t) {
      return new DeployerException(DeployerException.Error.JDWP_REDEFINE_CLASSES_EXCEPTION, NO_ARGS, new String[]{t.getMessage()});
   }

   public static DeployerException attachAgentNotFound() {
      return new DeployerException(DeployerException.Error.ATTACHAGENT_NOT_FOUND, NO_ARGS, NO_ARGS);
   }

   public static DeployerException abisFieldNotFound() {
      return new DeployerException(DeployerException.Error.ABIS_FIELD_NOT_FOUND, NO_ARGS, NO_ARGS);
   }

   public static DeployerException attachAgentException(Exception e) {
      return new DeployerException(DeployerException.Error.ATTACHAGENT_EXCEPTION, new String[]{e.getClass().getSimpleName()}, new String[]{e.getMessage()});
   }

   public static DeployerException noDebuggerSession(int port) {
      return new DeployerException(DeployerException.Error.NO_DEBUGGER_SESSION, new String[]{"" + port}, NO_ARGS);
   }

   public static DeployerException jdiInvalidState() {
      return new DeployerException(DeployerException.Error.JDI_INVAlID_STATE, NO_ARGS, NO_ARGS);
   }

   public static DeployerException interrupted(String reason) {
      return new DeployerException(DeployerException.Error.INTERRUPTED, NO_ARGS, new String[]{reason});
   }

   public static DeployerException jdbcNativeLibError(String nativeLibLoc) {
      return new DeployerException(DeployerException.Error.JDBC_NATIVE_LIB, NO_ARGS, new String[]{nativeLibLoc});
   }

   public static DeployerException changeNotSupportedByIWI(ChangeType type) {
      return new DeployerException(DeployerException.Error.UNSUPPORTED_IWI_CHANGE, type, NO_ARGS, NO_ARGS);
   }

   public static DeployerException deleteInstalledFileNotSupported() {
      return new DeployerException(DeployerException.Error.UNSUPPORTED_IWI_FILE_DELETE, NO_ARGS, NO_ARGS);
   }

   public static DeployerException runTestsNotSupported() {
      return new DeployerException(DeployerException.Error.IWI_RUN_TESTS_NOT_SUPPORTED, NO_ARGS, NO_ARGS);
   }

   public static DeployerException sdksNotSupported() {
      return new DeployerException(DeployerException.Error.IWI_SDK_RUNTIME_NOT_SUPPORTED, NO_ARGS, NO_ARGS);
   }

   public static DeployerException pmFlagsNotSupported() {
      return new DeployerException(DeployerException.Error.IWI_RUN_PM_FLAGS_NOT_SUPPORTED, NO_ARGS, NO_ARGS);
   }

   public static DeployerException operationNotSupported(String reason) {
      return new DeployerException(DeployerException.Error.OPERATION_NOT_SUPPORTED, NO_ARGS, new String[]{reason});
   }

   public static DeployerException runtimeException(Exception e) {
      DeployerException dx = new DeployerException(DeployerException.Error.RUN_TIME_EXCEPTION, NO_ARGS, new String[]{e.toString()});
      dx.initCause(e);
      return dx;
   }

   public static DeployerException componentActivationException(String reason) {
      return new DeployerException(DeployerException.Error.COMPONENT_ACTIVATION_EXCEPTION, NO_ARGS, new String[]{reason});
   }

   public static DeployerException apiNotSupported() {
      return new DeployerException(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26, NO_ARGS, NO_ARGS);
   }

   public static DeployerException cannotTerminateApplication() {
      return new DeployerException(DeployerException.Error.CANNOT_TERMINATE_APPLICATION, NO_ARGS, NO_ARGS);
   }

   static {
      ERROR_CODE_TO_ERROR = ImmutableMap.<JvmtiErrorCode, Error>builder().put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED, DeployerException.Error.CANNOT_ADD_METHOD).put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED, DeployerException.Error.CANNOT_AND_OR_REMOVE_FIELDS).put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED, DeployerException.Error.CANNOT_CHANGE_INHERITANCE).put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED, DeployerException.Error.CANNOT_DELETE_METHOD).put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_CLASS_MODIFIERS_CHANGED, DeployerException.Error.CANNOT_CHANGE_CLASS_MODIFIERS).put(JvmtiErrorCode.JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_MODIFIERS_CHANGED, DeployerException.Error.CANNOT_CHANGE_METHOD_MODIFIERS).put(JvmtiErrorCode.JVMTI_ERROR_FAILS_VERIFICATION, DeployerException.Error.VERIFICATION_ERROR).build();
   }

   public static enum ResolutionAction {
      NONE,
      RUN_APP,
      APPLY_CHANGES,
      RETRY;
   }

   public static enum Error {
      NO_ERROR("", "", "", DeployerException.ResolutionAction.NONE),
      CANNOT_SWAP_BEFORE_API_26("Apply Changes is only supported on API 26 or newer", "", "", DeployerException.ResolutionAction.NONE),
      DUMP_UNKNOWN_PACKAGE("Package not found on device.", "The package '%s' was not found on the device. Is the app installed?", "Install and run app", DeployerException.ResolutionAction.RUN_APP),
      DUMP_ERROR("Packages [%s] dump failed [%s].", "Unable to retrieve packages [%s] info. Error code [%s]", "", DeployerException.ResolutionAction.NONE),
      DUMP_UNKNOWN_PROCESS("No running app process found.", "", "Run app", DeployerException.ResolutionAction.RUN_APP),
      REMOTE_APK_NOT_FOUND_IN_DB("Android Studio was unable to recognize the APK(s) currently installed on the device.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      DIFFERENT_NUMBER_OF_APKS("A different number of APKs were found on the device than on the host.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      DIFFERENT_APK_NAMES("The naming scheme of APKs on the device differ from the APKs on the host.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      PROCESS_CRASHING("Apply Changes could not complete because an application process is crashed.", "Process '%s' has crashed.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      PROCESS_NOT_RESPONDING("Apply Changes could not complete because an application process is not responding.", "Process '%s' is not responding.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      PROCESS_TERMINATED("Apply Changes could not complete because one of the application's processes terminated unexpectedly.", "PID #%s terminated before the operation could complete.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      ENTRY_NOT_FOUND("Apply Changes could not find an expected file in the APK.", "'%s' was not found in APK '%s'", "Retry", DeployerException.ResolutionAction.APPLY_CHANGES),
      ENTRY_UNZIP_FAILED("Apply Changes failed to extract a file from the APK.", "Exception occurred: %s", "Retry", DeployerException.ResolutionAction.APPLY_CHANGES),
      ISOLATED_SERVICE_NOT_SUPPORTED("Applications with services in isolated processes cannot be swapped with Apply Changes.", "The following service(s) are set to run in an isolated process: %s", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CLASS_NOT_FOUND("Class not found: %s", "Class '%s' was not found during swap.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_SWAP_STATIC_LIB("Modifications to shared libraries require an app restart.", "File '%s' was modified.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_SWAP_MANIFEST("Modifications to AndroidManifest.xml require an app restart.", "Manifest '%s' was modified.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_SWAP_RESOURCE("Modifying resources requires an activity restart.", "Resource '%s' was modified.", "Apply changes and restart activity", DeployerException.ResolutionAction.APPLY_CHANGES),
      CANNOT_SWAP_CRASHLYTICS_PROPERTY("Crashlytics modified your build ID, which requires an activity restart. <a href=\"https://d.android.com/r/studio-ui/apply-changes-crashlytics-buildid\">See here</a>", "Resource '%s' was modified.", "Apply changes and restart activity", DeployerException.ResolutionAction.APPLY_CHANGES),
      CANNOT_ADD_RESOURCE("Adding or renaming a resource requires an application restart.", "Resource '%s' (%s) was added.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_REMOVE_RESOURCE("Removing a resource requires an application restart.", "Resource '%s' (%s) was removed.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_ADD_METHOD("Adding a new method requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_AND_OR_REMOVE_FIELDS("Adding or removing a field requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_REMOVE_FIELDS("Removing a field requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_CHANGE_INHERITANCE("Changes to class inheritance require an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_DELETE_METHOD("Removing a method requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_CHANGE_CLASS_MODIFIERS("Changing class modifiers requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      CANNOT_CHANGE_METHOD_MODIFIERS("Changing method modifiers requires an app restart.", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      VERIFICATION_ERROR("New code fails verification", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      JVMTI_ERROR("JVMTI error: %s", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      DUMP_FAILED("We were unable to deploy your changes.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      DUMP_MIXED_ARCH("Application with process in both 32 and 64 bit mode.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      PREINSTALL_FAILED("The application could not be installed.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      INSTALL_FAILED("The application could not be installed%s", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      SWAP_FAILED("We were unable to deploy your changes%s", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      PREINSTALL_APPID_CHANGED("Cannot preinstall: apks have different package name (%s and %s)", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      AGENT_FAILED("We were unable to deploy your changes%s", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      AGENT_SWAP_FAILED("We were unable to deploy your changes%s", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      PARSE_FAILED("We were unable to deploy your changes.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      SWAP_MULTIPLE_PACKAGES("Cannot swap multiple packages", "", "Retry", DeployerException.ResolutionAction.RETRY),
      INSTALLER_IO_EXCEPTION("IOException occurred within Installer", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      APP_OVERLAY_IN_UNKNOWN_STATE("The target app on the device is in a state unknown to Studio", "Android Studio is unable to recognize the version of the application currently installed on the target device.", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      UNSUPPORTED_ARCH("The target device's architecture is not supported", "", "", DeployerException.ResolutionAction.NONE),
      UNKNOWN_JVMTI_ERROR("Invalid error code %s", "", "Retry", DeployerException.ResolutionAction.RETRY),
      JDWP_REDEFINE_CLASSES_EXCEPTION("Exception during VM RedfineClasses", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      ABIS_FIELD_NOT_FOUND("android.os.Build does not contain the expected ABI fields", "", "Retry", DeployerException.ResolutionAction.RETRY),
      ATTACHAGENT_NOT_FOUND("dalvik.system.VMDebug does not contain proper attachAgent method", "", "Retry", DeployerException.ResolutionAction.RETRY),
      ATTACHAGENT_EXCEPTION("Debugger attachAgent invocation failed due to %s", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      NO_DEBUGGER_SESSION("No Debugger session found for port %s", "", "Retry", DeployerException.ResolutionAction.RETRY),
      JDI_INVAlID_STATE("Invalid Redefinition State.", "", "", DeployerException.ResolutionAction.RETRY),
      INTERRUPTED("Deployment was interrupted.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      UNSUPPORTED_REINIT("Added variable(s) does not support value initialization: %s", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      UNSUPPORTED_R_REASSIGNMENT("Existing ID values of R.class has been changed.", "%s", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      JDBC_NATIVE_LIB("Unable to establish JDBC connection to DEX file database", "Verify Android Studio is able to extract executable file to %s", "Retry", DeployerException.ResolutionAction.RETRY),
      UNSUPPORTED_IWI_CHANGE("A change was not supported by the IWI pipeline. Deployment should fall back to regular installation", "", "", DeployerException.ResolutionAction.NONE),
      UNSUPPORTED_IWI_FILE_DELETE("Deleting files from installed APKs is not supported by the IWI pipeline. Deployment should fall back to regular installation", "", "", DeployerException.ResolutionAction.NONE),
      IWI_RUN_TESTS_NOT_SUPPORTED("Running instrumented tests is not supported by the IWI pipeline. Deployment should fall back to regular installation", "", "", DeployerException.ResolutionAction.NONE),
      IWI_SDK_RUNTIME_NOT_SUPPORTED("Deploying SDK runtime apps is not supported by the IWI pipeline. Deployment should fall back to regular installation", "", "", DeployerException.ResolutionAction.NONE),
      IWI_RUN_PM_FLAGS_NOT_SUPPORTED("Specifying package manager flags is not supported by the IWI pipeline. Deployment should fall back to regular installation", "", "", DeployerException.ResolutionAction.NONE),
      SWAP_AFTER_LIVE_EDIT_NOT_SUPPORTED("Apply Changes/Apply Code Changes are not compatible with Live Edit and require the running app to be restarted", "", "Reinstall and restart app", DeployerException.ResolutionAction.RUN_APP),
      OPERATION_NOT_SUPPORTED("Operation not supported.", "%s", "", DeployerException.ResolutionAction.NONE),
      RUN_TIME_EXCEPTION("Runtime Exception.", "%s", "Retry", DeployerException.ResolutionAction.RETRY),
      COMPONENT_ACTIVATION_EXCEPTION("Component activation exception", "%s", "", DeployerException.ResolutionAction.NONE),
      CANNOT_TERMINATE_APPLICATION("Cannot terminate running application", "", "Retry", DeployerException.ResolutionAction.RUN_APP);

      private final String message;
      private final String details;
      private final String callToAction;
      private final ResolutionAction action;

      private Error(String message, String details, String callToAction, ResolutionAction action) {
         this.message = message;
         this.details = details;
         this.callToAction = callToAction;
         this.action = action;
      }

      public String getCallToAction() {
         return this.callToAction;
      }

      public ResolutionAction getResolution() {
         return this.action;
      }
   }
}
