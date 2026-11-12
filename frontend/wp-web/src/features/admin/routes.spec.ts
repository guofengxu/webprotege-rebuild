import { QueryClient } from "@tanstack/vue-query";
import { ElMessage } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import { createMemoryHistory } from "vue-router";
import { installAuthGuard } from "@/app/authGuard";
import { createAppRouter } from "@/app/router";
import { createApiClient } from "@/shared/api/client";
import { createMockFetch, type MockHandler } from "@/shared/testing/mockFetch";
import AdminUsersPage from "./AdminUsersPage.vue";
import { featureRoutes } from "./routes";

const ordinaryUser = {
  userId: "u1",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["PROJECT_CREATOR"],
};

const adminUser = {
  ...ordinaryUser,
  appRoles: ["SYSTEM_ADMIN"],
};

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
  document.body.innerHTML = "";
});

test("admin users route requires system admin and mounts AdminUsersPage", () => {
  expect(featureRoutes.layout).toEqual([
    expect.objectContaining({
      path: "/admin/users",
      component: AdminUsersPage,
      meta: { requiresSystemAdmin: true },
    }),
  ]);
});

test("non-admin visiting /admin/users is redirected home", async () => {
  localStorage.setItem("wp.locale", "zh-CN");
  const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({
    close: () => undefined,
  }));

  const router = await startAt("/admin/users", {
    "GET /api/auth/me": () => ({ json: ordinaryUser }),
  });

  // Guard returns "/"; the projects layout child then redirects "" → /projects.
  expect(router.currentRoute.value.path).toBe("/projects");
  expect(warning).toHaveBeenCalledWith("需要系统管理员权限");
});

test("system admin can open /admin/users", async () => {
  const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({
    close: () => undefined,
  }));

  const router = await startAt("/admin/users", {
    "GET /api/auth/me": () => ({ json: adminUser }),
    "GET /api/admin/users": () => ({
      json: { page: 0, size: 50, total: 0, users: [] },
    }),
  });

  expect(router.currentRoute.value.path).toBe("/admin/users");
  expect(router.currentRoute.value.matched.at(-1)?.components?.default).toBe(
    AdminUsersPage,
  );
  expect(warning).not.toHaveBeenCalled();
});

async function startAt(path: string, handlers: Record<string, MockHandler>) {
  const { fetch } = createMockFetch(handlers);
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createAppRouter(createMemoryHistory());
  installAuthGuard(router, queryClient, client);
  await router.push(path);
  return router;
}
