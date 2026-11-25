import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import DeleteUserDialog from "./DeleteUserDialog.vue";
import adminEn from "./locales/en-US";
import adminZh from "./locales/zh-CN";

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("delete button stays disabled when confirmation does not match user id", async () => {
  const { wrapper, requests } = await renderDialog();

  expect(deleteButton(wrapper).attributes("disabled")).toBeDefined();

  await setConfirmInput(wrapper, "ada-typo");
  expect(deleteButton(wrapper).attributes("disabled")).toBeDefined();

  await wrapper.get("form").trigger("submit");
  await flushPromises();
  expect(
    requests.filter(
      (request) => request.path === "/api/admin/users/ada",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("matching confirmation enables delete and submits deleteUser", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await renderDialog({
    "DELETE /api/admin/users/{userId}": () => ({ status: 204 }),
  });

  await setConfirmInput(wrapper, "ada");
  expect(deleteButton(wrapper).attributes("disabled")).toBeUndefined();

  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const del = requests.find(
    (request) =>
      request.method === "DELETE" && request.path === "/api/admin/users/ada",
  );
  expect(del?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  wrapper.unmount();
});

async function renderDialog(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const rendered = await renderWithProviders(DeleteUserDialog, {
    props: { modelValue: true, userId: "ada" },
    handlers,
    routes: [
      {
        path: "/",
        component: DeleteUserDialog,
        props: { modelValue: true, userId: "ada" },
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

function deleteButton(wrapper: VueWrapper) {
  const button = wrapper
    .findAll("button")
    .find(
      (entry) =>
        entry.text() === "删除" || entry.attributes("type") === "submit",
    );
  expect(button).toBeDefined();
  return button!;
}

async function setConfirmInput(
  wrapper: Awaited<ReturnType<typeof renderDialog>>["wrapper"],
  value: string,
): Promise<void> {
  const input = wrapper.find('input[name="confirmUserId"]');
  expect(input.exists()).toBe(true);
  await input.setValue(value);
}
