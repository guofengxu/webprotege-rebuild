import type { FeatureRegistration } from "@/features/workspace/registry";
import EntityHistoryTab from "./EntityHistoryTab.vue";

export const registration: FeatureRegistration = {
  detailTabs: [
    {
      key: "history",
      labelKey: "history.entityTab",
      component: EntityHistoryTab,
    },
  ],
};
