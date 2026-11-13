import type {
  ClassFrame,
  EntityNode,
  UpdateClassFrameRequest,
} from "@wp/api-types";
import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessage, ElMessageBox } from "element-plus";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { RDF_LANG_STRING, RDFS_LABEL } from "../builtins";
import framesEn from "../locales/en-US";
import framesZh from "../locales/zh-CN";
import inputsEn from "../inputs/locales/en-US";
import inputsZh from "../inputs/locales/zh-CN";
import ClassFrameEditor from "./ClassFrameEditor.vue";
import classEn from "./locales/en-US";
import classZh from "./locales/zh-CN";

const IRI = "http://ex.org#Widget";

const SUBJECT: EntityNode = {
  iri: IRI,
  entityType: "CLASS",
  displayName: "Widget",
  deprecated: false,
  hasChildren: false,
};

const PARENT: EntityNode = {
  iri: "http://ex.org#Thing",
  entityType: "CLASS",
  displayName: "Thing",
  deprecated: false,
  hasChildren: true,
};

const OBJECT_PROP: EntityNode = {
  iri: "http://ex.org#hasPart",
  entityType: "OBJECT_PROPERTY",
  displayName: "hasPart",
  deprecated: false,
  hasChildren: false,
};

const PART: EntityNode = {
  iri: "http://ex.org#Part",
  entityType: "CLASS",
  displayName: "Part",
  deprecated: false,
  hasChildren: false,
};

const OTHER_AXIOM = "Class: Widget EquivalentTo: Part and Thing";

function initialFrame(): ClassFrame {
  return {
    subject: SUBJECT,
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "旧名",
          datatype: RDF_LANG_STRING,
          lang: "zh",
        },
      },
    ],
    parents: [PARENT],
    propertyValues: [
      {
        kind: "SOME",
        property: OBJECT_PROP,
        filler: { type: "iri", entity: PART },
      },
    ],
    otherAxioms: [OTHER_AXIOM],
  };
}

afterEach(() => {
  localStorage.removeItem("wp.locale");
  vi.restoreAllMocks();
  document.body
    .querySelectorAll(".el-popper, .el-select__popper, .el-message, .el-overlay")
    .forEach((node) => node.remove());
});

test("loads frame sections from getClassFrame", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const get = requests.find(
    (entry) =>
      entry.method === "GET" && entry.path === "/api/projects/p1/frames/class",
  );
  expect(get?.query.get("iri")).toBe(IRI);
  expect(wrapper.text()).toContain("Widget");
  expect(
    (wrapper.get('[data-testid="annotation-lexical"]').element as HTMLTextAreaElement)
      .value,
  ).toBe("旧名");
  expect(wrapper.text()).toContain("Thing");
  expect(wrapper.text()).toContain("hasPart");
  expect(wrapper.text()).toContain("Part");
  expect(wrapper.text()).toContain(OTHER_AXIOM);
  wrapper.unmount();
});

test("save sends from as original frame and to with only label changed", async () => {
  const { wrapper, requests } = await mountEditor();
  await flushPromises();

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  await lexical.setValue("新名");
  await flushPromises();

  await clickSave(wrapper);
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" && entry.path === "/api/projects/p1/frames/class",
  );
  expect(put).toBeDefined();
  const body = put!.body as UpdateClassFrameRequest;
  expect(body.from).toEqual(initialFrame());
  expect(body.to?.otherAxioms).toEqual([OTHER_AXIOM]);
  expect(body.to?.parents).toEqual([PARENT]);
  expect(body.to?.propertyValues).toEqual(initialFrame().propertyValues);
  expect(body.to?.annotations).toEqual([
    {
      property: RDFS_LABEL,
      value: {
        type: "literal",
        lexical: "新名",
        datatype: RDF_LANG_STRING,
        lang: "zh",
      },
    },
  ]);
  wrapper.unmount();
});

test("409 FRAME_CHANGED shows conflict and reload refetches getClassFrame", async () => {
  let putCount = 0;
  let frame = initialFrame();
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  const { wrapper, requests } = await mountEditor({
    handlers: {
      "GET /api/projects/{id}/frames/class": () => ({ json: frame }),
      "PUT /api/projects/{id}/frames/class": () => {
        putCount += 1;
        if (putCount === 1) {
          return {
            status: 409,
            problem: { status: 409, code: "FRAME_CHANGED", title: "Conflict" },
          };
        }
        return {
          json: { frame, revision: 2 },
        };
      },
    },
  });
  await flushPromises();

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  await lexical.setValue("冲突名");
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  expect(wrapper.text()).toContain("该实体已被其他人修改");
  const getsBefore = requests.filter(
    (entry) =>
      entry.method === "GET" && entry.path === "/api/projects/p1/frames/class",
  ).length;

  frame = {
    ...initialFrame(),
    annotations: [
      {
        property: RDFS_LABEL,
        value: {
          type: "literal",
          lexical: "服务器名",
          datatype: RDF_LANG_STRING,
          lang: "zh",
        },
      },
    ],
  };

  const reload = [...wrapper.findAll("button")].find((button) =>
    button.text().includes("刷新"),
  );
  expect(reload).toBeDefined();
  await reload!.trigger("click");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  const getsAfter = requests.filter(
    (entry) =>
      entry.method === "GET" && entry.path === "/api/projects/p1/frames/class",
  ).length;
  expect(getsAfter).toBeGreaterThan(getsBefore);
  expect(
    (wrapper.get('[data-testid="annotation-lexical"]').element as HTMLTextAreaElement)
      .value,
  ).toBe("服务器名");
  wrapper.unmount();
});
test("successful save invalidates class hierarchy query", async () => {
  vi.spyOn(ElMessage, "success").mockImplementation(
    () => ({ close: () => undefined }) as never,
  );

  const { wrapper, queryClient } = await mountEditor();
  await flushPromises();

  const hierarchyKey = queryKeys.hierarchy("p1", "class");
  queryClient.setQueryData(hierarchyKey, { roots: [] });
  expect(queryClient.getQueryState(hierarchyKey)?.isInvalidated).toBe(false);

  const lexical = wrapper.get('[data-testid="annotation-lexical"]');
  await lexical.setValue("保存后");
  await flushPromises();
  await clickSave(wrapper);
  await flushPromises();

  expect(queryClient.getQueryState(hierarchyKey)?.isInvalidated).toBe(true);
  expect(ElMessage.success).toHaveBeenCalled();
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
    name: "ClassFrameEditorHost",
    setup() {
      return () =>
        h(ClassFrameEditor, {
          projectId: "p1",
          iri: IRI,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/class?entity=${encodeURIComponent(IRI)}`,
    routes: [
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: Host,
      },
    ],
    handlers: {
      "GET /api/projects/{id}/frames/class": () => ({ json: initialFrame() }),
      "PUT /api/projects/{id}/frames/class": ({ body }) => {
        const request = body as UpdateClassFrameRequest;
        return {
          json: {
            frame: request.to ?? initialFrame(),
            revision: 7,
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
