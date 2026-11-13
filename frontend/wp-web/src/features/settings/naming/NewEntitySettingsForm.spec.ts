import type { NewEntitySettings } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import { RDFS_LABEL } from "@/features/frames/builtins";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import NewEntitySettingsForm from "./NewEntitySettingsForm.vue";
import namingEn from "./locales/en-US";
import namingZh from "./locales/zh-CN";

const SETTINGS: NewEntitySettings = {
  defaultLang: "en",
  iriPrefix: "http://example.org/",
  kit: { type: "UUID" },
  labelProperty: RDFS_LABEL.iri,
  targetOntology: "http://example.org/ont",
};

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper")
    .forEach((node) => node.remove());
});

test("switching UUID to OBO sends kit with type, totalDigits, and userRanges", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm();
  await flushPromises();

  await selectKit(wrapper, "OBO");
  await setInput(wrapper, "totalDigits", "7");
  await wrapper.get('[data-testid="add-range"]').trigger("click");
  await setRange(wrapper, 0, "alice", "1", "100");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/projects/p1/settings/new-entity",
  );
  expect(put?.body).toMatchObject({
    kit: {
      type: "OBO",
      totalDigits: 7,
      userRanges: [{ userId: "alice", start: 1, end: 100 }],
    },
  });
  wrapper.unmount();
});

test("overlapping OBO ranges cannot save", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm();
  await flushPromises();

  await selectKit(wrapper, "OBO");
  await setInput(wrapper, "totalDigits", "7");
  await wrapper.get('[data-testid="add-range"]').trigger("click");
  await wrapper.get('[data-testid="add-range"]').trigger("click");
  await setRange(wrapper, 0, "alice", "1", "50");
  await setRange(wrapper, 1, "bob", "50", "100");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/重叠|overlap/i);
  expect(
    requests.filter(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/new-entity",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("switching back to UUID keeps kit with only type", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm({
    "GET /api/projects/{id}/settings/new-entity": () => ({
      json: {
        ...SETTINGS,
        kit: {
          type: "OBO",
          totalDigits: 7,
          userRanges: [{ userId: "alice", start: 1, end: 10 }],
        },
      },
    }),
  });
  await flushPromises();

  await selectKit(wrapper, "UUID");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/projects/p1/settings/new-entity",
  );
  expect(put?.body).toEqual(
    expect.objectContaining({
      kit: { type: "UUID" },
    }),
  );
  expect(Object.keys((put?.body as { kit: object }).kit)).toEqual(["type"]);
  wrapper.unmount();
});

test("empty search still allows choosing rdfs:label for label property", async () => {
  const { wrapper } = await mountForm({
    "GET /api/projects/{id}/search": () => ({ json: [] }),
  });
  await flushPromises();

  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  (
    picker.vm as unknown as { applyFilter: (query: string) => void }
  ).applyFilter("label");
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
  await picker.get(".el-select").trigger("click");
  await flushPromises();

  const option = [
    ...document.body.querySelectorAll<HTMLElement>(
      ".el-select-dropdown__item",
    ),
  ].find((node) => (node.textContent ?? "").includes("rdfs:label"));
  expect(option).toBeDefined();
  option!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await flushPromises();

  expect(picker.props("modelValue")).toEqual(
    expect.objectContaining({ iri: RDFS_LABEL.iri }),
  );
  wrapper.unmount();
});

async function mountForm(
  extraHandlers: Record<
    string,
    (req: {
      params: Record<string, string>;
      query: URLSearchParams;
      headers: Headers;
      body: unknown;
    }) => { status?: number; json?: unknown; problem?: object }
  > = {},
) {
  const rendered = await renderWithProviders(NewEntitySettingsForm, {
    props: { projectId: "p1" },
    handlers: {
      "GET /api/projects/{id}/settings/new-entity": () => ({
        json: SETTINGS,
      }),
      "PUT /api/projects/{id}/settings/new-entity": (req) => ({
        json: req.body,
      }),
      "GET /api/projects/{id}/search": () => ({ json: [] }),
      ...extraHandlers,
    },
  });
  mergeNamingLocales(rendered.wrapper);
  return rendered;
}

function mergeNamingLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", namingZh);
  i18n.global.mergeLocaleMessage("en-US", namingEn);
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

async function selectKit(
  wrapper: VueWrapper,
  type: "UUID" | "SUPPLIED_NAME" | "OBO",
): Promise<void> {
  const input = wrapper.get(`input[name="kitType"][value="${type}"]`);
  await input.setValue(true);
  await input.trigger("change");
  await flushPromises();
}

async function setInput(
  wrapper: VueWrapper,
  name: string,
  value: string,
): Promise<void> {
  await wrapper
    .get(`input[name="${name}"], textarea[name="${name}"]`)
    .setValue(value);
}

async function setRange(
  wrapper: VueWrapper,
  index: number,
  userId: string,
  start: string,
  end: string,
): Promise<void> {
  const userInputs = wrapper.findAll('input[name="userId"]');
  const startInputs = wrapper.findAll('input[name="rangeStart"]');
  const endInputs = wrapper.findAll('input[name="rangeEnd"]');
  expect(userInputs[index]).toBeDefined();
  expect(startInputs[index]).toBeDefined();
  expect(endInputs[index]).toBeDefined();
  await userInputs[index]!.setValue(userId);
  await startInputs[index]!.setValue(start);
  await endInputs[index]!.setValue(end);
}
