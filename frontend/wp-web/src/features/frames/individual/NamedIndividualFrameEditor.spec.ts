import type {
  EntityNode,
  NamedIndividualFrame,
  UpdateNamedIndividualFrameRequest,
} from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { RDF_LANG_STRING, RDFS_LABEL } from "../builtins";
import EntityPicker from "../EntityPicker.vue";
import EntityRefList from "../EntityRefList.vue";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import NamedIndividualFrameEditor from "./NamedIndividualFrameEditor.vue";
import individualEn from "./locales/en-US";
import individualZh from "./locales/zh-CN";

const IRI = "http://ex.org#alice";

const SUBJECT: EntityNode = {
  iri: IRI,
  entityType: "NAMED_INDIVIDUAL",
  displayName: "alice",
  deprecated: false,
  hasChildren: false,
};

const TYPE: EntityNode = {
  iri: "http://ex.org#Person",
  entityType: "CLASS",
  displayName: "Person",
  deprecated: false,
  hasChildren: false,
};

const OBJECT_PROP: EntityNode = {
  iri: "http://ex.org#knows",
  entityType: "OBJECT_PROPERTY",
  displayName: "knows",
  deprecated: false,
  hasChildren: false,
};

const BOB: EntityNode = {
  iri: "http://ex.org#bob",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "bob",
  deprecated: false,
  hasChildren: false,
};

const DATA_PROP: EntityNode = {
  iri: "http://ex.org#age",
  entityType: "DATA_PROPERTY",
  displayName: "age",
  deprecated: false,
  hasChildren: false,
};

const OTHER_AXIOM = "Individual: alice DifferentFrom: carol";

function initialFrame(): NamedIndividualFrame {
  return {
    subject: SUBJECT,
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "alice",
          datatype: RDF_LANG_STRING,
          lang: "en",
        },
      },
    ],
    types: [TYPE],
    objectPropertyAssertions: [
      {
        property: OBJECT_PROP,
        individual: BOB,
      },
    ],
    dataPropertyAssertions: [
      {
        property: DATA_PROP,
        value: {
          type: "literal",
          lexical: "30",
          datatype: "http://www.w3.org/2001/XMLSchema#integer",
        },
      },
    ],
    sameIndividuals: [],
    otherAxioms: [OTHER_AXIOM],
  };
}

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper, .el-message, .el-overlay")
    .forEach((node) => node.remove());
});

test("loads frame showing types and both assertion kinds", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const get = requests.find(
    (entry) =>
      entry.method === "GET" &&
      entry.path === "/api/projects/p1/frames/individual",
  );
  expect(get?.query.get("iri")).toBe(IRI);
  expect(wrapper.text()).toContain("alice");
  expect(wrapper.text()).toContain("Person");
  expect(wrapper.text()).toContain("knows");
  expect(wrapper.text()).toContain("bob");
  expect(wrapper.text()).toContain("age");
  const lexicalInputs = wrapper.findAll('[data-testid="annotation-lexical"]');
  const dataLexical = lexicalInputs.at(1);
  expect(dataLexical).toBeDefined();
  expect((dataLexical!.element as HTMLTextAreaElement).value).toBe("30");
  expect(wrapper.text()).toContain(OTHER_AXIOM);
  wrapper.unmount();
});

test("adding an object property assertion saves from original and to with extra assertion", async () => {
  const NEW_PROP: EntityNode = {
    iri: "http://ex.org#likes",
    entityType: "OBJECT_PROPERTY",
    displayName: "likes",
    deprecated: false,
    hasChildren: false,
  };
  const CAROL: EntityNode = {
    iri: "http://ex.org#carol",
    entityType: "NAMED_INDIVIDUAL",
    displayName: "carol",
    deprecated: false,
    hasChildren: false,
  };

  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  await wrapper.get('[data-testid="add-object-assertion"]').trigger("click");
  await flushPromises();

  const pickers = wrapper.findAllComponents(EntityPicker);
  const propertyPicker = pickers.find(
    (picker) =>
      Array.isArray(picker.props("types")) &&
      (picker.props("types") as string[]).includes("object-property") &&
      picker.props("modelValue") === null,
  );
  expect(propertyPicker).toBeDefined();
  await propertyPicker!.vm.$emit("update:modelValue", NEW_PROP);
  await flushPromises();

  const individualPicker = wrapper
    .findAllComponents(EntityPicker)
    .find(
      (picker) =>
        Array.isArray(picker.props("types")) &&
        (picker.props("types") as string[]).includes("named-individual") &&
        picker.props("modelValue") === null,
    );
  expect(individualPicker).toBeDefined();
  await individualPicker!.vm.$emit("update:modelValue", CAROL);
  await flushPromises();

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/individual",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateNamedIndividualFrameRequest;
  expect(body.from).toEqual(initialFrame());
  expect(body.to?.objectPropertyAssertions).toEqual([
    { property: OBJECT_PROP, individual: BOB },
    { property: NEW_PROP, individual: CAROL },
  ]);
  expect(body.to?.types).toEqual([TYPE]);
  expect(body.to?.dataPropertyAssertions).toEqual(
    initialFrame().dataPropertyAssertions,
  );
  wrapper.unmount();
});

test("409 FRAME_CHANGED shows conflict hint", async () => {
  const { wrapper } = await mountEditor({
    handlers: {
      "PUT /api/projects/{id}/frames/individual": () => ({
        status: 409,
        problem: { status: 409, code: "FRAME_CHANGED", title: "Conflict" },
      }),
    },
  });
  await flushPromises();

  const typesList = wrapper
    .findAllComponents(EntityRefList)
    .find((list) => (list.props("types") as string[]).includes("class"));
  expect(typesList).toBeDefined();
  const EXTRA_TYPE: EntityNode = {
    iri: "http://ex.org#Agent",
    entityType: "CLASS",
    displayName: "Agent",
    deprecated: false,
    hasChildren: false,
  };
  await typesList!.vm.$emit("update:modelValue", [TYPE, EXTRA_TYPE]);
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  expect(wrapper.text()).toContain("该实体已被其他人修改");
  wrapper.unmount();
});

test("same-individual EntityRefList excludes the current individual", async () => {
  const { wrapper } = await mountEditor();
  await flushPromises();

  const sameList = wrapper
    .findAllComponents(EntityRefList)
    .find((list) =>
      (list.props("types") as string[]).includes("named-individual"),
    );
  expect(sameList).toBeDefined();
  expect(sameList!.props("excludeIris")).toEqual([IRI]);
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


test("without EDIT_ONTOLOGY inputs are disabled and no PUT is sent", async () => {
  const { wrapper, requests } = await mountEditor({
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_VIEW",
          systemAdmin: false,
          actions: [...VIEW_ACTIONS],
        },
      }),
    },
  });
  await flushPromises();

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  expect((lexical.element as HTMLTextAreaElement).disabled).toBe(true);
  expect(wrapper.find('[aria-label="保存"]').exists()).toBe(false);
  expect(wrapper.text()).toContain("只读：你在本项目中没有编辑权限");
  expect(
    requests.some(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === "/api/projects/p1/frames/individual",
    ),
  ).toBe(false);
  wrapper.unmount();
});

test("with EDIT_ONTOLOGY save still works", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  expect((lexical.element as HTMLTextAreaElement).disabled).toBe(false);
  await lexical.setValue("可编辑");
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  expect(
    requests.some(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === "/api/projects/p1/frames/individual",
    ),
  ).toBe(true);
  wrapper.unmount();
});

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
    name: "NamedIndividualFrameEditorHost",
    setup() {
      return () =>
        h(NamedIndividualFrameEditor, {
          projectId: "p1",
          iri: IRI,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/individual?entity=${encodeURIComponent(IRI)}`,
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
    handlers: {
      "GET /api/projects/{id}/frames/individual": () => ({
        json: initialFrame(),
      }),
      "PUT /api/projects/{id}/frames/individual": ({ body }) => {
        const request = body as UpdateNamedIndividualFrameRequest;
        return {
          json: {
            frame: request.to ?? initialFrame(),
            revision: 4,
          },
        };
      },
      "GET /api/projects/{id}/search": () => ({ json: [] as EntityNode[] }),
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_EDIT",
          systemAdmin: false,
          actions: [...EDIT_ACTIONS],
        },
      }),
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
    .find((button) => button.text().includes("保存") && !button.text().includes("中"));
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
