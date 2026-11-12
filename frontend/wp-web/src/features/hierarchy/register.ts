import type { FeatureRegistration } from "@/features/workspace/registry";
import ClassHierarchyPanel from "./ClassHierarchyPanel.vue";
import PropertyHierarchyPanel from "./PropertyHierarchyPanel.vue";

export const registration: FeatureRegistration = {
  hierarchyPanels: {
    class: ClassHierarchyPanel,
    "object-property": PropertyHierarchyPanel,
    "data-property": PropertyHierarchyPanel,
    "annotation-property": PropertyHierarchyPanel,
  },
};
