import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import type { DeviceSession } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import DevicesTab from "./DevicesTab.vue";
import accountEn from "./locales/en-US";
import accountZh from "./locales/zh-CN";

const DEVICE: DeviceSession = {
  tokenId: "33333333-3333-3333-3333-333333333333",
  deviceName: "Pixel 8",
  createdAt: "2024-03-01T08:00:00Z",
  lastUsedAt: "2024-03-10T09:30:00Z",
  accessExpiresAt: "2024-03-11T08:00:00Z",
  refreshExpiresAt: "2024-04-01T08:00:00Z",
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("renders device list with expiry times", async () => {
  const { wrapper } = await mountTab({
    "GET /api/me/devices": () => ({ json: [DEVICE] }),
  });

  const text = wrapper.text();
  expect(text).toContain("Pixel 8");
  expect(text).toContain(
    new Intl.DateTimeFormat("zh-CN", {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(DEVICE.accessExpiresAt!)),
  );
  expect(text).toContain(
    new Intl.DateTimeFormat("zh-CN", {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(DEVICE.refreshExpiresAt!)),
  );

  wrapper.unmount();
});

test("revoke confirm calls revokeDevice and refreshes the list", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  let listed: DeviceSession[] = [DEVICE];
  const { wrapper, requests } = await mountTab({
    "GET /api/me/devices": () => ({ json: listed }),
    "DELETE /api/me/devices/{tokenId}": () => {
      listed = [];
      return { status: 204 };
    },
  });

  const getsBefore = requests.filter(
    (request) =>
      request.method === "GET" && request.path === "/api/me/devices",
  ).length;

  const revokeButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("撤销"));
  expect(revokeButton).toBeDefined();
  await revokeButton!.trigger("click");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  const deleted = requests.find(
    (request) =>
      request.method === "DELETE" &&
      request.path === `/api/me/devices/${DEVICE.tokenId}`,
  );
  expect(deleted).toBeDefined();
  expect(deleted?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(
    requests.filter(
      (request) =>
        request.method === "GET" && request.path === "/api/me/devices",
    ).length,
  ).toBeGreaterThan(getsBefore);

  wrapper.unmount();
});

async function mountTab(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const rendered = await renderWithProviders(DevicesTab, { handlers });
  mergeAccountLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeAccountLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", accountZh);
  i18n.global.mergeLocaleMessage("en-US", accountEn);
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
