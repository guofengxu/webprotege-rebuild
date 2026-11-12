import type { FeatureRoutes } from "@/app/featureRoutes";
import AdminUsersPage from "./AdminUsersPage.vue";

export const featureRoutes = {
  layout: [
    {
      path: "/admin/users",
      component: AdminUsersPage,
      meta: { requiresSystemAdmin: true },
    },
  ],
} satisfies FeatureRoutes;
