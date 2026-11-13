import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import type { AuthUserResponse } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import AppRolesDialog from "./AppRolesDialog.vue";
import adminEn from "./locales/en-US";
import adminZh from "./locales/zh-CN";

const CURRENT_USER: AuthUserResponse = {
  userId: "ada",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["SYSTEM_ADMIN"],
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("unchecking SYSTEM_ADMIN submits appRoles with PROJECT_CREATOR only", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await renderDialog({
    userId: "bob",
    appRoles: ["SYSTEM_ADMIN", "PROJECT_CREATOR"],
    handlers: {
      "GET /api/auth/me": () => ({ json: CURRENT_USER }),
      "PUT /api/admin/users/{userId}/app-roles": () => ({
        json: {
          userId: "bob",
          displayName: "Bob",
          email: "bob@example.org",
          appRoles: ["PROJECT_CREATOR"],
          passwordResetRequired: false,
        },
      }),
    },
  });

  await setRoleChecked(wrapper, "SYSTEM_ADMIN", false);
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/admin/users/bob/app-roles",
  );
  expect(put?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(put?.body).toEqual({ appRoles: ["PROJECT_CREATOR"] });
  wrapper.unmount();
});

test("409 LAST_SYSTEM_ADMIN shows a dedicated tip", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
  const { wrapper } = await renderDialog({
    userId: "ada",
    appRoles: ["SYSTEM_ADMIN"],
    handlers: {
      "GET /api/auth/me": () => ({ json: CURRENT_USER }),
      "PUT /api/admin/users/{userId}/app-roles": () => ({
        status: 409,
        problem: {
          status: 409,
          title: "Conflict",
          code: "LAST_SYSTEM_ADMIN",
          detail: "Cannot remove the last system admin",
        },
      }),
    },
  });

  await setRoleChecked(wrapper, "SYSTEM_ADMIN", false);
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/最后一位系统管理员|最后.*系统管理员/);
  wrapper.unmount();
});

test("removing own SYSTEM_ADMIN asks for confirmation before PUT", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const confirmSpy = vi
    .spyOn(ElMessageBox, "confirm")
    .mockResolvedValue("confirm" as never);

  const { wrapper, requests } = await renderDialog({
    userId: "ada",
    appRoles: ["SYSTEM_ADMIN", "PROJECT_CREATOR"],
    handlers: {
      "GET /api/auth/me": () => ({ json: CURRENT_USER }),
      "PUT /api/admin/users/{userId}/app-roles": () => ({
        json: {
          userId: "ada",
          displayName: "Ada",
          email: "ada@example.org",
          appRoles: ["PROJECT_CREATOR"],
          passwordResetRequired: false,
        },
      }),
    },
  });

  await setRoleChecked(wrapper, "SYSTEM_ADMIN", false);
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(confirmSpy).toHaveBeenCalled();
  expect(
    requests.some(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === "/api/admin/users/ada/app-roles" &&
        JSON.stringify(entry.body) ===
          JSON.stringify({ appRoles: ["PROJECT_CREATOR"] }),
    ),
  ).toBe(true);
  wrapper.unmount();
});

async function renderDialog(options: {
  userId: string;
  appRoles: string[];
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"];
}) {
  const rendered = await renderWithProviders(AppRolesDialog, {
    props: {
      modelValue: true,
      userId: options.userId,
      appRoles: options.appRoles,
    },
    handlers: options.handlers,
    routes: [
      {
        path: "/",
        component: AppRolesDialog,
        props: {
          modelValue: true,
          userId: options.userId,
          appRoles: options.appRoles,
        },
      },
    ],
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

async function setRoleChecked(
  wrapper: VueWrapper,
  role: "SYSTEM_ADMIN" | "PROJECT_CREATOR",
  checked: boolean,
): Promise<void> {
  const checkbox = wrapper.find(`input[type="checkbox"][name="${role}"]`);
  expect(checkbox.exists()).toBe(true);
  const input = checkbox.element as HTMLInputElement;
  if (input.checked !== checked) {
    await checkbox.setValue(checked);
    await checkbox.trigger("change");
  }
}
