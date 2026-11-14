import type { DisplayNameSetting } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import { RDFS_LABEL } from "@/features/frames/builtins";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import DisplayNameSettingsForm from "./DisplayNameSettingsForm.vue";
import namingEn from "./locales/en-US";
import namingZh from "./locales/zh-CN";

const SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
const DC_TITLE = "http://purl.org/dc/elements/1.1/title";
const DCTERMS_TITLE = "http://purl.org/dc/terms/title";

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

test("empty settings show built-in default hint; start-from-defaults fills four rows", async () => {
  const { wrapper } = await mountForm([]);
  await flushPromises();

  expect(wrapper.text()).toMatch(/内置默认|Built-in defaults/);
  expect(wrapper.text()).toMatch(/skos:prefLabel/);
  expect(wrapper.text()).toMatch(/rdfs:label/);
  expect(wrapper.text()).toMatch(/dc:title/);
  expect(wrapper.text()).toMatch(/dcterms:title/);

  await wrapper.get('[data-testid="start-from-defaults"]').trigger("click");
  await flushPromises();

  const pickers = wrapper.findAllComponents(EntityPicker);
  expect(pickers).toHaveLength(4);
  expect(pickers.map((picker) => picker.props("modelValue")?.iri)).toEqual([
    SKOS_PREF_LABEL,
    RDFS_LABEL.iri,
    DC_TITLE,
    DCTERMS_TITLE,
  ]);
  wrapper.unmount();
});

test("move up changes saved array order", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const initial: DisplayNameSetting[] = [
    { annotationProperty: RDFS_LABEL.iri, lang: "en" },
    { annotationProperty: SKOS_PREF_LABEL, lang: "zh" },
  ];
  const { wrapper, requests } = await mountForm(initial);
  await flushPromises();

  await wrapper.get('[data-testid="move-up-1"]').trigger("click");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/projects/p1/settings/display-names",
  );
  expect(put?.body).toEqual([
    { annotationProperty: SKOS_PREF_LABEL, lang: "zh" },
    { annotationProperty: RDFS_LABEL.iri, lang: "en" },
  ]);
  wrapper.unmount();
});

test("adding rdfs:label with empty, zh, and en langs saves three settings", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm([]);
  await flushPromises();

  await wrapper.get('[data-testid="add-row"]').trigger("click");
  await wrapper.get('[data-testid="add-row"]').trigger("click");
  await wrapper.get('[data-testid="add-row"]').trigger("click");
  await flushPromises();

  const pickers = wrapper.findAllComponents(EntityPicker);
  expect(pickers).toHaveLength(3);
  for (const picker of pickers) {
    picker.vm.$emit("update:modelValue", RDFS_LABEL);
  }
  await flushPromises();

  const langInputs = wrapper.findAll('input[name="lang"]');
  expect(langInputs).toHaveLength(3);
  await langInputs[0]!.setValue("");
  await langInputs[1]!.setValue("zh");
  await langInputs[2]!.setValue("en");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/projects/p1/settings/display-names",
  );
  expect(put?.body).toEqual([
    { annotationProperty: RDFS_LABEL.iri, lang: "" },
    { annotationProperty: RDFS_LABEL.iri, lang: "zh" },
    { annotationProperty: RDFS_LABEL.iri, lang: "en" },
  ]);
  wrapper.unmount();
});

test("editing emits update:dirty(true)", async () => {
  const { wrapper } = await mountForm([]);
  await flushPromises();

  const form = wrapper.findComponent(DisplayNameSettingsForm);
  expect(form.exists()).toBe(true);
  expect(form.emitted("update:dirty")?.at(-1)).toEqual([false]);

  await wrapper.get('[data-testid="start-from-defaults"]').trigger("click");
  await flushPromises();

  expect(form.emitted("update:dirty")?.at(-1)).toEqual([true]);
  wrapper.unmount();
});

test("without EDIT_PROJECT_SETTINGS the form is read-only", async () => {
  const { wrapper, requests } = await mountForm(
    [{ annotationProperty: RDFS_LABEL.iri, lang: "en" }],
    { actions: ["VIEW_PROJECT"] },
  );
  await flushPromises();

  expect(wrapper.text()).toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(false);
  expect(
    wrapper
      .findAll('input[name="lang"]')
      .every((input) => input.attributes("disabled") !== undefined),
  ).toBe(true);
  expect(
    requests.filter(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/display-names",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("with EDIT_PROJECT_SETTINGS can save", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm(
    [{ annotationProperty: RDFS_LABEL.iri, lang: "en" }],
    { actions: ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"] },
  );
  await flushPromises();

  expect(wrapper.text()).not.toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(true);

  await wrapper.get('input[name="lang"]').setValue("zh");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/display-names",
    ),
  ).toBe(true);
  wrapper.unmount();
});

async function mountForm(
  initial: DisplayNameSetting[],
  {
    actions = ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"] as const,
  }: { actions?: readonly string[] } = {},
) {
  const rendered = await renderWithProviders(DisplayNameSettingsForm, {
    props: { projectId: "p1" },
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: { actions: [...actions] },
      }),
      "GET /api/projects/{id}/settings/display-names": () => ({
        json: initial,
      }),
      "PUT /api/projects/{id}/settings/display-names": (req) => ({
        json: req.body,
      }),
      "GET /api/projects/{id}/search": () => ({ json: [] }),
    },
  });
  mergeNamingLocales(rendered.wrapper);
  return rendered;
}

function findSaveButton(wrapper: VueWrapper) {
  return (
    wrapper
      .findAll("button")
      .find((button) => /保存|Save/.test(button.text())) ?? {
      exists: () => false,
    }
  );
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
