import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import PrefixSettingsForm from "./PrefixSettingsForm.vue";

const PREFIXES = {
  owl: "http://www.w3.org/2002/07/owl#",
  rdfs: "http://www.w3.org/2000/01/rdf-schema#",
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem("wp.locale");
});

test("duplicate prefix names and illegal IRIs cannot save", async () => {
  const { wrapper, requests } = await mountForm();
  await flushPromises();

  await setRow(wrapper, 0, "owl", "http://www.w3.org/2002/07/owl#");
  await setRow(wrapper, 1, "owl", "not an iri");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/重复|非法|绝对 IRI|无效/);
  expect(
    requests.filter(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/prefixes",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("save body is a prefix-to-IRI object", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm();
  await flushPromises();

  await setRow(wrapper, 0, "ex", "http://example.org/");
  await setRow(wrapper, 1, "rdfs", "http://www.w3.org/2000/01/rdf-schema#");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (request) =>
      request.method === "PUT" &&
      request.path === "/api/projects/p1/settings/prefixes",
  );
  expect(put?.body).toEqual({
    ex: "http://example.org/",
    rdfs: "http://www.w3.org/2000/01/rdf-schema#",
  });
  wrapper.unmount();
});

test("putPrefixSettings 403 makes the form read-only", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper } = await mountForm({
    handlers: {
      "PUT /api/projects/{id}/settings/prefixes": () => ({
        status: 403,
        problem: { status: 403, code: "PERMISSION_DENIED" },
      }),
    },
  });
  await flushPromises();

  await setRow(wrapper, 0, "ex", "http://example.org/");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toMatch(/无权限|没有权限|只读/);
  expect(
    wrapper
      .findAll("input")
      .every((input) => input.attributes("disabled") !== undefined),
  ).toBe(true);
  wrapper.unmount();
});

test("without EDIT_PROJECT_PREFIXES the form is read-only", async () => {
  const { wrapper, requests } = await mountForm({
    actions: ["VIEW_PROJECT"],
  });
  await flushPromises();

  expect(wrapper.text()).toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(false);
  expect(
    wrapper
      .findAll("input")
      .every((input) => input.attributes("disabled") !== undefined),
  ).toBe(true);
  expect(
    requests.filter(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/prefixes",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("with EDIT_PROJECT_PREFIXES can save", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountForm({
    actions: ["VIEW_PROJECT", "EDIT_PROJECT_PREFIXES"],
  });
  await flushPromises();

  expect(wrapper.text()).not.toContain("只读：需要管理权限才能修改");
  expect(findSaveButton(wrapper).exists()).toBe(true);

  await setRow(wrapper, 0, "ex", "http://example.org/");
  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.method === "PUT" &&
        request.path === "/api/projects/p1/settings/prefixes",
    ),
  ).toBe(true);
  wrapper.unmount();
});

async function mountForm({
  actions = ["VIEW_PROJECT", "EDIT_PROJECT_PREFIXES"] as const,
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
  return renderWithProviders(PrefixSettingsForm, {
    props: { projectId: "p1" },
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: { actions: [...actions] },
      }),
      "GET /api/projects/{id}/settings/prefixes": () => ({ json: PREFIXES }),
      "PUT /api/projects/{id}/settings/prefixes": (req) => ({
        json: req.body,
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

async function setRow(
  wrapper: VueWrapper,
  index: number,
  prefix: string,
  iri: string,
): Promise<void> {
  const prefixInputs = wrapper.findAll('input[name="prefix"]');
  const iriInputs = wrapper.findAll('input[name="iri"]');
  expect(prefixInputs[index]).toBeDefined();
  expect(iriInputs[index]).toBeDefined();
  await prefixInputs[index]!.setValue(prefix);
  await iriInputs[index]!.setValue(iri);
}
