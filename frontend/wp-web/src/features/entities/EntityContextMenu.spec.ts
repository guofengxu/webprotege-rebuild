import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { defineComponent, h, nextTick, ref } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import type { MockResponse } from "@/shared/testing/mockFetch";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import EntityContextMenu, {
  type ContextMenuTarget,
} from "./EntityContextMenu.vue";
import entitiesEn from "./locales/en-US";
import entitiesZh from "./locales/zh-CN";

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

const CLASS_NODE: EntityNode = {
  iri: "http://ex.org/Pizza",
  entityType: "CLASS",
  displayName: "Pizza",
  hasChildren: false,
  deprecated: false,
};

const INDIVIDUAL_NODE: EntityNode = {
  iri: "http://ex.org/ont#Margherita",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "Margherita",
  hasChildren: false,
  deprecated: false,
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
});

test("right-click class node shows 新建子类", async () => {
  const { wrapper, openMenu } = await mountMenu("class");
  await openMenu({
    node: CLASS_NODE,
    clientX: 120,
    clientY: 80,
    source: "tree",
  });
  await flushPromises();

  expect(findMenuItem("新建子类")).not.toBeNull();
  expect(findMenuItem("删除")).not.toBeNull();
  wrapper.unmount();
});

test("on individual tab class node shows 新建个体", async () => {
  const { wrapper, openMenu } = await mountMenu(
    "individual",
    `/projects/p1/individual?type=${encodeURIComponent(CLASS_NODE.iri)}`,
  );
  await openMenu({
    node: CLASS_NODE,
    clientX: 40,
    clientY: 60,
    source: "tree",
  });
  await flushPromises();

  expect(findMenuItem("新建个体")).not.toBeNull();
  expect(findMenuItem("删除")).toBeNull();
  wrapper.unmount();
});

test("on individual row shows 删除", async () => {
  const { wrapper, openMenu } = await mountMenu(
    "individual",
    `/projects/p1/individual?type=${encodeURIComponent(CLASS_NODE.iri)}`,
  );
  await openMenu({
    node: INDIVIDUAL_NODE,
    clientX: 200,
    clientY: 140,
    source: "individual-row",
  });
  await flushPromises();

  expect(findMenuItem("删除")).not.toBeNull();
  expect(findMenuItem("新建个体")).toBeNull();
  wrapper.unmount();
});

test("Esc closes menu", async () => {
  const { wrapper, openMenu, target } = await mountMenu("class");
  await openMenu({
    node: CLASS_NODE,
    clientX: 10,
    clientY: 20,
    source: "tree",
  });
  await flushPromises();
  expect(findMenuItem("新建子类")).not.toBeNull();

  document.dispatchEvent(
    new KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
  );
  await flushPromises();
  await nextTick();

  expect(target.value).toBeNull();
  expect(findMenuItem("新建子类")).toBeNull();
  wrapper.unmount();
});

test("CAN_VIEW hides create and delete menu items", async () => {
  const { wrapper, openMenu, target } = await mountMenu("class", undefined, {
    "GET /api/projects/{id}/permissions/me": () => ({
      json: {
        projectId: "p1",
        role: "CAN_VIEW",
        systemAdmin: false,
        actions: [...VIEW_ACTIONS],
      },
    }),
  });
  await openMenu({
    node: CLASS_NODE,
    clientX: 120,
    clientY: 80,
    source: "tree",
  });
  await flushPromises();

  expect(findMenuItem("新建子类")).toBeNull();
  expect(findMenuItem("删除")).toBeNull();
  expect(target.value).toBeNull();
  wrapper.unmount();
});

test("CAN_EDIT shows create and delete menu items", async () => {
  const { wrapper, openMenu } = await mountMenu("class");
  await openMenu({
    node: CLASS_NODE,
    clientX: 120,
    clientY: 80,
    source: "tree",
  });
  await flushPromises();

  expect(findMenuItem("新建子类")).not.toBeNull();
  expect(findMenuItem("删除")).not.toBeNull();
  wrapper.unmount();
});

test("pending permissions disable menu items", async () => {
  let resolveResponse: ((value: MockResponse) => void) | undefined;
  const { wrapper, openMenu } = await mountMenu("class", undefined, {
    "GET /api/projects/{id}/permissions/me": () =>
      new Promise<MockResponse>((resolve) => {
        resolveResponse = resolve;
      }),
  });
  await openMenu({
    node: CLASS_NODE,
    clientX: 120,
    clientY: 80,
    source: "tree",
  });

  const create = findMenuItem("新建子类");
  const remove = findMenuItem("删除");
  expect(create).not.toBeNull();
  expect(remove).not.toBeNull();
  expect(create!.getAttribute("aria-disabled")).toBe("true");
  expect(remove!.getAttribute("aria-disabled")).toBe("true");

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

test("permissions 500 still shows menu items", async () => {
  const { wrapper, openMenu } = await mountMenu("class", undefined, {
    "GET /api/projects/{id}/permissions/me": () => ({
      status: 500,
      problem: { status: 500, code: "INTERNAL_ERROR", title: "Error" },
    }),
  });
  await openMenu({
    node: CLASS_NODE,
    clientX: 120,
    clientY: 80,
    source: "tree",
  });
  await flushPromises();

  expect(findMenuItem("新建子类")).not.toBeNull();
  expect(findMenuItem("删除")).not.toBeNull();
  expect(findMenuItem("新建子类")!.getAttribute("aria-disabled")).not.toBe("true");
  wrapper.unmount();
});

async function mountMenu(
  kind: WorkspaceKind,
  route = `/projects/p1/${kind}`,
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const target = ref<ContextMenuTarget | null>(null);
  const Host = defineComponent({
    name: "EntityContextMenuHost",
    setup() {
      return () =>
        h(EntityContextMenu, {
          projectId: "p1",
          kind,
          target: target.value,
          onClose: () => {
            target.value = null;
          },
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route,
    handlers: {
      "GET /api/projects/{id}/settings/new-entity": () => ({
        json: {
          defaultLang: "en",
          iriPrefix: "http://ex.org/",
          kit: { type: "UUID" },
          labelProperty: "http://www.w3.org/2000/01/rdf-schema#label",
          targetOntology: "http://ex.org/ont",
        },
      }),
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

  async function openMenu(next: ContextMenuTarget): Promise<void> {
    target.value = next;
    await flushPromises();
    await nextTick();
  }

  return { ...rendered, target, openMenu };
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

function findMenuItem(label: string): HTMLElement | null {
  const items = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-dropdown-menu__item"),
  );
  return items.find((item) => item.textContent?.trim() === label) ?? null;
}
