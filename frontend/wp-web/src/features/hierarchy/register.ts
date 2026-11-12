import type { FeatureRegistration } from "@/features/workspace/registry";
import ClassHierarchyPanel from "./ClassHierarchyPanel.vue";

export const registration: FeatureRegistration = {
  hierarchyPanels: {
    class: ClassHierarchyPanel,
  },
};
