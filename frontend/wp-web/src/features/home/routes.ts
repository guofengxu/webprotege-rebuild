import type { FeatureRoutes } from "@/app/featureRoutes";
import HomePage from "./HomePage.vue";

export const featureRoutes = {
  root: [{ path: "/", component: HomePage }],
} satisfies FeatureRoutes;
