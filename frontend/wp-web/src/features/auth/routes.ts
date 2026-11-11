import type { FeatureRoutes } from "@/app/featureRoutes";
import LoginPage from "./LoginPage.vue";

export const featureRoutes = {
  root: [{ path: "/login", component: LoginPage }],
} satisfies FeatureRoutes;
