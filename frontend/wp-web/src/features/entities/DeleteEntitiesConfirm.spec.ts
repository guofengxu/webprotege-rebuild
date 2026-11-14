import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { confirmDelete } from "./DeleteEntitiesConfirm";
import EntityToolbar from "./EntityToolbar.vue";
import entitiesEn from "./locales/en-US";
import entitiesZh from "./locales/zh-CN";

const ENTITY_IRI = "http://ex.org/Pizza";

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
});

test("confirmDelete returns false when the user cancels", async () => {
  vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
  const t = ((key: string) => key) as never;
  await expect(
    confirmDelete([{ iri: ENTITY_IRI, label: "Pizza" }], t),
  ).resolves.toBe(false);
});

test("cancel does not call deleteEntities", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
  const { wrapper, requests } = await renderToolbar(
    `/projects/p1/class?entity=${encodeURIComponent(ENTITY_IRI)}`,
    {
      "DELETE /api/projects/{id}/entities": () => ({
        json: { revision: 1 },
      }),
    },
  );
  await flushPromises();

  await clickDelete(wrapper);
  await flushPromises();

  expect(
    requests.filter(
      (request) =>
        request.method === "DELETE" &&
        request.path === "/api/projects/p1/entities",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("confirm sends iris, invalidates hierarchy, and clears selection", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
  const { wrapper, requests, router, queryClient } = await renderToolbar(
    `/projects/p1/class?entity=${encodeURIComponent(ENTITY_IRI)}`,
    {
      "DELETE /api/projects/{id}/entities": () => ({
        json: { revision: 1 },
      }),
    },
  );
  await flushPromises();

  queryClient.setQueryData(queryKeys.hierarchyRoots("p1", "class"), []);
  queryClient.setQueryData(
    queryKeys.hierarchyChildren("p1", "class", ENTITY_IRI),
    [],
  );
  queryClient.setQueryData(queryKeys.searchAll("p1"), []);
  queryClient.setQueryData(queryKeys.frame("p1", "CLASS", ENTITY_IRI), {
    iri: ENTITY_IRI,
  });

  await clickDelete(wrapper);
  await flushPromises();

  const deleted = requests.find(
    (request) =>
      request.method === "DELETE" &&
      request.path === "/api/projects/p1/entities",
  );
  expect(deleted?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(deleted?.body).toEqual({ iris: [ENTITY_IRI] });
  expect(
    queryClient.getQueryState(queryKeys.hierarchyRoots("p1", "class"))
      ?.isInvalidated,
  ).toBe(true);
  expect(
    queryClient.getQueryState(
      queryKeys.hierarchyChildren("p1", "class", ENTITY_IRI),
    )?.isInvalidated,
  ).toBe(true);
  expect(router.currentRoute.value.query.entity).toBeUndefined();
  wrapper.unmount();
});

async function renderToolbar(
  route: string,
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const Host = defineComponent({
    name: "DeleteConfirmToolbarHost",
    setup() {
      return () => h(EntityToolbar, { projectId: "p1", kind: "class" });
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
          actions: [
            "VIEW_PROJECT",
            "VIEW_CHANGES",
            "DOWNLOAD_PROJECT",
            "EDIT_ONTOLOGY",
            "EDIT_ONTOLOGY_ANNOTATIONS",
            "CREATE_CLASS",
            "DELETE_CLASS",
            "CREATE_PROPERTY",
            "DELETE_PROPERTY",
            "CREATE_INDIVIDUAL",
            "DELETE_INDIVIDUAL",
            "REVERT_CHANGES",
          ],
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

async function clickDelete(
  wrapper: Awaited<ReturnType<typeof renderToolbar>>["wrapper"],
): Promise<void> {
  const button = wrapper
    .findAll("button")
    .find((entry) => entry.text().includes("删除"));
  expect(button).toBeDefined();
  await button!.trigger("click");
}
