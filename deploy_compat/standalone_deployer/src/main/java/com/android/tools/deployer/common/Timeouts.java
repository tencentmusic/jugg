package com.android.tools.deployer.common;

import java.util.concurrent.TimeUnit;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class Timeouts {
   private static final long T_5_SECONDS;
   private static final long T_5_MINUTES;
   static final long CMD_DUMP_MS;
   static final long CMD_SWAP_MS;
   static final long CMD_OSWAP_MS;
   static final long CMD_OINSTALL_MS;
   static final long CMD_ROOT_PUSH_INSTALL;
   static final long CMD_VERIFY_OID_MS;
   static final long CMD_DELTA_PREINSTALL_MS;
   static final long CMD_DELTA_INSTALL_MS;
   static final long CMD_UPDATE_LL;
   static final long CMD_LIVE_EDIT;
   static final long CMD_COMPOSE_STATUS;
   static final long CMD_INSTALL_COROUTINE;
   static final long CMD_NETTEST;
   public static final long CMD_TIMEOUT;
   static final long CMD_RESTART_ACTIVITY_MS;
   static final long CMD_FIND_DEX_MS;
   public static final long SHELL_MKDIR;
   public static final long SHELL_RMFR;
   public static final long SHELL_CHMOD;
   public static final long SHELL_CHOWN;
   public static final long SHELL_AM_STOP;
   public static final long SHELL_ABORT_INSTALL_MS;
   public static final long SHELL_BASELINE_PROFILE_STATUS;

   static {
      T_5_SECONDS = TimeUnit.SECONDS.toMillis(5L);
      T_5_MINUTES = TimeUnit.MINUTES.toMillis(5L);
      CMD_DUMP_MS = T_5_SECONDS;
      CMD_SWAP_MS = T_5_MINUTES;
      CMD_OSWAP_MS = T_5_MINUTES;
      CMD_OINSTALL_MS = T_5_MINUTES;
      CMD_ROOT_PUSH_INSTALL = T_5_MINUTES;
      CMD_VERIFY_OID_MS = T_5_SECONDS;
      CMD_DELTA_PREINSTALL_MS = T_5_MINUTES;
      CMD_DELTA_INSTALL_MS = T_5_MINUTES;
      CMD_UPDATE_LL = T_5_SECONDS;
      CMD_LIVE_EDIT = T_5_SECONDS;
      CMD_COMPOSE_STATUS = T_5_SECONDS;
      CMD_INSTALL_COROUTINE = T_5_SECONDS;
      CMD_NETTEST = T_5_MINUTES;
      CMD_TIMEOUT = T_5_SECONDS;
      CMD_RESTART_ACTIVITY_MS = T_5_SECONDS;
      CMD_FIND_DEX_MS = T_5_SECONDS;
      SHELL_MKDIR = T_5_SECONDS;
      SHELL_RMFR = T_5_SECONDS;
      SHELL_CHMOD = T_5_SECONDS;
      SHELL_CHOWN = T_5_SECONDS;
      SHELL_AM_STOP = T_5_SECONDS;
      SHELL_ABORT_INSTALL_MS = T_5_SECONDS;
      SHELL_BASELINE_PROFILE_STATUS = T_5_SECONDS;
   }
}
