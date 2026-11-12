import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import CreateUserDialog from "./CreateUserDialog.vue";
import adminEn from "./locales/en-US";
import adminZh from "./locales/zh-CN";

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("invalid user id and 11-char password cannot submit", async () => {
  const { wrapper, requests } = await renderDialog();

  await setInput(wrapper, "userId", "!");
  await setInput(wrapper, "displayName", "Bob");
  await setInput(wrapper, "email", "bob@example.org");
  await setInput(wrapper, "initialPassword", "shortpass11");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/用户 ID/);
  expect(wrapper.text()).toMatch(/密码/);
  expect(
    requests.filter((request) => request.path === "/api/admin/users"),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("generate random password produces at least 16 characters", async () => {
  const { wrapper } = await renderDialog();

  const generate = wrapper.find("button.generate-password");
  expect(generate.exists()).toBe(true);
  await generate.trigger("click");
  await flushPromises();

  const password = wrapper.find('input[name="initialPassword"]');
  expect(password.exists()).toBe(true);
  expect(
    String((password.element as HTMLInputElement).value).length,
  ).toBeGreaterThanOrEqual(16);
  wrapper.unmount();
});

test("submit posts createUser body fields", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await renderDialog({
    "POST /api/admin/users": () => ({
      status: 201,
      json: {
        userId: "bob",
        displayName: "Bob",
        email: "bob@example.org",
        appRoles: [],
        passwordResetRequired: false,
      },
    }),
  });

  await setInput(wrapper, "userId", "bob");
  await setInput(wrapper, "displayName", "Bob");
  await setInput(wrapper, "email", "bob@example.org");
  await setInput(wrapper, "initialPassword", "initial-password-ok");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const created = requests.find(
    (request) =>
      request.method === "POST" && request.path === "/api/admin/users",
  );
  expect(created?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(created?.body).toEqual({
    userId: "bob",
    displayName: "Bob",
    email: "bob@example.org",
    initialPassword: "initial-password-ok",
  });
  wrapper.unmount();
});

test("409 shows user id already exists", async () => {
  const { wrapper } = await renderDialog({
    "POST /api/admin/users": () => ({
      status: 409,
      problem: {
        status: 409,
        title: "Conflict",
        code: "CONFLICT",
        detail: "user exists",
      },
    }),
  });

  await setInput(wrapper, "userId", "bob");
  await setInput(wrapper, "displayName", "Bob");
  await setInput(wrapper, "email", "bob@example.org");
  await setInput(wrapper, "initialPassword", "initial-password-ok");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toContain("用户 ID 已存在");
  wrapper.unmount();
});

async function renderDialog(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const rendered = await renderWithProviders(CreateUserDialog, {
    props: { modelValue: true },
    handlers,
    routes: [
      {
        path: "/",
        component: CreateUserDialog,
        props: { modelValue: true },
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

async function setInput(
  wrapper: Awaited<ReturnType<typeof renderDialog>>["wrapper"],
  name: string,
  value: string,
): Promise<void> {
  const input = wrapper.find(`input[name="${name}"]`);
  expect(input.exists()).toBe(true);
  await input.setValue(value);
}
