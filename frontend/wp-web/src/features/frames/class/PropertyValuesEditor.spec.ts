import type { EntityNode, PropertyValue } from "@wp/api-types";
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
import classEn from "./locales/en-US";
import classZh from "./locales/zh-CN";
import PropertyValuesEditor from "./PropertyValuesEditor.vue";

const OBJECT_PROP: EntityNode = {
  iri: "http://ex.org#hasPart",
  entityType: "OBJECT_PROPERTY",
  displayName: "hasPart",
  deprecated: false,
  hasChildren: false,
};

const DATA_PROP: EntityNode = {
  iri: "http://ex.org#hasAge",
  entityType: "DATA_PROPERTY",
  displayName: "hasAge",
  deprecated: false,
  hasChildren: false,
};

const CLASS_FILLER: EntityNode = {
  iri: "http://ex.org#Part",
  entityType: "CLASS",
  displayName: "Part",
  deprecated: false,
  hasChildren: false,
};

const INDIVIDUAL: EntityNode = {
  iri: "http://ex.org#bob",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "bob",
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

test("SOME yields iri filler with class entity", async () => {
  const model = ref<PropertyValue[]>([
    { kind: "SOME", property: undefined, filler: undefined },
  ]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  await emitPicker(wrapper, 0, OBJECT_PROP);
  await emitPicker(wrapper, 1, CLASS_FILLER);
  await flushPromises();

  expect(model.value[0]).toEqual({
    kind: "SOME",
    property: OBJECT_PROP,
    filler: { type: "iri", entity: CLASS_FILLER },
  });
  expect(model.value[0]?.filler?.type).toBe("iri");
  expect(valid.value).toBe(true);
  wrapper.unmount();
});

test("HAS_VALUE with object property yields individual iri filler", async () => {
  const model = ref<PropertyValue[]>([
    { kind: "HAS_VALUE", property: OBJECT_PROP, filler: undefined },
  ]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  await emitPicker(wrapper, 1, INDIVIDUAL);
  await flushPromises();

  expect(model.value[0]?.filler).toEqual({
    type: "iri",
    entity: INDIVIDUAL,
  });
  expect(model.value[0]?.filler?.type).toBe("iri");
  expect(valid.value).toBe(true);
  wrapper.unmount();
});

test("DATA_SOME can pick builtin xsd:integer when search is empty", async () => {
  const model = ref<PropertyValue[]>([
    { kind: "DATA_SOME", property: DATA_PROP, filler: undefined },
  ]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  const datatypePicker = wrapper
    .findAllComponents(EntityPicker)
    .find((picker) => picker.props("builtins") === BUILTIN_DATATYPES);
  expect(datatypePicker).toBeDefined();
  expect(datatypePicker!.props("types")).toEqual(["datatype"]);
  expect(datatypePicker!.props("allowRawIri") ?? false).toBe(false);

  await datatypePicker!.vm.$emit("update:modelValue", XSD_INTEGER);
  await flushPromises();
  await nextTick();

  expect(model.value[0]).toEqual({
    kind: "DATA_SOME",
    property: DATA_PROP,
    filler: { type: "iri", entity: XSD_INTEGER },
  });
  expect(model.value[0]?.filler?.type).toBe("iri");
  if (model.value[0]?.filler?.type === "iri") {
    expect(model.value[0].filler.entity?.entityType).toBe("DATATYPE");
  }
  expect(valid.value).toBe(true);
  wrapper.unmount();
});

test("HAS_VALUE switching object property to data property clears filler to literal", async () => {
  const model = ref<PropertyValue[]>([
    {
      kind: "HAS_VALUE",
      property: OBJECT_PROP,
      filler: { type: "iri", entity: INDIVIDUAL },
    },
  ]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  expect(wrapper.findComponent(LiteralValueInput).exists()).toBe(false);

  await emitPicker(wrapper, 0, DATA_PROP);
  await flushPromises();
  await nextTick();

  expect(model.value[0]?.property).toEqual(DATA_PROP);
  expect(model.value[0]?.filler?.type).toBe("literal");
  expect(wrapper.findComponent(LiteralValueInput).exists()).toBe(true);
  wrapper.unmount();
});

test("incomplete rows emit valid false then true when filled", async () => {
  const model = ref<PropertyValue[]>([]);
  const valid = ref(true);
  const { wrapper } = await mountEditor({ model, valid });

  await wrapper.get('[data-testid="add-property-value"]').trigger("click");
  await flushPromises();
  expect(valid.value).toBe(false);

  await emitPicker(wrapper, 0, OBJECT_PROP);
  await flushPromises();
  expect(valid.value).toBe(false);

  await emitPicker(wrapper, 1, CLASS_FILLER);
  await flushPromises();
  expect(valid.value).toBe(true);
  wrapper.unmount();
});

async function mountEditor(options: {
  model: ReturnType<typeof ref<PropertyValue[]>>;
  valid: ReturnType<typeof ref<boolean>>;
}) {
  const Host = defineComponent({
    name: "PropertyValuesEditorHost",
    setup() {
      return () =>
        h(PropertyValuesEditor, {
          projectId: "p1",
          modelValue: options.model.value ?? [],
          valid: options.valid.value ?? true,
          "onUpdate:modelValue": (value: PropertyValue[]) => {
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

async function emitPicker(
  wrapper: VueWrapper,
  pickerIndex: number,
  node: EntityNode,
): Promise<void> {
  const pickers = wrapper.findAllComponents(EntityPicker);
  const picker = pickers.at(pickerIndex);
  expect(picker).toBeDefined();
  await picker!.vm.$emit("update:modelValue", node);
  await flushPromises();
  await nextTick();
}

function mergeLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", framesZh);
  i18n.global.mergeLocaleMessage("en-US", framesEn);
  i18n.global.mergeLocaleMessage("zh-CN", inputsZh);
  i18n.global.mergeLocaleMessage("en-US", inputsEn);
  i18n.global.mergeLocaleMessage("zh-CN", classZh);
  i18n.global.mergeLocaleMessage("en-US", classEn);
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
