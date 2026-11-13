import type {
  AnnotationPropertyFrame,
  EntityNode,
  UpdateAnnotationPropertyFrameRequest,
} from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { RDFS_LABEL, RDF_LANG_STRING } from "../builtins";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import AnnotationPropertyFrameEditor from "./AnnotationPropertyFrameEditor.vue";
import IriListInput from "./IriListInput.vue";
import propertyEn from "./locales/en-US";
import propertyZh from "./locales/zh-CN";

const IRI = "http://ex.org#seeAlso";

const SUBJECT: EntityNode = {
  iri: IRI,
  entityType: "ANNOTATION_PROPERTY",
  displayName: "seeAlso",
  deprecated: false,
  hasChildren: false,
};

const PARENT: EntityNode = {
  iri: "http://www.w3.org/2000/01/rdf-schema#seeAlso",
  entityType: "ANNOTATION_PROPERTY",
  displayName: "rdfs:seeAlso",
  deprecated: false,
  hasChildren: false,
};

const DOMAIN_IRI = "http://ex.org#Widget";

function initialFrame(): AnnotationPropertyFrame {
  return {
    subject: SUBJECT,
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "seeAlso",
          datatype: RDF_LANG_STRING,
          lang: "en",
        },
      },
    ],
    parents: [PARENT],
    domains: [],
    ranges: [],
    otherAxioms: [],
  };
}

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-select__popper, .el-message, .el-overlay")
    .forEach((node) => node.remove());
});

test("adding a domain IRI then save sends string domains", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const domainInput = wrapper
    .findAllComponents(IriListInput)
    .find((input) => {
      const label = String(input.props("ariaLabel") ?? "");
      return label.includes("定义域") || label.toLowerCase().includes("domain");
    });
  expect(domainInput).toBeDefined();

  const field = domainInput!.get("input");
  await field.setValue(DOMAIN_IRI);
  await domainInput!.get("form").trigger("submit");
  await flushPromises();

  expect(domainInput!.props("modelValue")).toEqual([DOMAIN_IRI]);

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === "/api/projects/p1/frames/annotation-property",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateAnnotationPropertyFrameRequest;
  expect(body.to?.domains).toEqual([DOMAIN_IRI]);
  expect(typeof body.to?.domains?.[0]).toBe("string");
  expect(body.to?.ranges).toEqual([]);
  wrapper.unmount();
});

test("invalid IRI cannot be added to domains", async () => {
  const { wrapper } = await mountEditor();
  await flushPromises();

  const domainInput = wrapper.findAllComponents(IriListInput)[0];
  expect(domainInput).toBeDefined();

  const field = domainInput!.get("input");
  await field.setValue("not-an-iri");
  await domainInput!.get("form").trigger("submit");
  await flushPromises();

  expect(domainInput!.props("modelValue")).toEqual([]);
  expect(wrapper.text()).toMatch(/非法|invalid/i);
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
    name: "AnnotationPropertyFrameEditorHost",
    setup() {
      return () =>
        h(AnnotationPropertyFrameEditor, {
          projectId: "p1",
          iri: IRI,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/annotation-property?entity=${encodeURIComponent(IRI)}`,
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
    handlers: {
      "GET /api/projects/{id}/frames/annotation-property": () => ({
        json: initialFrame(),
      }),
      "PUT /api/projects/{id}/frames/annotation-property": ({ body }) => {
        const request = body as UpdateAnnotationPropertyFrameRequest;
        return {
          json: {
            frame: request.to ?? initialFrame(),
            revision: 5,
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
