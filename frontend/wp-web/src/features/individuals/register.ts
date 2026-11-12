import type { FeatureRegistration } from "@/features/workspace/registry";
import IndividualsPanel from "./IndividualsPanel.vue";

export const registration: FeatureRegistration = {
  hierarchyPanels: {
    individual: IndividualsPanel,
  },
};
