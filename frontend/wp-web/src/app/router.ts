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
  { eager: true },
);

export function createAppRouter(history = createWebHistory()): Router {
  const features = Object.values(featureRouteModules);
  const rootRoutes = features.flatMap((feature) => feature.featureRoutes.root ?? []);
  const layoutChildren = features.flatMap(
    (feature) => feature.featureRoutes.layout ?? [],
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
