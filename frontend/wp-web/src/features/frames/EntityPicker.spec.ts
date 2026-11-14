import type { EntityNode } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { SearchType } from "@/features/workspace/hierarchyKinds";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { BUILTIN_DATATYPES } from "./builtins";
import EntityPicker from "./EntityPicker.vue";
import framesEn from "./locales/en-US";
import framesZh from "./locales/zh-CN";
import inputsEn from "./inputs/locales/en-US";
import inputsZh from "./inputs/locales/zh-CN";

const SEARCHED: EntityNode = {
  iri: "http://ex.org#Searched",
  entityType: "DATATYPE",
  displayName: "Searched",
  hasChildren: false,
  deprecated: false,
};

const BUILTIN_STRING = BUILTIN_DATATYPES.find(
  (node) => node.displayName === "xsd:string",
)!;

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper")
    .forEach((node) => node.remove());
});

test("debounced search joins types and uses limit 20", async () => {
  const { wrapper, requests, queryClient } = await mountPicker({
    types: ["datatype", "class"],
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [SEARCHED] }),
    },
  });

  await typeFilter(wrapper, "str");
  await vi.advanceTimersByTimeAsync(249);
  await flushPromises();
  expect(requests.filter((entry) => entry.path.includes("/search"))).toHaveLength(
    0,
  );

  await vi.advanceTimersByTimeAsync(1);
  await flushPromises();

  const searchRequests = requests.filter((entry) =>
    entry.path.includes("/search"),
  );
  expect(searchRequests).toHaveLength(1);
  expect(searchRequests[0]?.query.get("types")).toBe("datatype,class");
  expect(searchRequests[0]?.query.get("limit")).toBe("20");
  expect(searchRequests[0]?.query.get("q")).toBe("str");
  expect(
    queryClient.getQueryData(
      queryKeys.search("p1", "str", ["datatype", "class"], 20),
    ),
  ).toEqual([SEARCHED]);
  wrapper.unmount();
});

test("matching builtins appear before search results and dedupe by iri", async () => {
  const duplicateBuiltin: EntityNode = {
    ...BUILTIN_STRING,
    displayName: "xsd:string (from search)",
  };
  const { wrapper } = await mountPicker({
    types: ["datatype"],
    builtins: BUILTIN_DATATYPES,
    handlers: {
      "GET /api/projects/{id}/search": () => ({
        json: [duplicateBuiltin, SEARCHED],
      }),
    },
  });

  await typeFilter(wrapper, "string");
  await openDropdown(wrapper);
  const labels = optionLabels();
  const builtinIndex = labels.findIndex((text) => text.includes("xsd:string"));
  const searchedIndex = labels.findIndex((text) => text.includes("Searched"));
  expect(builtinIndex).toBeGreaterThanOrEqual(0);
  expect(searchedIndex).toBeGreaterThan(builtinIndex);
  expect(labels.filter((text) => text.includes("xsd:string")).length).toBe(1);
  wrapper.unmount();
});

test("excludeIris hides entities from options", async () => {
  const { wrapper } = await mountPicker({
    types: ["datatype"],
    builtins: BUILTIN_DATATYPES,
    excludeIris: [BUILTIN_STRING.iri],
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [BUILTIN_STRING] }),
    },
  });

  await typeFilter(wrapper, "string");
  await openDropdown(wrapper);
  expect(optionLabels().some((text) => text.includes("xsd:string"))).toBe(
    false,
  );
  wrapper.unmount();
});

test("disabled picker cannot open the select dropdown", async () => {
  const { wrapper, model } = await mountPicker({
    types: ["datatype"],
    disabled: true,
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [SEARCHED] }),
    },
  });

  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.props("disabled")).toBe(true);
  const select = wrapper.get(".el-select");
  expect(
    select.find(".el-select__wrapper.is-disabled").exists() ||
      select.attributes("aria-disabled") === "true" ||
      select.find(".is-disabled").exists(),
  ).toBe(true);
  expect(model.value).toBeNull();
  wrapper.unmount();
});

test("allowRawIri offers use-IRI option that emits entityType IRI", async () => {
  const selected = ref<EntityNode | null>(null);
  const { wrapper } = await mountPicker({
    types: ["datatype"],
    allowRawIri: true,
    modelRef: selected,
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] }),
    },
  });

  const iri = "http://example.org/custom#Type";
  await typeFilter(wrapper, iri);
  await openDropdown(wrapper);
  const useIri = optionElements().find((node) =>
    (node.textContent ?? "").includes("使用 IRI"),
  );
  expect(useIri).toBeDefined();
  useIri!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await flushPromises();

  expect(selected.value).toEqual({
    iri,
    entityType: "IRI",
    displayName: "Type",
    deprecated: false,
    hasChildren: false,
  });
  wrapper.unmount();
});

test("without allowRawIri there is no use-IRI option", async () => {
  const { wrapper } = await mountPicker({
    types: ["datatype"],
    allowRawIri: false,
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] }),
    },
  });

  await typeFilter(wrapper, "http://example.org/custom#Type");
  await openDropdown(wrapper);
  expect(
    optionLabels().some((text) => text.includes("使用 IRI")),
  ).toBe(false);
  wrapper.unmount();
});

async function mountPicker(options: {
  types: SearchType[];
  builtins?: readonly EntityNode[];
  excludeIris?: readonly string[];
  allowRawIri?: boolean;
  disabled?: boolean;
  modelRef?: ReturnType<typeof ref<EntityNode | null>>;
  handlers?: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never;
}) {
  const model = options.modelRef ?? ref<EntityNode | null>(null);
  const Host = defineComponent({
    name: "EntityPickerHost",
    setup() {
      return () =>
        h(EntityPicker, {
          projectId: "p1",
          types: options.types,
          modelValue: model.value ?? null,
          "onUpdate:modelValue": (value: EntityNode | null) => {
            model.value = value;
          },
          ...(options.builtins !== undefined
            ? { builtins: options.builtins }
            : {}),
          ...(options.allowRawIri !== undefined
            ? { allowRawIri: options.allowRawIri }
            : {}),
          ...(options.excludeIris !== undefined
            ? { excludeIris: options.excludeIris }
            : {}),
          disabled: options.disabled ?? false,
          ariaLabel: "实体",
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers: options.handlers,
    route: "/projects/p1/class",
    routes: [
      {
        path: "/projects/:projectId/:kind",
        name: "workspace",
        component: Host,
      },
    ],
  });
  mergeLocales(rendered.wrapper);
  await flushPromises();
  return { ...rendered, model };
}

function mergeLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", framesZh);
  i18n.global.mergeLocaleMessage("en-US", framesEn);
  i18n.global.mergeLocaleMessage("zh-CN", inputsZh);
  i18n.global.mergeLocaleMessage("en-US", inputsEn);
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

async function typeFilter(wrapper: VueWrapper, value: string): Promise<void> {
  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  (
    picker.vm as unknown as { applyFilter: (query: string) => void }
  ).applyFilter(value);
  await flushPromises();
}

async function openDropdown(wrapper: VueWrapper): Promise<void> {
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
  await wrapper.get(".el-select").trigger("click");
  await flushPromises();
}

function optionElements(): HTMLElement[] {
  return [
    ...document.body.querySelectorAll<HTMLElement>(
      ".el-select-dropdown__item",
    ),
  ].filter((node) => node.offsetParent !== null || node.isConnected);
}

function optionLabels(): string[] {
  return optionElements().map((node) => (node.textContent ?? "").trim());
}
