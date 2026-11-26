package com.android.tools.deployer;

import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deploy.proto.Deploy.JvmtiError.Details.Type;
import com.android.tools.deploy.proto.Deploy.SwapResponse.Status;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.JvmtiErrorCode;
import com.google.common.base.Enums;
import com.google.common.base.Optional;
import java.util.List;

/** Quail deployer compatibility type rebuilt for the standalone Java 11 runtime. */
public class InstallerResponseHandler {
   private final boolean canAddFields;

   InstallerResponseHandler(RedefinitionCapability capability) {
      this.canAddFields = capability == InstallerResponseHandler.RedefinitionCapability.ALLOW_ADD_FIELD;
   }

   public SuccessStatus handle(Deploy.SwapResponse response) throws DeployerException {
      if (response.getStatus() == Status.OK) {
         return InstallerResponseHandler.SuccessStatus.OK;
      } else if (response.getStatus() == Status.SWAP_FAILED_BUT_OVERLAY_UPDATED) {
         return InstallerResponseHandler.SuccessStatus.SWAP_FAILED_BUT_APP_UPDATED;
      } else if (response.getStatus() == Status.PROCESS_CRASHING) {
         throw DeployerException.processCrashing(response.getExtra());
      } else if (response.getStatus() == Status.PROCESS_NOT_RESPONDING) {
         throw DeployerException.processNotResponding(response.getExtra());
      } else if (response.getStatus() == Status.PROCESS_TERMINATED) {
         throw DeployerException.processTerminated(response.getExtra());
      } else if (response.getStatus() != Status.AGENT_ERROR) {
         throw DeployerException.swapFailed(response.getStatus());
      } else {
         return this.handleAgentFailures(response.getFailedAgentsList());
      }
   }

   private SuccessStatus handleAgentFailures(List<Deploy.AgentResponse> failedAgents) throws DeployerException {
      if (failedAgents.isEmpty()) {
         return InstallerResponseHandler.SuccessStatus.OK;
      } else {
         Deploy.AgentResponse failedAgent = (Deploy.AgentResponse)failedAgents.get(0);
         if (failedAgent.getStatus() == com.android.tools.deploy.proto.Deploy.AgentResponse.Status.SWAP_FAILURE) {
            this.handleAgentSwapFailures(failedAgent.getSwapResponse());
         }

         throw DeployerException.agentFailed(failedAgent.getStatus());
      }
   }

   private SuccessStatus handleAgentSwapFailures(Deploy.AgentSwapResponse failedAgent) throws DeployerException {
      if (failedAgent.getStatus() == com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.CLASS_NOT_FOUND) {
         throw DeployerException.classNotFound(failedAgent.getClassName());
      } else if (failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_STATIC_PRIMITIVE && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_STATIC_PRIMITIVE_NOT_CONSTANT && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_STATIC_OBJECT && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_STATIC_ARRAY && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_NON_STATIC_PRIMITIVE && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_NON_STATIC_OBJECT && failedAgent.getStatus() != com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_NON_STATIC_ARRAY) {
         if (failedAgent.getStatus() == com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.UNSUPPORTED_REINIT_R_CLASS_VALUE_MODIFIED) {
            throw DeployerException.unsupportedRClassReassignment(failedAgent.getStatus(), failedAgent.getErrorMsg());
         } else {
            if (failedAgent.getStatus() == com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.JVMTI_ERROR) {
               this.handleJvmtiError(failedAgent.getJvmtiError());
            }

            if (failedAgent.getStatus() == com.android.tools.deploy.proto.Deploy.AgentSwapResponse.Status.LIVE_EDIT_PRIMED_CLASSES) {
               throw DeployerException.swapAfterLeNotSupported();
            } else {
               throw DeployerException.agentSwapFailed(failedAgent.getStatus());
            }
         }
      } else {
         throw DeployerException.unsupportedVariableReinit(failedAgent.getStatus(), failedAgent.getErrorMsg());
      }
   }

   private void handleJvmtiError(Deploy.JvmtiError jvmtiError) throws DeployerException {
      if (jvmtiError.getDetailsCount() == 0) {
         Optional<JvmtiErrorCode> errorCode = Enums.getIfPresent(JvmtiErrorCode.class, jvmtiError.getErrorCode());
         throw DeployerException.jvmtiError((JvmtiErrorCode)errorCode.or(JvmtiErrorCode.UNKNOWN_JVMTI_ERROR), this.canAddFields);
      } else {
         Deploy.JvmtiError.Details details = (Deploy.JvmtiError.Details)jvmtiError.getDetailsList().get(0);
         String parentClass = details.getClassName();
         String resType = parentClass.substring(parentClass.lastIndexOf(36) + 1);
         if (details.getType() == Type.FIELD_ADDED) {
            throw DeployerException.addedResources(details.getName(), resType);
         } else if (details.getType() == Type.FIELD_REMOVED) {
            throw DeployerException.removedResources(details.getName(), resType);
         } else {
            throw DeployerException.unknownJvmtiError(details.getType().name());
         }
      }
   }

   static enum SuccessStatus {
      OK,
      SWAP_FAILED_BUT_APP_UPDATED;
   }

   static enum RedefinitionCapability {
      MOFIFY_CODE_ONLY,
      ALLOW_ADD_FIELD;
   }
}
