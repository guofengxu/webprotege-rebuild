import type { EntityNode, LiteralValue, NamedIndividualFrame } from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h, nextTick, ref } from "vue";
import { afterEach, expect, test } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { BUILTIN_DATATYPES } from "../builtins";
import EntityPicker from "../EntityPicker.vue";
import LiteralValueInput from "../LiteralValueInput.vue";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import DataAssertionsEditor from "./DataAssertionsEditor.vue";
import individualEn from "./locales/en-US";
import individualZh from "./locales/zh-CN";

type DataPropertyAssertion = NonNullable<
  NamedIndividualFrame["dataPropertyAssertions"]
>[number];

const DATA_PROP: EntityNode = {
  iri: "http://ex.org#age",
  entityType: "DATA_PROPERTY",
  displayName: "age",
  deprecated: false,
  hasChildren: false,
};

const XSD_INTEGER = BUILTIN_DATATYPES.find(
  (node) => node.displayName === "xsd:integer",
)!;

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper")
    .forEach((node) => node.remove());
});

test("xsd:integer assertion yields property plus literal value", async () => {
  const model = ref<DataPropertyAssertion[]>([]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  await wrapper.get('[data-testid="add-data-assertion"]').trigger("click");
  await flushPromises();

  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  expect(picker.props("types")).toEqual(["data-property"]);
  await picker.vm.$emit("update:modelValue", DATA_PROP);
  await flushPromises();
  await nextTick();

  const literal = wrapper.findComponent(LiteralValueInput);
  expect(literal.exists()).toBe(true);
  const nextValue: LiteralValue = {
    type: "literal",
    lexical: "42",
    datatype: XSD_INTEGER.iri,
  };
  await literal.vm.$emit("update:modelValue", nextValue);
  await flushPromises();
  await nextTick();

  expect(model.value[0]).toEqual({
    property: DATA_PROP,
    value: {
      type: "literal",
      lexical: "42",
      datatype: XSD_INTEGER.iri,
    },
  });
  expect(valid.value).toBe(true);
  wrapper.unmount();
});

test("row with missing property emits update:valid false", async () => {
  const model = ref<DataPropertyAssertion[]>([]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  await wrapper.get('[data-testid="add-data-assertion"]').trigger("click");
  await flushPromises();
  expect(valid.value).toBe(false);

  const picker = wrapper.findComponent(EntityPicker);
  await picker.vm.$emit("update:modelValue", DATA_PROP);
  await flushPromises();
  await nextTick();

  const literal = wrapper.findComponent(LiteralValueInput);
  await literal.vm.$emit("update:modelValue", {
    type: "literal",
    lexical: "7",
    datatype: XSD_INTEGER.iri,
  } satisfies LiteralValue);
  await flushPromises();
  await nextTick();

  expect(valid.value).toBe(true);
  wrapper.unmount();
});

async function mountEditor(options: {
  model: ReturnType<typeof ref<DataPropertyAssertion[]>>;
  valid: ReturnType<typeof ref<boolean>>;
}) {
  const Host = defineComponent({
    name: "DataAssertionsEditorHost",
    setup() {
      return () =>
        h(DataAssertionsEditor, {
          projectId: "p1",
          modelValue: options.model.value ?? [],
          valid: options.valid.value ?? true,
          "onUpdate:modelValue": (value: DataPropertyAssertion[]) => {
            options.model.value = value;
          },
          "onUpdate:valid": (value: boolean) => {
            options.valid.value = value;
          },
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] as EntityNode[] }),
    },
    route: "/projects/p1/individual",
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
  i18n.global.mergeLocaleMessage("zh-CN", individualZh);
  i18n.global.mergeLocaleMessage("en-US", individualEn);
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
