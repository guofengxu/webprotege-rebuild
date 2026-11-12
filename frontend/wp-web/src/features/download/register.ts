import type { FeatureRegistration } from "@/features/workspace/registry";
import DownloadMenu from "./DownloadMenu.vue";

export const registration: FeatureRegistration = {
  workspaceToolbar: [DownloadMenu],
  revisionActions: [DownloadMenu],
};
