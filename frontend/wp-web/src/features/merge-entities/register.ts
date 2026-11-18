import type { FeatureRegistration } from "@/features/workspace/registry";
import MergeEntitiesButton from "./MergeEntitiesButton.vue";

export const registration: FeatureRegistration = {
  hierarchyToolbar: [MergeEntitiesButton],
};
