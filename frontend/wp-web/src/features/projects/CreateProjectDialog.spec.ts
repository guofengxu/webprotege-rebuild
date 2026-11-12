import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import CreateProjectDialog from "./CreateProjectDialog.vue";

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

const created = {
  projectId: "new-1",
  displayName: "Fresh",
  description: "",
  owner: "ada",
  modifiedAt: "2026-01-02T03:04:05Z",
  modifiedBy: "ada",
};

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

test("empty name cannot submit", async () => {
  const { wrapper, requests } = await renderDialog({
    "POST /api/projects": () => ({ status: 201, json: created }),
  });

  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toContain("请输入项目名称");
  expect(requests.filter((request) => request.path === "/api/projects")).toHaveLength(0);
  wrapper.unmount();
});

test("invalid ontology IRI shows a validation error", async () => {
  const { wrapper, requests } = await renderDialog({
    "POST /api/projects": () => ({ status: 201, json: created }),
  });

  await setInput(wrapper, "name", "Fresh");
  await setInput(wrapper, "ontologyIri", "not an iri");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toContain("请输入绝对 IRI");
  expect(requests.filter((request) => request.path === "/api/projects")).toHaveLength(0);
  wrapper.unmount();
});

test("successful create navigates to the new project", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests, router } = await renderDialog({
    "POST /api/projects": () => ({ status: 201, json: created }),
  });

  await setInput(wrapper, "name", "Fresh");
  await setInput(wrapper, "description", "empty project");
  await setInput(wrapper, "ontologyIri", "http://example.org/fresh");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const create = requests.find((request) => request.path === "/api/projects");
  expect(create?.method).toBe("POST");
  expect(create?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(create?.body).toEqual({
    name: "Fresh",
    description: "empty project",
    ontologyIri: "http://example.org/fresh",
  });
  expect(router.currentRoute.value.path).toBe("/projects/new-1");
  wrapper.unmount();
});

async function renderDialog(
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never,
) {
  return renderWithProviders(CreateProjectDialog, {
    props: { modelValue: true },
    handlers,
    routes: [
      {
        path: "/",
        component: CreateProjectDialog,
        props: { modelValue: true },
      },
      { path: "/projects/:projectId", component: StubPage },
    ],
  });
}

async function setInput(
  wrapper: Awaited<ReturnType<typeof renderDialog>>["wrapper"],
  name: string,
  value: string,
): Promise<void> {
  const input = wrapper.find(`input[name="${name}"], textarea[name="${name}"]`);
  expect(input.exists()).toBe(true);
  await input.setValue(value);
}
