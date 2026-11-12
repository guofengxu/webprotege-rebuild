import type { FeatureRegistration } from "@/features/workspace/registry";
import EntityToolbar from "./EntityToolbar.vue";

export const registration: FeatureRegistration = {
  hierarchyToolbar: [EntityToolbar],
};
