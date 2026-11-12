import type { FeatureRoutes } from "@/app/featureRoutes";
import HomePage from "./HomePage.vue";

export const featureRoutes = {
  layout: [{ path: "", component: HomePage }],
} satisfies FeatureRoutes;
