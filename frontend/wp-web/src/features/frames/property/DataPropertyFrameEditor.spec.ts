import type {
  DataPropertyFrame,
  EntityNode,
  UpdateDataPropertyFrameRequest,
} from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { BUILTIN_DATATYPES, RDFS_LABEL, RDF_LANG_STRING } from "../builtins";
import EntityPicker from "../EntityPicker.vue";
import EntityRefList from "../EntityRefList.vue";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import DataPropertyFrameEditor from "./DataPropertyFrameEditor.vue";
import propertyEn from "./locales/en-US";
import propertyZh from "./locales/zh-CN";

const IRI = "http://ex.org#hasAge";

const SUBJECT: EntityNode = {
  iri: IRI,
  entityType: "DATA_PROPERTY",
  displayName: "hasAge",
  deprecated: false,
  hasChildren: false,
};

const PARENT: EntityNode = {
  iri: "http://ex.org#topDataProperty",
  entityType: "DATA_PROPERTY",
  displayName: "topDataProperty",
  deprecated: false,
  hasChildren: true,
};

const DOMAIN: EntityNode = {
  iri: "http://ex.org#Person",
  entityType: "CLASS",
  displayName: "Person",
  deprecated: false,
  hasChildren: false,
};

const XSD_STRING = BUILTIN_DATATYPES.find(
  (node) => node.displayName === "xsd:string",
)!;

function initialFrame(): DataPropertyFrame {
  return {
    subject: SUBJECT,
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "hasAge",
          datatype: RDF_LANG_STRING,
          lang: "en",
        },
      },
    ],
    parents: [PARENT],
    domains: [DOMAIN],
    ranges: [],
    functional: false,
    otherAxioms: [],
  };
}

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper, .el-message, .el-overlay")
    .forEach((node) => node.remove());
});

test("toggling functional then save sets to.functional true", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const toggle = wrapper.get('[data-testid="data-property-functional"] input');
  await toggle.setValue(true);
  await flushPromises();

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/data-property",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateDataPropertyFrameRequest;
  expect(body.to?.functional).toBe(true);
  expect(body.to?.domains).toEqual([DOMAIN]);
  expect(body.to?.parents).toEqual([PARENT]);
  wrapper.unmount();
});

test("range picker searches only datatype and can pick xsd:string when search empty", async () => {
  const { wrapper, requests } = await mountEditor({
    handlers: {
      "GET /api/projects/{id}/search": () => ({ json: [] as EntityNode[] }),
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_EDIT",
          systemAdmin: false,
          actions: [...EDIT_ACTIONS],
        },
      }),
    },
  });
  await flushPromises();

  const rangeList = wrapper
    .findAllComponents(EntityRefList)
    .find((list) => {
      const types = list.props("types") as string[] | undefined;
      return types?.includes("datatype") === true;
    });
  expect(rangeList).toBeDefined();
  expect(rangeList!.props("types")).toEqual(["datatype"]);
  expect(rangeList!.props("builtins")).toBe(BUILTIN_DATATYPES);

  const picker = rangeList!.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  expect(picker.props("types")).toEqual(["datatype"]);
  expect(picker.props("builtins")).toBe(BUILTIN_DATATYPES);

  await rangeList!.vm.$emit("update:modelValue", [XSD_STRING]);
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/data-property",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateDataPropertyFrameRequest;
  expect(body.to?.ranges).toEqual([XSD_STRING]);
  wrapper.unmount();
});


const VIEW_ACTIONS = [
  "VIEW_PROJECT",
  "VIEW_CHANGES",
  "DOWNLOAD_PROJECT",
] as const;

const EDIT_ACTIONS = [
  ...VIEW_ACTIONS,
  "EDIT_ONTOLOGY",
  "EDIT_ONTOLOGY_ANNOTATIONS",
  "CREATE_CLASS",
  "DELETE_CLASS",
  "CREATE_PROPERTY",
  "DELETE_PROPERTY",
  "CREATE_INDIVIDUAL",
  "DELETE_INDIVIDUAL",
  "REVERT_CHANGES",
] as const;

async function mountEditor(options?: {
  handlers?: Record<
    string,
    (req: {
      params: Record<string, string>;
      query: URLSearchParams;
      body: unknown;
    }) => { status?: number; json?: unknown; problem?: object }
  >;
}) {
  const Host = defineComponent({
    name: "DataPropertyFrameEditorHost",
    setup() {
      return () =>
        h(DataPropertyFrameEditor, {
          projectId: "p1",
          iri: IRI,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/data-property?entity=${encodeURIComponent(IRI)}`,
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
    handlers: {
      "GET /api/projects/{id}/frames/data-property": () => ({
        json: initialFrame(),
      }),
      "PUT /api/projects/{id}/frames/data-property": ({ body }) => {
        const request = body as UpdateDataPropertyFrameRequest;
        return {
          json: {
            frame: request.to ?? initialFrame(),
            revision: 4,
          },
        };
      },
      "GET /api/projects/{id}/search": () => ({ json: [] as EntityNode[] }),
      ...(options?.handlers ?? {}),
    },
  });
  mergeLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

async function clickSave(wrapper: VueWrapper): Promise<void> {
  const save = wrapper
    .findAll("button")
    .find(
      (button) =>
        button.text().includes("保存") && !button.text().includes("中"),
    );
  expect(save).toBeDefined();
  expect(save!.attributes("disabled")).toBeUndefined();
  await save!.trigger("click");
}

function mergeLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", framesZh);
  i18n.global.mergeLocaleMessage("en-US", framesEn);
  i18n.global.mergeLocaleMessage("zh-CN", inputsZh);
  i18n.global.mergeLocaleMessage("en-US", inputsEn);
  i18n.global.mergeLocaleMessage("zh-CN", propertyZh);
  i18n.global.mergeLocaleMessage("en-US", propertyEn);
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
