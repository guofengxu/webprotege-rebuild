import type { FeatureRoutes } from "@/app/featureRoutes";
import ProjectSettingsPage from "./ProjectSettingsPage.vue";

export const featureRoutes = {
  project: [
    {
      path: "settings",
      name: "settings",
      component: ProjectSettingsPage,
    },
  ],
} satisfies FeatureRoutes;
