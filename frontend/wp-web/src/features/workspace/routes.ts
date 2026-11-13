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
        {
          path: "",
          // Vue Router 5 resolves a relative string redirect from the app root
          // ("/class"), not under /projects/:projectId. Use the named workspace
          // route so projectId is kept.
          redirect: (to) => ({
            name: "workspace",
            params: { ...to.params, kind: "class" },
          }),
        },
        {
          path: WORKSPACE_KIND_PATH,
          name: "workspace",
          component: WorkspacePage,
        },
      ],
    },
  ],
} satisfies FeatureRoutes;
