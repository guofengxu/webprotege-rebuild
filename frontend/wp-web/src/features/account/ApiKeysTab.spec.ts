import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import type { ApiKeyInfo, IssuedApiKeyResponse } from "@wp/api-types";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import ApiKeysTab from "./ApiKeysTab.vue";
import accountEn from "./locales/en-US";
import accountZh from "./locales/zh-CN";

const EXISTING_KEY: ApiKeyInfo = {
  keyId: "11111111-1111-1111-1111-111111111111",
  name: "ci-bot",
  createdAt: "2024-01-15T10:00:00Z",
  lastUsedAt: undefined,
};

const ISSUED: IssuedApiKeyResponse = {
  keyId: "22222222-2222-2222-2222-222222222222",
  name: "deploy",
  apiKey: "wp_live_secret_plaintext_only_once",
  createdAt: "2024-06-01T12:00:00Z",
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("create shows plaintext and copy button; closing clears plaintext from page and cache", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal("navigator", {
    ...navigator,
    clipboard: { writeText },
  });

  let listed: ApiKeyInfo[] = [EXISTING_KEY];
  const { wrapper, requests, queryClient } = await mountTab({
    "GET /api/me/api-keys": () => ({ json: listed }),
    "POST /api/me/api-keys": () => {
      listed = [
        EXISTING_KEY,
        {
          keyId: ISSUED.keyId,
          name: ISSUED.name,
          createdAt: ISSUED.createdAt,
        },
      ];
      return { status: 201, json: ISSUED };
    },
  });

  await clickButton(wrapper, "新建");
  await flushPromises();
  await setCreateName(wrapper, "deploy");
  await clickButton(wrapper, "创建");
  await flushPromises();

  const created = requests.find(
    (request) =>
      request.method === "POST" && request.path === "/api/me/api-keys",
  );
  expect(created?.body).toEqual({ name: "deploy" });
  expect(created?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");

  expect(wrapper.text()).toContain("此 key 只显示这一次");
  const issuedInput = findIssuedKeyInput(wrapper);
  expect(issuedInput).not.toBeNull();
  expect(issuedInput!.element).toHaveProperty("value", ISSUED.apiKey);

  await clickButton(wrapper, "复制");
  await flushPromises();
  expect(writeText).toHaveBeenCalledWith(ISSUED.apiKey);

  await clickButton(wrapper, "关闭");
  await flushPromises();

  expect(findIssuedKeyInput(wrapper)).toBeNull();
  expect(wrapper.text()).not.toContain(ISSUED.apiKey!);
  expect(wrapper.html()).not.toContain(ISSUED.apiKey!);

  const cached = queryClient.getQueryData(queryKeys.apiKeys());
  expect(JSON.stringify(cached)).not.toContain(ISSUED.apiKey!);
  expect(JSON.stringify(queryClient.getMutationCache().getAll())).not.toContain(
    ISSUED.apiKey!,
  );
  expect(
    requests.filter(
      (request) =>
        request.method === "GET" && request.path === "/api/me/api-keys",
    ).length,
  ).toBeGreaterThan(1);

  wrapper.unmount();
});

test("revoke confirms and calls revokeApiKey", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  const { wrapper, requests } = await mountTab({
    "GET /api/me/api-keys": () => ({ json: [EXISTING_KEY] }),
    "DELETE /api/me/api-keys/{keyId}": () => ({ status: 204 }),
  });

  const getsBefore = requests.filter(
    (request) =>
      request.method === "GET" && request.path === "/api/me/api-keys",
  ).length;

  await clickButton(wrapper, "撤销");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  const deleted = requests.find(
    (request) =>
      request.method === "DELETE" &&
      request.path === `/api/me/api-keys/${EXISTING_KEY.keyId}`,
  );
  expect(deleted).toBeDefined();
  expect(deleted?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(
    requests.filter(
      (request) =>
        request.method === "GET" && request.path === "/api/me/api-keys",
    ).length,
  ).toBeGreaterThan(getsBefore);

  wrapper.unmount();
});

async function mountTab(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const rendered = await renderWithProviders(ApiKeysTab, { handlers });
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

async function clickButton(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  label: string,
): Promise<void> {
  const button = wrapper
    .findAll("button")
    .find((entry) => entry.text().includes(label));
  expect(button).toBeDefined();
  await button!.trigger("click");
}

async function setCreateName(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  name: string,
): Promise<void> {
  const input = wrapper.find(".el-dialog .el-input__inner");
  expect(input.exists()).toBe(true);
  await input.setValue(name);
  await flushPromises();
}

function findIssuedKeyInput(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
) {
  const input = wrapper.find(".api-keys-tab__issued-input .el-input__inner");
  return input.exists() ? input : null;
}
