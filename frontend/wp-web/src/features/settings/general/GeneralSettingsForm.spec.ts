import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import GeneralSettingsForm from "./GeneralSettingsForm.vue";

const PROJECT = {
  projectId: "p1",
  displayName: "Demo",
  description: "A demo project",
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem("wp.locale");
});

test("empty name cannot save", async () => {
  const { wrapper, requests } = await mountForm();
  await flushPromises();

  await setInput(wrapper, "name", "   ");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toContain("请输入项目名称");
  expect(
    requests.filter(
      (request) =>
        request.method === "PATCH" && request.path === "/api/projects/p1",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("saves {name, description} and invalidates project caches", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests, queryClient } = await mountForm();
  await flushPromises();

  queryClient.setQueryData(queryKeys.projects(), [PROJECT]);
  const projectGetsBefore = requests.filter(
    (request) =>
      request.method === "GET" && request.path === "/api/projects/p1",
  ).length;

  await setInput(wrapper, "name", "Renamed");
  await setInput(wrapper, "description", "Updated");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const patch = requests.find(
    (request) =>
      request.method === "PATCH" && request.path === "/api/projects/p1",
  );
  expect(patch?.body).toEqual({
    name: "Renamed",
    description: "Updated",
  });
  // Active projectDetails observer refetches after invalidate, so isInvalidated
  // may already be false — assert the refetch happened instead.
  expect(
    requests.filter(
      (request) =>
        request.method === "GET" && request.path === "/api/projects/p1",
    ).length,
  ).toBeGreaterThan(projectGetsBefore);
  expect(queryClient.getQueryState(queryKeys.projects())?.isInvalidated).toBe(
    true,
  );
  wrapper.unmount();
});

test("without EDIT_PROJECT_SETTINGS the form is read-only", async () => {
  const { wrapper, requests } = await mountForm({
    actions: ["VIEW_PROJECT"],
  });
  await flushPromises();

  expect(wrapper.text()).toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(false);
  expect(
    wrapper
      .findAll('input[name="name"], textarea[name="description"]')
      .every((input) => input.attributes("disabled") !== undefined),
  ).toBe(true);
  expect(
    requests.filter(
      (request) =>
        request.method === "PATCH" && request.path === "/api/projects/p1",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("with EDIT_PROJECT_SETTINGS can save", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm({
    actions: ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"],
  });
  await flushPromises();

  expect(wrapper.text()).not.toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(true);

  await setInput(wrapper, "name", "Manager Rename");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.method === "PATCH" && request.path === "/api/projects/p1",
    ),
  ).toBe(true);
  wrapper.unmount();
});

async function mountForm({
  actions = ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"] as const,
  handlers = {},
}: {
  actions?: readonly string[];
  handlers?: Record<
    string,
    (req: {
      params: Record<string, string>;
      query: URLSearchParams;
      headers: Headers;
      body: unknown;
    }) => { status?: number; json?: unknown; problem?: object }
  >;
} = {}) {
  return renderWithProviders(GeneralSettingsForm, {
    props: { projectId: "p1" },
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: { actions: [...actions] },
      }),
      "GET /api/projects/{id}": () => ({ json: PROJECT }),
      "PATCH /api/projects/{id}": (req) => ({
        json: {
          ...PROJECT,
          displayName:
            typeof req.body === "object" &&
            req.body !== null &&
            "name" in req.body &&
            typeof (req.body as { name?: unknown }).name === "string"
              ? (req.body as { name: string }).name
              : PROJECT.displayName,
          description:
            typeof req.body === "object" &&
            req.body !== null &&
            "description" in req.body &&
            typeof (req.body as { description?: unknown }).description ===
              "string"
              ? (req.body as { description: string }).description
              : PROJECT.description,
        },
      }),
      ...handlers,
    },
  });
}

function findSaveButton(wrapper: VueWrapper) {
  return (
    wrapper
      .findAll("button")
      .find((button) => /保存|Save/.test(button.text())) ?? {
      exists: () => false,
    }
  );
}

async function setInput(
  wrapper: VueWrapper,
  name: string,
  value: string,
): Promise<void> {
  await wrapper
    .get(`input[name="${name}"], textarea[name="${name}"]`)
    .setValue(value);
}
