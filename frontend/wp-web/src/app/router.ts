import {
  createRouter,
  createWebHistory,
  type Router,
  type RouteRecordRaw,
} from "vue-router";
import type { FeatureRoutes } from "./featureRoutes";

type FeatureRouteModule = {
  readonly featureRoutes: FeatureRoutes;
};

const featureRouteModules = import.meta.glob<FeatureRouteModule>(
  "/src/features/*/routes.ts",
  { eager: true },
);

export function createAppRouter(history = createWebHistory()): Router {
  // P4-01 mounts root only. P4-03 attaches layout; P4-05 attaches project.
  const routes: RouteRecordRaw[] = Object.values(featureRouteModules).flatMap(
    (feature) => feature.featureRoutes.root ?? [],
  );
  return createRouter({ history, routes });
}
