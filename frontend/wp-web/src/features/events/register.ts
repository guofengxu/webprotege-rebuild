import type { FeatureRegistration } from "@/features/workspace/registry";
import ConnectionStatus from "./ConnectionStatus.vue";

export const registration: FeatureRegistration = {
  workspaceToolbar: [ConnectionStatus],
};
