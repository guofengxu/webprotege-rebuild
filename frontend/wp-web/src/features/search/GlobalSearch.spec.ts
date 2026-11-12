import type { EntityNode } from "@wp/api-types";
import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import GlobalSearch from "./GlobalSearch.vue";
import searchEn from "./locales/en-US";
import searchZh from "./locales/zh-CN";

const CLASS_ONE: EntityNode = {
  iri: "http://ex.org#One",
  entityType: "CLASS",
  displayName: "One",
  hasChildren: false,
  deprecated: false,
};

const CLASS_TWO: EntityNode = {
  iri: "http://ex.org#Two",
  entityType: "CLASS",
  displayName: "Two",
  hasChildren: false,
  deprecated: false,
};

const CLASS_THREE: EntityNode = {
  iri: "http://ex.org#Three",
  entityType: "CLASS",
  displayName: "Three",
  hasChildren: false,
  deprecated: false,
};

const INDIVIDUAL: EntityNode = {
  iri: "http://ex.org#Margherita",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "Margherita",
  hasChildren: false,
  deprecated: false,
};

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.removeItem("wp.locale");
});

test("ArrowDown twice then Enter navigates to the second result", async () => {
  const { wrapper, router } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({
      json: [CLASS_ONE, CLASS_TWO, CLASS_THREE],
    }),
  });

  await typeQuery(wrapper, "c");
  await openResults(wrapper);

  const input = wrapper.get("input");
  await input.trigger("keydown", { key: "ArrowDown" });
  await input.trigger("keydown", { key: "ArrowDown" });
  await input.trigger("keydown", { key: "Enter" });
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/class");
  expect(router.currentRoute.value.query.entity).toBe(CLASS_TWO.iri);
  wrapper.unmount();
});

test("selecting an individual result navigates to kind=individual", async () => {
  const { wrapper, router } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({
      json: [INDIVIDUAL],
    }),
  });

  await typeQuery(wrapper, "Mar");
  await openResults(wrapper);

  const input = wrapper.get("input");
  await input.trigger("keydown", { key: "Enter" });
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/individual");
  expect(router.currentRoute.value.query.entity).toBe(INDIVIDUAL.iri);
  wrapper.unmount();
});

test("Esc closes the dropdown", async () => {
  const { wrapper } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({
      json: [CLASS_ONE],
    }),
  });

  await typeQuery(wrapper, "One");
  await openResults(wrapper);
  expect(wrapper.find('[role="listbox"]').exists()).toBe(true);

  await wrapper.get("input").trigger("keydown", { key: "Escape" });
  await flushPromises();
  expect(wrapper.find('[role="listbox"]').exists()).toBe(false);
  wrapper.unmount();
});

test("empty results show the display-name settings hint", async () => {
  const { wrapper } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({ json: [] }),
  });

  await typeQuery(wrapper, "missing");
  await openResults(wrapper);

  expect(wrapper.text()).toContain(searchZh.search.noResults);
  expect(wrapper.text()).toContain(searchZh.search.displayNameHint);
  wrapper.unmount();
});

test("search is hidden outside a project route", async () => {
  const { wrapper } = await renderWithProviders(GlobalSearch, {
    route: "/",
    routes: [
      { path: "/", component: GlobalSearch },
      { path: "/projects/:projectId", component: StubPage },
    ],
  });
  mergeSearchLocales(wrapper);
  await flushPromises();
  expect(wrapper.find("input").exists()).toBe(false);
  wrapper.unmount();
});

async function mountSearch(
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never,
) {
  const rendered = await renderWithProviders(GlobalSearch, {
    handlers,
    route: "/projects/p1/class",
    routes: [
      { path: "/", component: StubPage },
      {
        path: `/projects/:projectId/${WORKSPACE_KIND_PATH}`,
        name: "workspace",
        component: GlobalSearch,
      },
    ],
  });
  mergeSearchLocales(rendered.wrapper);
  return rendered;
}

function mergeSearchLocales(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
): void {
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
  i18n.global.mergeLocaleMessage("zh-CN", searchZh);
  i18n.global.mergeLocaleMessage("en-US", searchEn);
}

async function typeQuery(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  value: string,
): Promise<void> {
  const input = wrapper.get("input");
  await input.setValue(value);
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();
}

async function openResults(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
): Promise<void> {
  await wrapper.get("input").trigger("focus");
  await flushPromises();
}
