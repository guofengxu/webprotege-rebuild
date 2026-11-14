import { flushPromises, DOMWrapper, type VueWrapper } from "@vue/test-utils";
import type { CreatedEntities, NewEntitySettings } from "@wp/api-types";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import type { MockResponse } from "@/shared/testing/mockFetch";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import EntityToolbar from "./EntityToolbar.vue";
import entitiesEn from "./locales/en-US";
import entitiesZh from "./locales/zh-CN";

const TYPE_IRI = "http://ex.org/Pizza";

const SETTINGS: NewEntitySettings = {
  defaultLang: "en",
  iriPrefix: "http://ex.org/",
  kit: { type: "UUID" },
  labelProperty: "http://www.w3.org/2000/01/rdf-schema#label",
  targetOntology: "http://ex.org/ont",
};

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

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
});

test("individual tab without type disables 新建", async () => {
  const { wrapper } = await renderToolbar("/projects/p1/individual", "individual");
  await flushPromises();

  const create = findToolbarButton(wrapper, "新建");
  expect(create).toBeDefined();
  expect(create!.attributes("disabled")).toBeDefined();
  wrapper.unmount();
});

test("with type selected create uses NAMED_INDIVIDUAL and parents", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const created: CreatedEntities = {
    entities: [
      {
        iri: "http://ex.org/ont#NewIndividual",
        entityType: "NAMED_INDIVIDUAL",
        displayName: "NewIndividual",
        hasChildren: false,
        deprecated: false,
      },
    ],
    revision: 1,
  };
  const { wrapper, requests } = await renderToolbar(
    `/projects/p1/individual?type=${encodeURIComponent(TYPE_IRI)}`,
    "individual",
    {
      "POST /api/projects/{id}/entities": () => ({ json: created }),
    },
  );
  await flushPromises();

  const create = findToolbarButton(wrapper, "新建");
  expect(create).toBeDefined();
  expect(create!.attributes("disabled")).toBeUndefined();
  await create!.trigger("click");
  await flushPromises();

  const textarea = document.body.querySelector<HTMLTextAreaElement>(
    'textarea[name="names"]',
  );
  expect(textarea).not.toBeNull();
  await new DOMWrapper(textarea!).setValue("NewIndividual");
  const form = document.body.querySelector("form");
  expect(form).not.toBeNull();
  await new DOMWrapper(form!).trigger("submit");
  await flushPromises();

  const request = requests.find(
    (entry) =>
      entry.method === "POST" && entry.path === "/api/projects/p1/entities",
  );
  expect(request?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(request?.body).toEqual({
    entityType: "NAMED_INDIVIDUAL",
    names: ["NewIndividual"],
    lang: "en",
    parents: [TYPE_IRI],
  });
  wrapper.unmount();
});

test("without selected entity 删除 is disabled", async () => {
  const { wrapper } = await renderToolbar("/projects/p1/class", "class");
  await flushPromises();

  const remove = findToolbarButton(wrapper, "删除");
  expect(remove).toBeDefined();
  expect(remove!.attributes("disabled")).toBeDefined();
  wrapper.unmount();
});

test("CAN_VIEW hides create and delete", async () => {
  const { wrapper } = await renderToolbar("/projects/p1/class", "class", {
    "GET /api/projects/{id}/permissions/me": () => ({
      json: {
        projectId: "p1",
        role: "CAN_VIEW",
        systemAdmin: false,
        actions: [...VIEW_ACTIONS],
      },
    }),
  });
  await flushPromises();

  expect(findToolbarButton(wrapper, "新建")).toBeUndefined();
  expect(findToolbarButton(wrapper, "删除")).toBeUndefined();
  wrapper.unmount();
});

test("CAN_EDIT shows create and delete", async () => {
  const { wrapper } = await renderToolbar("/projects/p1/class", "class");
  await flushPromises();

  expect(findToolbarButton(wrapper, "新建")).toBeDefined();
  expect(findToolbarButton(wrapper, "删除")).toBeDefined();
  wrapper.unmount();
});

test("pending permissions disable create and delete", async () => {
  let resolveResponse: ((value: MockResponse) => void) | undefined;
  const { wrapper } = await renderToolbar("/projects/p1/class", "class", {
    "GET /api/projects/{id}/permissions/me": () =>
      new Promise<MockResponse>((resolve) => {
        resolveResponse = resolve;
      }),
  });

  const create = findToolbarButton(wrapper, "新建");
  const remove = findToolbarButton(wrapper, "删除");
  expect(create).toBeDefined();
  expect(remove).toBeDefined();
  expect(create!.attributes("disabled")).toBeDefined();
  expect(remove!.attributes("disabled")).toBeDefined();

  for (let attempt = 0; attempt < 20 && resolveResponse === undefined; attempt += 1) {
    await flushPromises();
  }
  resolveResponse?.({
    json: {
      projectId: "p1",
      role: "CAN_EDIT",
      systemAdmin: false,
      actions: [...EDIT_ACTIONS],
    },
  });
  await flushPromises();
  wrapper.unmount();
});

test("permissions 500 still shows create and delete", async () => {
  const { wrapper } = await renderToolbar("/projects/p1/class", "class", {
    "GET /api/projects/{id}/permissions/me": () => ({
      status: 500,
      problem: { status: 500, code: "INTERNAL_ERROR", title: "Error" },
    }),
  });
  await flushPromises();

  expect(findToolbarButton(wrapper, "新建")).toBeDefined();
  expect(findToolbarButton(wrapper, "删除")).toBeDefined();
  expect(findToolbarButton(wrapper, "新建")!.attributes("disabled")).toBeUndefined();
  wrapper.unmount();
});

async function renderToolbar(
  route: string,
  kind: WorkspaceKind,
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const Host = defineComponent({
    name: "EntityToolbarHost",
    setup() {
      return () => h(EntityToolbar, { projectId: "p1", kind });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route,
    handlers: {
      "GET /api/projects/{id}/settings/new-entity": () => ({ json: SETTINGS }),
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_EDIT",
          systemAdmin: false,
          actions: [...EDIT_ACTIONS],
        },
      }),
      ...handlers,
    },
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: WORKSPACE_KIND_PATH,
            name: "workspace",
            component: Host,
          },
        ],
      },
    ],
  });
  mergeEntitiesLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeEntitiesLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", entitiesZh);
  i18n.global.mergeLocaleMessage("en-US", entitiesEn);
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

function findToolbarButton(
  wrapper: Awaited<ReturnType<typeof renderToolbar>>["wrapper"],
  label: string,
) {
  return wrapper
    .findAll("button")
    .find((button) => button.text().includes(label));
}
