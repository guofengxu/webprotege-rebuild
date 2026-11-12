import type { FeatureRoutes } from "@/app/featureRoutes";
import AccountSettingsPage from "./AccountSettingsPage.vue";

export const featureRoutes = {
  layout: [{ path: "/me", component: AccountSettingsPage }],
} satisfies FeatureRoutes;
