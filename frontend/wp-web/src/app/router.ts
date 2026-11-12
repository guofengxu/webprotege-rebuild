import {
  createRouter,
  createWebHistory,
  type Router,
  type RouteRecordRaw,
} from "vue-router";
import type { FeatureRoutes } from "./featureRoutes";
import AppLayout from "./layout/AppLayout.vue";

type FeatureRouteModule = {
  readonly featureRoutes: FeatureRoutes;
};

const featureRouteModules = import.meta.glob<FeatureRouteModule>(
  "/src/features/*/routes.ts",
  { eager: true, exhaustive: true },
);

export function createAppRouter(history = createWebHistory()): Router {
  const features = Object.values(featureRouteModules);
  const rootRoutes = features.flatMap((feature) => feature.featureRoutes.root ?? []);
  const projectChildren = features.flatMap(
    (feature) => feature.featureRoutes.project ?? [],
  );
  const layoutChildren = features.flatMap((feature) =>
    attachProjectChildren(feature.featureRoutes.layout ?? [], projectChildren),
  );
  const routes: RouteRecordRaw[] = [
    ...rootRoutes,
    {
      path: "/",
      component: AppLayout,
      meta: { requiresAuth: true },
      children: layoutChildren,
    },
  ];
  return createRouter({ history, routes });
}

function attachProjectChildren(
  layoutRoutes: readonly RouteRecordRaw[],
  projectChildren: readonly RouteRecordRaw[],
): RouteRecordRaw[] {
  if (projectChildren.length === 0) {
    return [...layoutRoutes];
  }
  return layoutRoutes.map((route) => {
    if (route.path !== "/projects/:projectId") {
      return route;
    }
    return {
      ...route,
      children: [...(route.children ?? []), ...projectChildren],
    };
  });
}
