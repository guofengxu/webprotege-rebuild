import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import FrameSaveBar from "./FrameSaveBar.vue";
import framesEn from "./locales/en-US";
import framesZh from "./locales/zh-CN";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
});

test("save is disabled when clean or invalid", async () => {
  const clean = await mountBar({
    isDirty: false,
    isSaving: false,
    conflict: false,
    serverChanged: false,
  });
  expect(saveButton(clean.wrapper).attributes("disabled")).toBeDefined();
  clean.wrapper.unmount();

  const invalid = await mountBar({
    isDirty: true,
    isSaving: false,
    conflict: false,
    serverChanged: false,
    invalid: true,
  });
  expect(saveButton(invalid.wrapper).attributes("disabled")).toBeDefined();
  expect(invalid.wrapper.text()).toContain("有未填完的行");
  invalid.wrapper.unmount();
});

test("denied hides save actions and shows read-only hint", async () => {
  const { wrapper } = await mountBar({
    isDirty: true,
    isSaving: false,
    conflict: false,
    serverChanged: false,
    readOnly: true,
    editPermission: "denied",
  });
  expect(wrapper.find('[aria-label="保存"]').exists()).toBe(false);
  expect(wrapper.find('[aria-label="撤销修改"]').exists()).toBe(false);
  expect(wrapper.text()).toContain("只读：你在本项目中没有编辑权限");
  wrapper.unmount();
});

test("pending hides save actions and does not show read-only hint", async () => {
  const { wrapper } = await mountBar({
    isDirty: true,
    isSaving: false,
    conflict: false,
    serverChanged: false,
    readOnly: true,
    editPermission: "pending",
  });
  expect(wrapper.find('[aria-label="保存"]').exists()).toBe(false);
  expect(wrapper.text()).not.toContain("只读：你在本项目中没有编辑权限");
  wrapper.unmount();
});

test("conflict shows alert and confirmed reload emits reload", async () => {
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
  const { wrapper } = await mountBar({
    isDirty: true,
    isSaving: false,
    conflict: true,
    serverChanged: false,
  });
  await flushPromises();

  expect(wrapper.text()).toContain("该实体已被其他人修改");
  const alert = wrapper.get('[role="alert"]');
  expect(alert.text()).toContain("该实体已被其他人修改");

  const reload = wrapper
    .findAll("button")
    .find((button) => button.text().includes("刷新"));
  expect(reload).toBeDefined();
  await reload!.trigger("click");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  expect(wrapper.findComponent(FrameSaveBar).emitted("reload")).toEqual([[]]);
  wrapper.unmount();
});

async function mountBar(props: {
  isDirty: boolean;
  isSaving: boolean;
  conflict: boolean;
  serverChanged: boolean;
  invalid?: boolean;
  readOnly?: boolean;
  editPermission?: "allowed" | "denied" | "pending";
}) {
  const rendered = await renderWithProviders(FrameSaveBar, { props });
  mergeFramesLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function saveButton(wrapper: VueWrapper) {
  return wrapper.get('[aria-label="保存"]');
}

function mergeFramesLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", framesZh);
  i18n.global.mergeLocaleMessage("en-US", framesEn);
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
