package com.android.tools.deployer.common;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public enum InstallStatus {
   OK,
   INSTALL_FAILED_ABORTED,
   INSTALL_FAILED_ALREADY_EXISTS,
   INSTALL_FAILED_BAD_DEX_METADATA,
   INSTALL_FAILED_BAD_SIGNATURE,
   INSTALL_FAILED_CONFLICTING_PROVIDER,
   INSTALL_FAILED_CONTAINER_ERROR,
   INSTALL_FAILED_CPU_ABI_INCOMPATIBLE,
   INSTALL_FAILED_DEXOPT,
   INSTALL_FAILED_DUPLICATE_PACKAGE,
   INSTALL_FAILED_DUPLICATE_PERMISSION,
   INSTALL_FAILED_INSTANT_APP_INVALID,
   INSTALL_FAILED_INSUFFICIENT_STORAGE,
   INSTALL_FAILED_INTERNAL_ERROR,
   INSTALL_FAILED_INVALID_APK,
   INSTALL_FAILED_INVALID_INSTALL_LOCATION,
   INSTALL_FAILED_INVALID_URI,
   INSTALL_FAILED_MEDIA_UNAVAILABLE,
   INSTALL_FAILED_MISSING_FEATURE,
   INSTALL_FAILED_MISSING_SHARED_LIBRARY,
   INSTALL_FAILED_MISSING_SPLIT,
   INSTALL_FAILED_DEPRECATED_SDK_VERSION,
   INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY,
   INSTALL_FAILED_NEWER_SDK,
   INSTALL_FAILED_NO_MATCHING_ABIS,
   NO_NATIVE_LIBRARIES,
   INSTALL_FAILED_NO_SHARED_USER,
   INSTALL_FAILED_OLDER_SDK,
   INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS,
   INSTALL_FAILED_PACKAGE_CHANGED,
   INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
   INSTALL_FAILED_REPLACE_COULDNT_DELETE,
   INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE,
   INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
   INSTALL_FAILED_TEST_ONLY,
   INSTALL_FAILED_UID_CHANGED,
   INSTALL_FAILED_UPDATE_INCOMPATIBLE,
   INSTALL_FAILED_USER_RESTRICTED,
   INSTALL_FAILED_VERIFICATION_FAILURE,
   INSTALL_FAILED_VERIFICATION_TIMEOUT,
   INSTALL_FAILED_VERSION_DOWNGRADE,
   INSTALL_FAILED_WRONG_INSTALLED_VERSION,
   INSTALL_PARSE_FAILED_BAD_MANIFEST,
   INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
   INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID,
   INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING,
   INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
   INSTALL_PARSE_FAILED_MANIFEST_EMPTY,
   INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
   INSTALL_PARSE_FAILED_NO_CERTIFICATES,
   INSTALL_PARSE_FAILED_NOT_APK,
   INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
   INSTALL_FAILED_PROCESS_NOT_DEFINED,
   INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED,
   INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED,
   INSTALL_PARSE_FAILED_SKIPPED,
   INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP,
   INSTALL_FAILED_BAD_PERMISSION_GROUP,
   INSTALL_ACTIVATION_FAILED,
   INSTALL_FAILED_PRE_APPROVAL_NOT_AVAILABLE,
   INSTALL_FAILED_SHARED_LIBRARY_BAD_CERTIFICATE_DIGEST,
   INSTALL_BASELINE_PROFILE_FAILED,
   DEVICE_NOT_RESPONDING,
   INCONSISTENT_CERTIFICATES,
   NO_CERTIFICATE,
   DEVICE_NOT_FOUND,
   SHELL_UNRESPONSIVE,
   MULTI_APKS_NO_SUPPORTED_BELOW21,
   UNKNOWN_ERROR,
   SKIPPED_INSTALL;

   public static InstallStatus numericErrorCodeToStatus(int code) {
      switch (code) {
         case -130:
            return INSTALL_FAILED_SHARED_LIBRARY_BAD_CERTIFICATE_DIGEST;
         case -129:
            return INSTALL_FAILED_PRE_APPROVAL_NOT_AVAILABLE;
         case -128:
            return INSTALL_ACTIVATION_FAILED;
         case -127:
            return INSTALL_FAILED_BAD_PERMISSION_GROUP;
         case -126:
            return INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP;
         case -125:
            return INSTALL_PARSE_FAILED_SKIPPED;
         case -124:
            return INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED;
         case -123:
            return INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED;
         case -122:
            return INSTALL_FAILED_PROCESS_NOT_DEFINED;
         case -121:
            return INSTALL_FAILED_WRONG_INSTALLED_VERSION;
         case -120:
            return INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY;
         case -119:
            return INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS;
         case -118:
            return INSTALL_FAILED_BAD_SIGNATURE;
         case -117:
            return INSTALL_FAILED_BAD_DEX_METADATA;
         case -116:
            return INSTALL_FAILED_INSTANT_APP_INVALID;
         case -115:
            return INSTALL_FAILED_ABORTED;
         case -114:
            return NO_NATIVE_LIBRARIES;
         case -113:
            return INSTALL_FAILED_NO_MATCHING_ABIS;
         case -112:
            return INSTALL_FAILED_DUPLICATE_PERMISSION;
         case -111:
            return INSTALL_FAILED_USER_RESTRICTED;
         case -110:
            return INSTALL_FAILED_INTERNAL_ERROR;
         case -109:
            return INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
         case -108:
            return INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
         case -107:
            return INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID;
         case -106:
            return INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
         case -105:
            return INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING;
         case -104:
            return INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
         case -103:
            return INSTALL_PARSE_FAILED_NO_CERTIFICATES;
         case -102:
            return INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
         case -101:
            return INSTALL_PARSE_FAILED_BAD_MANIFEST;
         case -100:
            return INSTALL_PARSE_FAILED_NOT_APK;
         case -99:
         case -98:
         case -97:
         case -96:
         case -95:
         case -94:
         case -93:
         case -92:
         case -91:
         case -90:
         case -89:
         case -88:
         case -87:
         case -86:
         case -85:
         case -84:
         case -83:
         case -82:
         case -81:
         case -80:
         case -79:
         case -78:
         case -77:
         case -76:
         case -75:
         case -74:
         case -73:
         case -72:
         case -71:
         case -70:
         case -69:
         case -68:
         case -67:
         case -66:
         case -65:
         case -64:
         case -63:
         case -62:
         case -61:
         case -60:
         case -59:
         case -58:
         case -57:
         case -56:
         case -55:
         case -54:
         case -53:
         case -52:
         case -51:
         case -50:
         case -49:
         case -48:
         case -47:
         case -46:
         case -45:
         case -44:
         case -43:
         case -42:
         case -41:
         case -40:
         case -39:
         case -38:
         case -37:
         case -36:
         case -35:
         case -34:
         case -33:
         case -32:
         case -31:
         case -30:
         default:
            return UNKNOWN_ERROR;
         case -29:
            return INSTALL_FAILED_DEPRECATED_SDK_VERSION;
         case -28:
            return INSTALL_FAILED_MISSING_SPLIT;
         case -27:
            return INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE;
         case -26:
            return INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE;
         case -25:
            return INSTALL_FAILED_VERSION_DOWNGRADE;
         case -24:
            return INSTALL_FAILED_UID_CHANGED;
         case -23:
            return INSTALL_FAILED_PACKAGE_CHANGED;
         case -22:
            return INSTALL_FAILED_VERIFICATION_FAILURE;
         case -21:
            return INSTALL_FAILED_VERIFICATION_TIMEOUT;
         case -20:
            return INSTALL_FAILED_MEDIA_UNAVAILABLE;
         case -19:
            return INSTALL_FAILED_INVALID_INSTALL_LOCATION;
         case -18:
            return INSTALL_FAILED_CONTAINER_ERROR;
         case -17:
            return INSTALL_FAILED_MISSING_FEATURE;
         case -16:
            return INSTALL_FAILED_CPU_ABI_INCOMPATIBLE;
         case -15:
            return INSTALL_FAILED_TEST_ONLY;
         case -14:
            return INSTALL_FAILED_NEWER_SDK;
         case -13:
            return INSTALL_FAILED_CONFLICTING_PROVIDER;
         case -12:
            return INSTALL_FAILED_OLDER_SDK;
         case -11:
            return INSTALL_FAILED_DEXOPT;
         case -10:
            return INSTALL_FAILED_REPLACE_COULDNT_DELETE;
         case -9:
            return INSTALL_FAILED_MISSING_SHARED_LIBRARY;
         case -8:
            return INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
         case -7:
            return INSTALL_FAILED_UPDATE_INCOMPATIBLE;
         case -6:
            return INSTALL_FAILED_NO_SHARED_USER;
         case -5:
            return INSTALL_FAILED_DUPLICATE_PACKAGE;
         case -4:
            return INSTALL_FAILED_INSUFFICIENT_STORAGE;
         case -3:
            return INSTALL_FAILED_INVALID_URI;
         case -2:
            return INSTALL_FAILED_INVALID_APK;
         case -1:
            return INSTALL_FAILED_ALREADY_EXISTS;
      }
   }
}
