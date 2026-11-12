import { flushPromises, DOMWrapper, type VueWrapper } from "@vue/test-utils";
import type { CreatedEntities, NewEntitySettings } from "@wp/api-types";
import { defineComponent, h, ref } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import CreateEntitiesDialog from "./CreateEntitiesDialog.vue";
import entitiesEn from "./locales/en-US";
import entitiesZh from "./locales/zh-CN";

const PARENT_IRI = "http://ex.org/Parent";
const PARENT_LABEL = "Parent";

const SETTINGS: NewEntitySettings = {
  defaultLang: "en",
  iriPrefix: "http://ex.org/",
  kit: { type: "UUID" },
  labelProperty: "http://www.w3.org/2000/01/rdf-schema#label",
  targetOntology: "http://ex.org/ont",
};

const CREATED_A = {
  iri: "http://ex.org/ont#A",
  entityType: "CLASS" as const,
  displayName: "A",
  hasChildren: false,
  deprecated: false,
};

const CREATED_B = {
  iri: "http://ex.org/ont#B",
  entityType: "CLASS" as const,
  displayName: "B",
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

test("three lines with a blank and a duplicate submit only two names", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const created: CreatedEntities = {
    entities: [CREATED_A, CREATED_B],
    revision: 1,
  };
  const { wrapper, requests, router, queryClient, open } = await renderDialog({
    "POST /api/projects/{id}/entities": () => ({ json: created }),
  });
  await flushPromises();
  await reopenAfterSettings(open);

  seedHierarchyQueries(queryClient);

  await setNames("A\n\nB\nA");
  await submitForm();
  await flushPromises();

  const create = requests.find(
    (request) =>
      request.method === "POST" &&
      request.path === "/api/projects/p1/entities",
  );
  expect(create?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(create?.body).toEqual({
    entityType: "CLASS",
    names: ["A", "B"],
    lang: "en",
    parents: [PARENT_IRI],
  });
  expect(router.currentRoute.value.query.entity).toBe(CREATED_A.iri);
  expect(
    queryClient.getQueryState(
      queryKeys.hierarchyChildren("p1", "class", PARENT_IRI),
    )?.isInvalidated,
  ).toBe(true);
  expect(
    queryClient.getQueryState(queryKeys.hierarchyRoots("p1", "class"))
      ?.isInvalidated,
  ).toBe(true);
  wrapper.unmount();
});

test("403 shows errors.PERMISSION_DENIED", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, open } = await renderDialog({
    "POST /api/projects/{id}/entities": () => ({
      status: 403,
      problem: {
        status: 403,
        code: "PERMISSION_DENIED",
        title: "Forbidden",
      },
    }),
  });
  await flushPromises();
  await reopenAfterSettings(open);

  await setNames("Solo");
  await submitForm();
  await flushPromises();

  expect(document.body.textContent ?? "").toContain("没有执行此操作的权限");
  wrapper.unmount();
});

async function renderDialog(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const open = ref(true);
  const Host = defineComponent({
    name: "CreateEntitiesDialogHost",
    setup() {
      return () =>
        h(CreateEntitiesDialog, {
          projectId: "p1",
          entityType: "CLASS",
          parent: { iri: PARENT_IRI, label: PARENT_LABEL },
          modelValue: open.value,
          "onUpdate:modelValue": (value: boolean) => {
            open.value = value;
          },
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/class?entity=${encodeURIComponent(PARENT_IRI)}`,
    handlers: {
      "GET /api/projects/{id}/settings/new-entity": () => ({ json: SETTINGS }),
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
  return { ...rendered, open };
}

async function reopenAfterSettings(open: { value: boolean }): Promise<void> {
  open.value = false;
  await flushPromises();
  open.value = true;
  await flushPromises();
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

function seedHierarchyQueries(
  queryClient: Awaited<ReturnType<typeof renderDialog>>["queryClient"],
): void {
  queryClient.setQueryData(
    queryKeys.hierarchyChildren("p1", "class", PARENT_IRI),
    [],
  );
  queryClient.setQueryData(queryKeys.hierarchyRoots("p1", "class"), []);
  queryClient.setQueryData(queryKeys.searchAll("p1"), []);
}

async function setNames(value: string): Promise<void> {
  const textarea = document.body.querySelector<HTMLTextAreaElement>(
    'textarea[name="names"]',
  );
  expect(textarea).not.toBeNull();
  const input = new DOMWrapper(textarea!);
  await input.setValue(value);
}

async function submitForm(): Promise<void> {
  const form = document.body.querySelector("form");
  expect(form).not.toBeNull();
  await new DOMWrapper(form!).trigger("submit");
}
