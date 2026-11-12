import type { FeatureRoutes } from "@/app/featureRoutes";
import RevisionHistoryPage from "./RevisionHistoryPage.vue";

export const featureRoutes = {
  project: [
    {
      path: "history",
      name: "history",
      component: RevisionHistoryPage,
    },
  ],
} satisfies FeatureRoutes;
