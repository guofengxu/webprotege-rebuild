import type {
  EntityNode,
  ObjectPropertyFrame,
  UpdateObjectPropertyFrameRequest,
} from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { RDFS_LABEL, RDF_LANG_STRING } from "../builtins";
import EntityRefList from "../EntityRefList.vue";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import ObjectPropertyFrameEditor from "./ObjectPropertyFrameEditor.vue";
import propertyEn from "./locales/en-US";
import propertyZh from "./locales/zh-CN";

const IRI = "http://ex.org#hasPart";

const SUBJECT: EntityNode = {
  iri: IRI,
  entityType: "OBJECT_PROPERTY",
  displayName: "hasPart",
  deprecated: false,
  hasChildren: false,
};

const PARENT: EntityNode = {
  iri: "http://ex.org#topObjectProperty",
  entityType: "OBJECT_PROPERTY",
  displayName: "topObjectProperty",
  deprecated: false,
  hasChildren: true,
};

const DOMAIN: EntityNode = {
  iri: "http://ex.org#Widget",
  entityType: "CLASS",
  displayName: "Widget",
  deprecated: false,
  hasChildren: false,
};

const RANGE: EntityNode = {
  iri: "http://ex.org#Part",
  entityType: "CLASS",
  displayName: "Part",
  deprecated: false,
  hasChildren: false,
};

const INVERSE: EntityNode = {
  iri: "http://ex.org#partOf",
  entityType: "OBJECT_PROPERTY",
  displayName: "partOf",
  deprecated: false,
  hasChildren: false,
};

const OTHER_AXIOM = "ObjectProperty: hasPart EquivalentTo: contains";

function initialFrame(): ObjectPropertyFrame {
  return {
    subject: SUBJECT,
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "hasPart",
          datatype: RDF_LANG_STRING,
          lang: "en",
        },
      },
    ],
    parents: [PARENT],
    domains: [DOMAIN],
    ranges: [RANGE],
    characteristics: ["FUNCTIONAL"],
    inverses: [],
    otherAxioms: [OTHER_AXIOM],
  };
}

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper, .el-message, .el-overlay")
    .forEach((node) => node.remove());
});

test("checking TRANSITIVE then save adds characteristic and keeps other fields", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const transitive = wrapper.get(
    '[data-testid="characteristic-TRANSITIVE"] input',
  );
  await transitive.setValue(true);
  await flushPromises();

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/object-property",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateObjectPropertyFrameRequest;
  expect(body.from).toEqual(initialFrame());
  expect(body.to?.characteristics).toEqual(["FUNCTIONAL", "TRANSITIVE"]);
  expect(body.to?.annotations).toEqual(initialFrame().annotations);
  expect(body.to?.parents).toEqual([PARENT]);
  expect(body.to?.domains).toEqual([DOMAIN]);
  expect(body.to?.ranges).toEqual([RANGE]);
  expect(body.to?.inverses).toEqual([]);
  expect(body.to?.otherAxioms).toEqual([OTHER_AXIOM]);
  wrapper.unmount();
});

test("adding an inverse property sends it in to.inverses", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const inverseList = wrapper
    .findAllComponents(EntityRefList)
    .find(
      (list) =>
        list.props("types")?.includes("object-property") &&
        Array.isArray(list.props("modelValue")) &&
        (list.props("modelValue") as EntityNode[]).length === 0,
    );
  expect(inverseList).toBeDefined();
  await inverseList!.vm.$emit("update:modelValue", [INVERSE]);
  await flushPromises();

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/object-property",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateObjectPropertyFrameRequest;
  expect(body.to?.inverses).toEqual([INVERSE]);
  expect(body.to?.characteristics).toEqual(["FUNCTIONAL"]);
  wrapper.unmount();
});

test("409 FRAME_CHANGED shows conflict hint", async () => {
  const { wrapper } = await mountEditor({
    handlers: {
      "PUT /api/projects/{id}/frames/object-property": () => ({
        status: 409,
        problem: { status: 409, code: "FRAME_CHANGED", title: "Conflict" },
      }),
    },
  });
  await flushPromises();

  const transitive = wrapper.get(
    '[data-testid="characteristic-TRANSITIVE"] input',
  );
  await transitive.setValue(true);
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  expect(wrapper.text()).toContain("该实体已被其他人修改");
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
    name: "ObjectPropertyFrameEditorHost",
    setup() {
      return () =>
        h(ObjectPropertyFrameEditor, {
          projectId: "p1",
          iri: IRI,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/object-property?entity=${encodeURIComponent(IRI)}`,
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
    handlers: {
      "GET /api/projects/{id}/frames/object-property": () => ({
        json: initialFrame(),
      }),
      "PUT /api/projects/{id}/frames/object-property": ({ body }) => {
        const request = body as UpdateObjectPropertyFrameRequest;
        return {
          json: {
            frame: request.to ?? initialFrame(),
            revision: 3,
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
