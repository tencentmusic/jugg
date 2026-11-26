package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.common.DeployMetric;
import com.android.tools.tracer.Trace;
import java.util.ArrayList;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class MetricsRecorder {
   private final ArrayList<DeployMetric> deployMetrics = new ArrayList();
   private final ArrayList<Deploy.AgentExceptionLog> agentExceptionLogs = new ArrayList();

   public void start(String name) {
      this.deployMetrics.add(new DeployMetric(name));
      Trace.begin(name);
   }

   public void finish() {
      this.currentMetric().finish("Success");
      Trace.end();
   }

   public void finish(Enum<?> status) {
      this.currentMetric().finish(status.name());
      Trace.end();
   }

   public List<DeployMetric> getDeployMetrics() {
      return this.deployMetrics;
   }

   public List<Deploy.AgentExceptionLog> getAgentFailures() {
      return this.agentExceptionLogs;
   }

   void add(DeployMetric metric) {
      this.deployMetrics.add(metric);
   }

   void add(List<Deploy.AgentExceptionLog> logs) {
      this.agentExceptionLogs.addAll(logs);
   }

   private DeployMetric currentMetric() {
      return (DeployMetric)this.deployMetrics.get(this.deployMetrics.size() - 1);
   }
}
