import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import MergeEntitiesButton from "./MergeEntitiesButton.vue";
import mergeEn from "./locales/en-US";
import mergeZh from "./locales/zh-CN";

const SOURCE_IRI = "http://ex.org/Source";
const OWL_THING = "http://www.w3.org/2002/07/owl#Thing";

const VIEW_ACTIONS = [
  "VIEW_PROJECT",
  "VIEW_CHANGES",
  "DOWNLOAD_PROJECT",
] as const;

const EDIT_ACTIONS = [
  ...VIEW_ACTIONS,
  "EDIT_ONTOLOGY",
  "CREATE_CLASS",
  "DELETE_CLASS",
  "MERGE_ENTITIES",
] as const;

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-overlay, .el-dialog__wrapper, .el-popper")
    .forEach((node) => node.remove());
});

test("without MERGE_ENTITIES permission hides the button", async () => {
  const { wrapper } = await renderButton(
    `/projects/p1/class?entity=${encodeURIComponent(SOURCE_IRI)}`,
    "class",
    {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_VIEW",
          systemAdmin: false,
          actions: [...VIEW_ACTIONS],
        },
      }),
    },
  );
  await flushPromises();

  expect(findMergeButton(wrapper)).toBeUndefined();
  wrapper.unmount();
});

test("without selected entity hides the button", async () => {
  const { wrapper } = await renderButton("/projects/p1/class", "class");
  await flushPromises();

  expect(findMergeButton(wrapper)).toBeUndefined();
  wrapper.unmount();
});

test("built-in entity selection hides the button", async () => {
  const { wrapper } = await renderButton(
    `/projects/p1/class?entity=${encodeURIComponent(OWL_THING)}`,
    "class",
  );
  await flushPromises();

  expect(findMergeButton(wrapper)).toBeUndefined();
  wrapper.unmount();
});

test("CAN_EDIT with selected entity shows the button", async () => {
  const { wrapper } = await renderButton(
    `/projects/p1/class?entity=${encodeURIComponent(SOURCE_IRI)}`,
    "class",
  );
  await flushPromises();

  expect(findMergeButton(wrapper)).toBeDefined();
  wrapper.unmount();
});

async function renderButton(
  route: string,
  kind: WorkspaceKind,
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const Host = defineComponent({
    name: "MergeEntitiesButtonHost",
    setup() {
      return () => h(MergeEntitiesButton, { projectId: "p1", kind });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route,
    handlers: {
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
  mergeLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", mergeZh);
  i18n.global.mergeLocaleMessage("en-US", mergeEn);
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

function findMergeButton(
  wrapper: Awaited<ReturnType<typeof renderButton>>["wrapper"],
) {
  return wrapper
    .findAll("button")
    .find((button) => button.text().includes("合并到"));
}
