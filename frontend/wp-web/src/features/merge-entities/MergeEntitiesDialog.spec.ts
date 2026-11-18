import { flushPromises, DOMWrapper, type VueWrapper } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { defineComponent, h, ref } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import MergeEntitiesDialog from "./MergeEntitiesDialog.vue";
import mergeEn from "./locales/en-US";
import mergeZh from "./locales/zh-CN";

const SOURCE_IRI = "http://ex.org/Source";
const TARGET_IRI = "http://ex.org/Target";

const TARGET_NODE: EntityNode = {
  iri: TARGET_IRI,
  entityType: "CLASS",
  displayName: "Target",
  hasChildren: false,
  deprecated: false,
};

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

test("merge submit stays disabled until a target is selected", async () => {
  const { wrapper, open } = await renderDialog();
  await flushPromises();
  await reopen(open);

  expect(findSubmitButton()?.hasAttribute("disabled")).toBe(true);

  await selectTarget(wrapper);
  await flushPromises();

  expect(findSubmitButton()?.hasAttribute("disabled")).toBe(false);
  wrapper.unmount();
});

test("submits sources target treatment and description", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, open, requests, router } = await renderDialog({
    "POST /api/projects/{id}/entities/merge": () => ({
      json: {
        target: TARGET_NODE,
        revision: 7,
      },
    }),
  });
  await flushPromises();
  await reopen(open);

  await selectTarget(wrapper);
  await setDescription("merge note");
  await submitForm();
  await flushPromises();

  const merge = requests.find(
    (request) =>
      request.method === "POST" &&
      request.path === "/api/projects/p1/entities/merge",
  );
  expect(merge?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(merge?.body).toEqual({
    sources: [SOURCE_IRI],
    target: TARGET_IRI,
    treatment: "DEPRECATE_MERGED_ENTITY",
    description: "merge note",
  });
  expect(router.currentRoute.value.query.entity).toBe(TARGET_IRI);
  wrapper.unmount();
});

test("INVALID_MERGE shows the reason-specific message", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, open } = await renderDialog({
    "POST /api/projects/{id}/entities/merge": () => ({
      status: 400,
      problem: {
        status: 400,
        code: "INVALID_MERGE",
        reason: "TYPE_MISMATCH",
        title: "Bad Request",
      },
    }),
  });
  await flushPromises();
  await reopen(open);

  await selectTarget(wrapper);
  await submitForm();
  await flushPromises();

  expect(document.body.textContent ?? "").toContain("源与目标的实体类型不一致");
  wrapper.unmount();
});

test("ENTITY_NOT_FOUND asks the user to refresh", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, open } = await renderDialog({
    "POST /api/projects/{id}/entities/merge": () => ({
      status: 404,
      problem: {
        status: 404,
        code: "ENTITY_NOT_FOUND",
        title: "Not Found",
      },
    }),
  });
  await flushPromises();
  await reopen(open);

  await selectTarget(wrapper);
  await submitForm();
  await flushPromises();

  expect(document.body.textContent ?? "").toContain("实体已不存在，请刷新");
  wrapper.unmount();
});

async function renderDialog(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const open = ref(true);
  const Host = defineComponent({
    name: "MergeEntitiesDialogHost",
    setup() {
      return () =>
        h(MergeEntitiesDialog, {
          projectId: "p1",
          kind: "class",
          sourceIri: SOURCE_IRI,
          modelValue: open.value,
          "onUpdate:modelValue": (value: boolean) => {
            open.value = value;
          },
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    route: `/projects/p1/class?entity=${encodeURIComponent(SOURCE_IRI)}`,
    handlers,
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
  return { ...rendered, open };
}

async function reopen(open: { value: boolean }): Promise<void> {
  open.value = false;
  await flushPromises();
  open.value = true;
  await flushPromises();
}

async function selectTarget(wrapper: VueWrapper): Promise<void> {
  const picker = wrapper.findComponent(EntityPicker);
  expect(picker.exists()).toBe(true);
  picker.vm.$emit("update:modelValue", TARGET_NODE);
  await flushPromises();
}

async function setDescription(value: string): Promise<void> {
  const input = document.body.querySelector<HTMLInputElement | HTMLTextAreaElement>(
    'textarea[name="description"], input[name="description"]',
  );
  expect(input).not.toBeNull();
  await new DOMWrapper(input!).setValue(value);
}

async function submitForm(): Promise<void> {
  const form = document.body.querySelector("form");
  expect(form).not.toBeNull();
  await new DOMWrapper(form!).trigger("submit");
}

function findSubmitButton(): HTMLButtonElement {
  const button = document.body.querySelector<HTMLButtonElement>(
    '[data-testid="merge-entities-submit"]',
  );
  expect(button).not.toBeNull();
  return button!;
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
