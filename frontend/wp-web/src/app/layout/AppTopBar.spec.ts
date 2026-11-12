import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import AppTopBar from "./AppTopBar.vue";

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

const ordinaryUser = {
  userId: "u1",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["PROJECT_CREATOR"] as string[],
};

const adminUser = {
  ...ordinaryUser,
  appRoles: ["SYSTEM_ADMIN"],
};

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem("wp.locale");
});

test("ordinary users do not see the user-management item", async () => {
  const { wrapper } = await renderTopBar("/", {
    "GET /api/auth/me": () => ({ json: ordinaryUser }),
  });

  await openUserMenu(wrapper);
  expect(wrapper.text()).not.toContain("用户管理");
  expect(document.body.textContent ?? "").not.toContain("用户管理");
  wrapper.unmount();
});

test("SYSTEM_ADMIN users see the user-management item", async () => {
  const { wrapper } = await renderTopBar("/", {
    "GET /api/auth/me": () => ({ json: adminUser }),
  });

  await openUserMenu(wrapper);
  expect(document.body.textContent ?? "").toContain("用户管理");
  wrapper.unmount();
});

test("logout calls the API and opens the login page", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, router, requests } = await renderTopBar("/", {
    "GET /api/auth/me": () => ({ json: ordinaryUser }),
    "POST /api/auth/logout": () => ({ status: 204 }),
  });

  await openUserMenu(wrapper);
  const logout = findMenuItem("退出登录");
  expect(logout).not.toBeNull();
  logout?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await flushPromises();

  expect(requests.some((request) => request.path === "/api/auth/logout")).toBe(true);
  expect(router.currentRoute.value.path).toBe("/login");
  wrapper.unmount();
});

test("project routes show the project display name from getProject", async () => {
  const { wrapper, requests } = await renderTopBar("/projects/p1", {
    "GET /api/auth/me": () => ({ json: ordinaryUser }),
    "GET /api/projects/p1": () => ({
      json: {
        projectId: "p1",
        displayName: "Pizza Ontology",
      },
    }),
  });

  await flushPromises();
  expect(wrapper.text()).toContain("Pizza Ontology");
  expect(requests.some((request) => request.path === "/api/projects/p1")).toBe(true);
  wrapper.unmount();
});

async function renderTopBar(
  route: string,
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never,
) {
  return renderWithProviders(AppTopBar, {
    route,
    handlers,
    routes: [
      { path: "/", component: AppTopBar },
      { path: "/projects/:projectId", component: AppTopBar },
      { path: "/login", component: StubPage },
      { path: "/me", component: StubPage },
      { path: "/admin/users", component: StubPage },
    ],
  });
}

async function openUserMenu(
  wrapper: Awaited<ReturnType<typeof renderTopBar>>["wrapper"],
): Promise<void> {
  await flushPromises();
  const trigger = wrapper.get('[aria-label^="用户菜单"]');
  await trigger.trigger("click");
  await flushPromises();
}

function findMenuItem(label: string): HTMLElement | null {
  const items = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-dropdown-menu__item"),
  );
  return items.find((item) => item.textContent?.trim() === label) ?? null;
}
