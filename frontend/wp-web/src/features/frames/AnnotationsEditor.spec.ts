import type { AnnotationValue, EntityNode } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import AnnotationsEditor from "./AnnotationsEditor.vue";
import {
  BUILTIN_ANNOTATION_PROPERTIES,
  RDF_LANG_STRING,
} from "./builtins";
import EntityPicker from "./EntityPicker.vue";
import framesEn from "./locales/en-US";
import framesZh from "./locales/zh-CN";
import inputsEn from "./inputs/locales/en-US";
import inputsZh from "./inputs/locales/zh-CN";

const RDFS_LABEL = BUILTIN_ANNOTATION_PROPERTIES.find(
  (node) => node.displayName === "rdfs:label",
)!;
const RDFS_COMMENT = BUILTIN_ANNOTATION_PROPERTIES.find(
  (node) => node.displayName === "rdfs:comment",
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

test("add Chinese label emits langString literal on rdfs:label", async () => {
  const model = ref<AnnotationValue[]>([]);
  const { wrapper } = await mountEditor({ model });

  await wrapper.get('[data-testid="add-label"]').trigger("click");
  await flushPromises();
  expect(model.value).toHaveLength(1);
  expect(model.value[0]?.property).toEqual(RDFS_LABEL);

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  await lexical.setValue("周转托盘");
  const lang = wrapper.get('[data-testid="annotation-lang"]');
  await lang.setValue("zh");
  await flushPromises();

  expect(model.value[0]).toEqual({
    property: RDFS_LABEL,
    value: {
      type: "literal",
      lexical: "周转托盘",
      datatype: RDF_LANG_STRING,
      lang: "zh",
    },
  });
  wrapper.unmount();
});

test("switching value type to IRI stores an iri value", async () => {
  const model = ref<AnnotationValue[]>([
    {
      property: RDFS_LABEL,
      value: {
        type: "literal",
        lexical: "x",
        datatype: RDF_LANG_STRING,
        lang: "en",
      },
    },
  ]);
  const { wrapper } = await mountEditor({ model });

  await wrapper.get('[data-testid="value-type-iri"]').trigger("click");
  await flushPromises();

  const iri = "http://example.org/see#Also";
  await selectFromPicker(wrapper, iri, "使用 IRI");
  expect(model.value[0]?.value).toEqual({
    type: "iri",
    entity: {
      iri,
      entityType: "IRI",
      displayName: "Also",
      deprecated: false,
      hasChildren: false,
    },
  });
  wrapper.unmount();
});

test("removing a row shortens the v-model array", async () => {
  const model = ref<AnnotationValue[]>([
    {
      property: RDFS_LABEL,
      value: {
        type: "literal",
        lexical: "one",
        datatype: RDF_LANG_STRING,
        lang: "en",
      },
    },
    {
      property: RDFS_COMMENT,
      value: {
        type: "literal",
        lexical: "two",
        datatype: RDF_LANG_STRING,
        lang: "en",
      },
    },
  ]);
  const { wrapper } = await mountEditor({ model });

  await wrapper.get('[data-testid="remove-annotation-0"]').trigger("click");
  await flushPromises();
  expect(model.value).toHaveLength(1);
  expect(model.value[0]?.property?.iri).toBe(RDFS_COMMENT.iri);
  wrapper.unmount();
});

test("empty search still allows selecting builtin rdfs:comment", async () => {
  const model = ref<AnnotationValue[]>([
    {
      property: RDFS_LABEL,
      value: {
        type: "literal",
        lexical: "",
        datatype: RDF_LANG_STRING,
        lang: "",
      },
    },
  ]);
  const { wrapper } = await mountEditor({ model });

  await selectFromPicker(wrapper, "comment", "rdfs:comment");
  expect(model.value[0]?.property).toEqual(RDFS_COMMENT);
  wrapper.unmount();
});

async function mountEditor(options: {
  model: ReturnType<typeof ref<AnnotationValue[]>>;
}) {
  const Host = defineComponent({
    name: "AnnotationsEditorHost",
    setup() {
      return () =>
        h(AnnotationsEditor, {
          projectId: "p1",
          modelValue: options.model.value ?? [],
          "onUpdate:modelValue": (value: AnnotationValue[]) => {
            options.model.value = value;
          },
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] as EntityNode[] }),
    },
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
  query: string,
  optionText: string,
): Promise<void> {
  const pickers = wrapper.findAllComponents(EntityPicker);
  const picker =
    optionText.includes("使用 IRI") || optionText.includes("Use IRI")
      ? pickers.at(pickers.length - 1)
      : pickers.at(0);
  expect(picker).toBeDefined();
  (
    picker!.vm as unknown as { applyFilter: (value: string) => void }
  ).applyFilter(query);
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
  await picker!.get(".el-select").trigger("click");
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
