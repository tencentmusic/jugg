package com.android.tools.deployer.common;

import java.util.Collection;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class DeployMetric {
   public static final long UNFINISHED = -1L;
   private final String name;
   private String status;
   private final long threadId;
   private final long startNs;
   private long endNs;

   public DeployMetric(String name) {
      this(name, System.nanoTime());
   }

   public DeployMetric(String name, long startNs) {
      this(name, startNs, -1L);
   }

   public DeployMetric(String name, long startNs, long endNs) {
      this(name, startNs, endNs, Thread.currentThread().getId());
   }

   private DeployMetric(String name, long startNs, long endNs, long threadId) {
      this.status = null;
      this.name = name;
      this.threadId = threadId;
      this.startNs = startNs;
      this.endNs = endNs;
   }

   public void finish(String status, Collection<DeployMetric> metrics) {
      this.finish(status);
      metrics.add(this);
   }

   public void finish(String status) {
      assert this.endNs == -1L;

      this.status = status;
      this.endNs = System.nanoTime();
   }

   public String getName() {
      return this.name;
   }

   public String getStatus() {
      return this.status;
   }

   public boolean hasStatus() {
      return this.status != null;
   }

   public long getThreadId() {
      return this.threadId;
   }

   public long getStartTimeNs() {
      return this.startNs;
   }

   public long getEndTimeNs() {
      assert this.endNs != -1L;

      return this.endNs;
   }
}
