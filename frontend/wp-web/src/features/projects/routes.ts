import type { FeatureRoutes } from "@/app/featureRoutes";
import ProjectListPage from "./ProjectListPage.vue";

export const featureRoutes = {
  layout: [
    { path: "", redirect: "/projects" },
    { path: "/projects", component: ProjectListPage },
  ],
} satisfies FeatureRoutes;
