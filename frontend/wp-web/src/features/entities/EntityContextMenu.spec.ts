import { flushPromises, type VueWrapper } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { defineComponent, h, nextTick, ref } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import EntityContextMenu, {
  type ContextMenuTarget,
} from "./EntityContextMenu.vue";
import entitiesEn from "./locales/en-US";
import entitiesZh from "./locales/zh-CN";

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

async function mountMenu(
  kind: WorkspaceKind,
  route = `/projects/p1/${kind}`,
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
