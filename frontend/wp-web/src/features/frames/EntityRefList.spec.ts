import type { EntityNode } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import type { SearchType } from "@/features/workspace/hierarchyKinds";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { BUILTIN_DATATYPES } from "./builtins";
import EntityPicker from "./EntityPicker.vue";
import EntityRefList from "./EntityRefList.vue";
import framesEn from "./locales/en-US";
import framesZh from "./locales/zh-CN";
import inputsEn from "./inputs/locales/en-US";
import inputsZh from "./inputs/locales/zh-CN";

const CLASS_A: EntityNode = {
  iri: "http://ex.org#A",
  entityType: "CLASS",
  displayName: "A",
  hasChildren: false,
  deprecated: false,
};

const DATATYPE = BUILTIN_DATATYPES.find(
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

test("adding a duplicate IRI does not create a second item", async () => {
  const model = ref<EntityNode[]>([CLASS_A]);
  const { wrapper } = await mountList({
    model,
    types: ["class"],
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [CLASS_A] }),
    },
  });

  await selectFromPicker(wrapper, "A");
  expect(model.value).toEqual([CLASS_A]);
  expect(wrapper.findAll(".el-tag")).toHaveLength(1);
  wrapper.unmount();
});

test("DATATYPE tags are not navigable links", async () => {
  const model = ref<EntityNode[]>([DATATYPE, CLASS_A]);
  const { wrapper, router } = await mountList({
    model,
    types: ["datatype", "class"],
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] }),
    },
  });

  const tags = wrapper.findAll(".el-tag");
  expect(tags).toHaveLength(2);
  const datatypeTag = tags.find((tag) => tag.text().includes("xsd:string"));
  expect(datatypeTag).toBeDefined();
  expect(datatypeTag!.find("a").exists()).toBe(false);

  const classTag = tags.find((tag) => tag.text().includes("A"));
  expect(classTag).toBeDefined();
  const link = classTag!.get("a");
  await link.trigger("click");
  await flushPromises();
  expect(router.currentRoute.value.path).toBe("/projects/p1/class");
  expect(router.currentRoute.value.query.entity).toBe(CLASS_A.iri);
  wrapper.unmount();
});

async function mountList(options: {
  model: ReturnType<typeof ref<EntityNode[]>>;
  types: SearchType[];
  handlers?: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never;
}) {
  const Host = defineComponent({
    name: "EntityRefListHost",
    setup() {
      return () =>
        h(EntityRefList, {
          projectId: "p1",
          types: options.types,
          modelValue: options.model.value ?? [],
          "onUpdate:modelValue": (value: EntityNode[]) => {
            options.model.value = value;
          },
          builtins: BUILTIN_DATATYPES,
          ariaLabel: "实体列表",
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers: options.handlers,
    route: "/projects/p1/class",
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
  });
  mergeLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
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

async function selectFromPicker(
  wrapper: VueWrapper,
  optionText: string,
): Promise<void> {
  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  (
    picker.vm as unknown as { applyFilter: (query: string) => void }
  ).applyFilter(optionText);
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
  await wrapper.get(".el-select").trigger("click");
  await flushPromises();
  const option = [
    ...document.body.querySelectorAll<HTMLElement>(
      ".el-select-dropdown__item",
    ),
  ].find((node) => (node.textContent ?? "").includes(optionText));
  expect(option).toBeDefined();
  option!.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  await flushPromises();
}
