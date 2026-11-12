import type { QueryClient } from "@tanstack/vue-query";
import { ElMessage } from "element-plus";
import type { RouteLocationNormalized, Router } from "vue-router";
import { currentUserQuery, isSystemAdmin } from "@/features/auth/useAuth";
import type { ApiClient } from "@/shared/api/client";
import { ApiError } from "@/shared/api/problem";
import { createAppI18n, resolveInitialLocale } from "./i18n";

declare module "vue-router" {
  interface RouteMeta {
    requiresAuth?: boolean;
    requiresSystemAdmin?: boolean;
  }
}

const guardI18n = createAppI18n("en-US");

function guardT(key: string): string {
  // Separate composer for router guards; do not touch <html lang>.
  guardI18n.global.locale.value = resolveInitialLocale();
  return String(guardI18n.global.t(key));
}

/** Same-origin paths only. Rejects scheme-relative and backslash redirects. */
export function safeRedirect(raw: unknown): string {
  if (typeof raw !== "string" || !isInternalPath(raw)) {
    return "/";
  }
  return raw;
}

export function installAuthGuard(
  router: Router,
  queryClient: QueryClient,
  client: ApiClient,
): void {
  router.beforeEach(async (to) => {
    const signingIn = to.path === "/login";
    if (!signingIn && !isProtected(to)) {
      return true;
    }
    try {
      const user = await queryClient.fetchQuery(currentUserQuery(client));
      if (signingIn) {
        return user === null ? true : "/";
      }
      if (user === null) {
        return loginLocation(to.fullPath);
      }
      if (to.meta.requiresSystemAdmin === true && !isSystemAdmin(user)) {
        ElMessage.warning(guardT("auth.adminRequired"));
        return "/";
      }
      return true;
    } catch (error) {
      if (signingIn) {
        return true;
      }
      if (error instanceof ApiError && error.status === 401) {
        return loginLocation(to.fullPath);
      }
      return loginLocation(to.fullPath, "network");
    }
  });
}

export function handleUnauthorized(router: Router, queryClient: QueryClient): void {
  // Drop every cached response. A later sign-in must not reuse the previous account.
  queryClient.clear();
  if (router.currentRoute.value.path === "/login") {
    return;
  }
  void router.replace({
    path: "/login",
    query: { redirect: router.currentRoute.value.fullPath },
  });
}

function isProtected(to: RouteLocationNormalized): boolean {
  return to.meta.requiresAuth === true || to.meta.requiresSystemAdmin === true;
}

function loginLocation(fullPath: string, reason?: "network") {
  const query: Record<string, string> = { redirect: fullPath };
  if (reason !== undefined) {
    query.reason = reason;
  }
  return { path: "/login", query };
}

function isInternalPath(path: string): boolean {
  return (
    path.startsWith("/") &&
    !path.startsWith("//") &&
    !path.includes("\\") &&
    !path.includes("://") &&
    !/[\u0000-\u001F]/.test(path)
  );
}
