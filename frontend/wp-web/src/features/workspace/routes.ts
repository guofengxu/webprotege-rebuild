import type { FeatureRoutes } from "@/app/featureRoutes";
import ProjectShell from "./ProjectShell.vue";
import WorkspacePage from "./WorkspacePage.vue";

export const WORKSPACE_KIND_PATH =
  ":kind(class|object-property|data-property|annotation-property|individual)";

export const featureRoutes = {
  layout: [
    {
      path: "/projects/:projectId",
      component: ProjectShell,
      children: [
        { path: "", redirect: "class" },
        {
          path: WORKSPACE_KIND_PATH,
          name: "workspace",
          component: WorkspacePage,
        },
      ],
    },
  ],
} satisfies FeatureRoutes;
