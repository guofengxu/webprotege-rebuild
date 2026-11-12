import { defineComponent, h } from "vue";
import { flushPromises } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { WORKSPACE_KIND_PATH } from "./routes";
import {
  useWorkspaceRoute,
  type WorkspaceRouteApi,
} from "./useWorkspaceRoute";

afterEach(() => {
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

test("parses kind, entity, and type from the URL, including IRIs with # and /", async () => {
  const iri = "http://example.org/ont#Class/Foo";
  const typeIri = "http://example.org/ont#Type/Bar";
  const { api, wrapper } = await mountRoute(
    `/projects/p1/class?entity=${encodeURIComponent(iri)}&type=${encodeURIComponent(typeIri)}`,
  );

  expect(api.projectId.value).toBe("p1");
  expect(api.kind.value).toBe("class");
  expect(api.entityIri.value).toBe(iri);
  expect(api.typeIri.value).toBe(typeIri);
  wrapper.unmount();
});

test("selectEntity uses replace and updates the entity query param", async () => {
  const { api, wrapper, router } = await mountRoute("/projects/p1/class");
  const historyLength = window.history.length;

  api.selectEntity("http://example.org/ont#Thing");
  await flushPromises();

  expect(router.currentRoute.value.query.entity).toBe(
    "http://example.org/ont#Thing",
  );
  expect(window.history.length).toBe(historyLength);

  api.selectEntity(null);
  await flushPromises();
  expect(router.currentRoute.value.query.entity).toBeUndefined();
  wrapper.unmount();
});

test("setKind clears entity and type selection", async () => {
  const { api, wrapper, router } = await mountRoute(
    "/projects/p1/class?entity=http://example.org/%23A&type=http://example.org/%23T",
  );

  api.setKind("object-property");
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/object-property");
  expect(router.currentRoute.value.query.entity).toBeUndefined();
  expect(router.currentRoute.value.query.type).toBeUndefined();
  expect(api.kind.value).toBe("object-property");
  wrapper.unmount();
});

test("illegal kind does not match the workspace route", async () => {
  const { wrapper, router } = await mountRoute("/projects/p1/not-a-kind");

  expect(router.currentRoute.value.name).not.toBe("workspace");
  expect(
    router.currentRoute.value.matched.some(
      (record) => record.name === "workspace",
    ),
  ).toBe(false);
  wrapper.unmount();
});

test("linkTo maps named individuals without a type query and returns null for datatype", async () => {
  const { api, wrapper } = await mountRoute("/projects/p1/class");

  expect(api.linkTo("NAMED_INDIVIDUAL", "http://example.org/%23i")).toEqual({
    path: "/projects/p1/individual",
    query: { entity: "http://example.org/%23i" },
  });
  expect(
    api.linkTo("DATATYPE", "http://www.w3.org/2001/XMLSchema#string"),
  ).toBeNull();
  expect(api.linkTo("IRI", "http://example.org")).toBeNull();
  expect(api.linkTo("ONTOLOGY", "http://example.org/ont")).toBeNull();
  wrapper.unmount();
});

async function mountRoute(route: string) {
  let api!: WorkspaceRouteApi;
  const Host = defineComponent({
    name: "WorkspaceRouteHost",
    setup() {
      api = useWorkspaceRoute();
      return () =>
        h("div", {
          "data-kind": api.kind.value,
          "data-entity": api.entityIri.value ?? "",
        });
    },
  });
  const NotFound = defineComponent({
    name: "NotFound",
    setup() {
      return () => h("p", "not-found");
    },
  });
  const result = await renderWithProviders(Host, {
    route,
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
      { path: "/:pathMatch(.*)*", name: "catch-all", component: NotFound },
    ],
  });
  await flushPromises();
  return { ...result, api };
}
