import type { RouteRecordRaw } from "vue-router";

export type FeatureRoutes = {
  root?: RouteRecordRaw[];
  layout?: RouteRecordRaw[];
  project?: RouteRecordRaw[];
};
