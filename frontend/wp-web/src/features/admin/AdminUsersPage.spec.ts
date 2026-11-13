import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import type { AdminUserPage, AdminUserResponse } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import AdminUsersPage from "./AdminUsersPage.vue";
import adminEn from "./locales/en-US";
import adminZh from "./locales/zh-CN";

const USER_READY: AdminUserResponse = {
  userId: "ada",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["SYSTEM_ADMIN"],
  passwordResetRequired: false,
};

const USER_RESET: AdminUserResponse = {
  userId: "bob",
  displayName: "Bob",
  email: "bob@example.org",
  appRoles: ["PROJECT_CREATOR"],
  passwordResetRequired: true,
};

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("first page requests page=0 and marks password-reset users", async () => {
  const { wrapper, requests } = await mountPage({
    "GET /api/admin/users": () => ({
      json: {
        page: 0,
        size: 50,
        total: 2,
        users: [USER_READY, USER_RESET],
      } satisfies AdminUserPage,
    }),
  });
  await flushPromises();

  const list = requests.find(
    (entry) => entry.method === "GET" && entry.path === "/api/admin/users",
  );
  expect(list).toBeDefined();
  expect(list!.query.get("page")).toBe("0");
  expect(list!.query.get("size")).toBe("50");
  expect(wrapper.text()).toContain("ada");
  expect(wrapper.text()).toContain("bob");
  expect(wrapper.text()).toContain("需要设置密码");
  wrapper.unmount();
});

test("page 2 requests page=1", async () => {
  const { wrapper, requests } = await mountPage({
    "GET /api/admin/users": (req) => {
      const page = Number(req.query.get("page") ?? "0");
      if (page === 0) {
        return {
          json: {
            page: 0,
            size: 50,
            total: 51,
            users: [USER_READY],
          } satisfies AdminUserPage,
        };
      }
      return {
        json: {
          page: 1,
          size: 50,
          total: 51,
          users: [USER_RESET],
        } satisfies AdminUserPage,
      };
    },
  });
  await flushPromises();

  const nextButton = wrapper.find(
    ".el-pagination .btn-next, .el-pagination button.btn-next",
  );
  expect(nextButton.exists()).toBe(true);
  await nextButton.trigger("click");
  await flushPromises();

  expect(
    requests.some(
      (entry) =>
        entry.method === "GET" &&
        entry.path === "/api/admin/users" &&
        entry.query.get("page") === "1",
    ),
  ).toBe(true);
  wrapper.unmount();
});

test("403 shows permission denied message", async () => {
  const { wrapper } = await mountPage({
    "GET /api/admin/users": () => ({
      status: 403,
      problem: {
        status: 403,
        title: "Forbidden",
        code: "PERMISSION_DENIED",
      },
    }),
  });
  await flushPromises();

  expect(wrapper.text()).toMatch(/无权限|没有权限|权限/);
  wrapper.unmount();
});

test("action buttons open role and reset dialogs for the row user", async () => {
  const { wrapper } = await mountPage({
    "GET /api/admin/users": () => ({
      json: {
        page: 0,
        size: 50,
        total: 1,
        users: [USER_READY],
      } satisfies AdminUserPage,
    }),
    "GET /api/auth/me": () => ({
      json: {
        userId: "viewer",
        displayName: "Viewer",
        email: "viewer@example.org",
        appRoles: ["SYSTEM_ADMIN"],
      },
    }),
  });
  await flushPromises();

  const editRoles = wrapper
    .findAll("button")
    .find((button) => button.text().includes("修改角色"));
  expect(editRoles).toBeDefined();
  await editRoles!.trigger("click");
  await flushPromises();
  expect(wrapper.text()).toMatch(/修改 ada 的应用角色/);

  const resetPassword = wrapper
    .findAll("button")
    .find((button) => button.text().includes("重置密码"));
  expect(resetPassword).toBeDefined();
  await resetPassword!.trigger("click");
  await flushPromises();
  expect(wrapper.text()).toMatch(/重置 ada 的密码/);
  wrapper.unmount();
});

async function mountPage(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const rendered = await renderWithProviders(AdminUsersPage, {
    handlers,
    routes: [{ path: "/admin/users", component: AdminUsersPage }],
    route: "/admin/users",
  });
  mergeAdminLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeAdminLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", adminZh);
  i18n.global.mergeLocaleMessage("en-US", adminEn);
}

function resolveI18n(wrapper: VueWrapper): {
  global: { mergeLocaleMessage: (locale: string, message: object) => void };
} {
  const provides = wrapper.vm.$.appContext.provides as Record<
    PropertyKey,
    { global: { mergeLocaleMessage: (locale: string, message: object) => void } }
  >;
  const key = Reflect.ownKeys(provides).find(
    (entry) => String(entry) === "Symbol(vue-i18n)",
  );
  expect(key).toBeDefined();
  const i18n = provides[key as PropertyKey];
  if (i18n === undefined) {
    throw new Error("vue-i18n provide missing");
  }
  return i18n;
}
