import { QueryClient } from "@tanstack/vue-query";
import { flushPromises } from "@vue/test-utils";
import { ElMessage } from "element-plus";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, expect, test, vi } from "vitest";
import { createApiClient } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queryKeys";
import { createMockFetch, type MockHandler } from "@/shared/testing/mockFetch";
import LoginPage from "@/features/auth/LoginPage.vue";
import { featureRoutes as authRoutes } from "@/features/auth/routes";
import { featureRoutes as homeRoutes } from "@/features/home/routes";
import { handleUnauthorized, installAuthGuard, safeRedirect } from "./authGuard";

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

const user = {
  userId: "u1",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["PROJECT_CREATOR"],
};

afterEach(() => {
  vi.restoreAllMocks();
  document.body.innerHTML = "";
  localStorage.removeItem("wp.locale");
});

test("home is a layout child and login stays a public root route", () => {
  expect("root" in homeRoutes).toBe(false);
  expect(homeRoutes.layout?.[0]).toMatchObject({
    path: "",
    component: expect.anything(),
  });
  expect(authRoutes.root?.[0]?.path).toBe("/login");
  expect(authRoutes.root?.[0]?.component).toBe(LoginPage);
});

test("createAppRouter mounts home under an authenticated AppLayout parent", async () => {
  const { createAppRouter } = await import("./router");
  const { createMemoryHistory } = await import("vue-router");
  const router = createAppRouter(createMemoryHistory());
  await router.push("/");
  const matched = router.currentRoute.value.matched;
  expect(matched.length).toBeGreaterThan(1);
  expect(matched[0]?.meta.requiresAuth).toBe(true);
  expect(matched[0]?.components?.default).toBeDefined();
  expect(matched.at(-1)?.components?.default).toBeDefined();
});

test("safeRedirect keeps internal paths and drops open redirects", () => {
  expect(safeRedirect("/projects")).toBe("/projects");
  expect(safeRedirect("//evil.example")).toBe("/");
  expect(safeRedirect("https://evil.example")).toBe("/");
  expect(safeRedirect(undefined)).toBe("/");
  expect(safeRedirect("/\\evil.example")).toBe("/");
  expect(safeRedirect(["/projects"])).toBe("/");
});

test("an anonymous visit to / goes to the login page and keeps the path", async () => {
  const router = await startAt("/", {
    "GET /api/auth/me": () => ({
      status: 401,
      problem: { status: 401, title: "Unauthorized" },
    }),
  });

  expect(router.currentRoute.value.path).toBe("/login");
  expect(router.currentRoute.value.query.redirect).toBe("/");
  expect(router.currentRoute.value.query.reason).toBeUndefined();
});

test("a signed-in visit to /login returns home", async () => {
  const router = await startAt("/login", {
    "GET /api/auth/me": () => ({ json: user }),
  });

  expect(router.currentRoute.value.path).toBe("/");
});

test("a current-user 500 opens login with a network reason", async () => {
  const router = await startAt("/", {
    "GET /api/auth/me": () => ({
      status: 500,
      problem: { status: 500, title: "Server error", code: "INTERNAL_ERROR" },
    }),
  });

  expect(router.currentRoute.value.path).toBe("/login");
  expect(router.currentRoute.value.query.redirect).toBe("/");
  expect(router.currentRoute.value.query.reason).toBe("network");
});

test("requiresSystemAdmin sends ordinary users home and admits system admins", async () => {
  localStorage.setItem("wp.locale", "zh-CN");
  const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({
    close: () => undefined,
  }));
  const ordinary = await startAt("/admin", {
    "GET /api/auth/me": () => ({ json: user }),
  });

  expect(ordinary.currentRoute.value.path).toBe("/");
  expect(warning).toHaveBeenCalledWith("需要系统管理员权限");

  warning.mockClear();
  const admin = await startAt("/admin", {
    "GET /api/auth/me": () => ({
      json: { ...user, appRoles: ["SYSTEM_ADMIN"] },
    }),
  });

  expect(admin.currentRoute.value.path).toBe("/admin");
  expect(warning).not.toHaveBeenCalled();
});

test("a 401 from another request clears the current user and opens login", async () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/projects", component: StubPage },
      { path: "/login", component: StubPage },
    ],
  });
  await router.push("/projects");
  queryClient.setQueryData(queryKeys.me(), user);
  queryClient.setQueryData(queryKeys.projects(), [{ projectId: "p1" }]);

  handleUnauthorized(router, queryClient);
  await flushPromises();

  expect(queryClient.getQueryData(queryKeys.me())).toBeUndefined();
  expect(queryClient.getQueryData(queryKeys.projects())).toBeUndefined();
  expect(router.currentRoute.value.path).toBe("/login");
  expect(router.currentRoute.value.query.redirect).toBe("/projects");
});

async function startAt(path: string, handlers: Record<string, MockHandler>) {
  const { fetch } = createMockFetch(handlers);
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", component: StubPage, meta: { requiresAuth: true } },
      { path: "/login", component: StubPage },
      { path: "/admin", component: StubPage, meta: { requiresSystemAdmin: true } },
    ],
  });
  installAuthGuard(router, queryClient, client);
  await router.push(path);
  return router;
}
