import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import ResetPasswordDialog from "./ResetPasswordDialog.vue";
import adminEn from "./locales/en-US";
import adminZh from "./locales/zh-CN";

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("11-character password cannot submit", async () => {
  const { wrapper, requests } = await renderDialog();

  await setInput(wrapper, "newPassword", "shortpass11");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/密码/);
  expect(
    requests.filter(
      (request) => request.path === "/api/admin/users/bob/password",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("submit sends newPassword and clears plaintext after success", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await renderDialog({
    "PUT /api/admin/users/{userId}/password": () => ({ status: 204 }),
  });

  await setInput(wrapper, "newPassword", "reset-password-ok");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/admin/users/bob/password",
  );
  expect(put?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(put?.body).toEqual({ newPassword: "reset-password-ok" });

  const passwordInput = wrapper.find('input[name="newPassword"]');
  if (passwordInput.exists()) {
    expect((passwordInput.element as HTMLInputElement).value).toBe("");
  }
  expect(wrapper.html()).not.toContain("reset-password-ok");
  wrapper.unmount();
});

async function renderDialog(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const rendered = await renderWithProviders(ResetPasswordDialog, {
    props: { modelValue: true, userId: "bob" },
    handlers,
    routes: [
      {
        path: "/",
        component: ResetPasswordDialog,
        props: { modelValue: true, userId: "bob" },
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
