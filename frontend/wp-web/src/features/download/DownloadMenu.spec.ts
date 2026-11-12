import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessage } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import { ApiError } from "@/shared/api/problem";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import DownloadMenu from "./DownloadMenu.vue";
import { downloadProject } from "./downloadProject";
import downloadEn from "./locales/en-US";
import downloadZh from "./locales/zh-CN";
import { saveBlob } from "./saveBlob";

vi.mock("./downloadProject", () => ({
  downloadProject: vi.fn(),
}));

vi.mock("./saveBlob", () => ({
  saveBlob: vi.fn(),
}));

afterEach(() => {
  vi.clearAllMocks();
  localStorage.removeItem("wp.locale");
  document.body
    .querySelectorAll(".el-popper, .el-dropdown__popper")
    .forEach((node) => node.remove());
});

test("selecting Turtle downloads and saves the blob", async () => {
  const blob = new Blob(["ttl"], { type: "text/turtle" });
  vi.mocked(downloadProject).mockResolvedValue({
    blob,
    fileName: "demo-r1.ttl",
  });
  const { wrapper } = await mountMenu({ projectId: "p1" });
  await flushPromises();

  await openMenu(wrapper);
  await clickFormat("Turtle (.ttl)");
  await flushPromises();

  expect(downloadProject).toHaveBeenCalledWith(
    expect.anything(),
    "p1",
    { format: "ttl" },
  );
  expect(saveBlob).toHaveBeenCalledWith(blob, "demo-r1.ttl");
  wrapper.unmount();
});

test("passes revision when the prop is set", async () => {
  vi.mocked(downloadProject).mockResolvedValue({
    blob: new Blob(["x"]),
    fileName: "demo-r7.owl",
  });
  const { wrapper } = await mountMenu({ projectId: "p1", revision: 7 });
  await flushPromises();

  await openMenu(wrapper);
  await clickFormat("RDF/XML (.owl)");
  await flushPromises();

  expect(downloadProject).toHaveBeenCalledWith(
    expect.anything(),
    "p1",
    { format: "owl", revision: 7 },
  );
  wrapper.unmount();
});

test("shows an error message when download fails", async () => {
  const errorSpy = vi
    .spyOn(ElMessage, "error")
    .mockImplementation(() => ({}) as never);
  vi.mocked(downloadProject).mockRejectedValue(
    new ApiError(403, {
      status: 403,
      code: "PERMISSION_DENIED",
      detail: "denied",
    }),
  );
  const { wrapper } = await mountMenu({ projectId: "p1" });
  await flushPromises();

  await openMenu(wrapper);
  await clickFormat("Turtle (.ttl)");
  await flushPromises();

  expect(errorSpy).toHaveBeenCalled();
  const message = String(errorSpy.mock.calls[0]?.[0] ?? "");
  expect(message.length).toBeGreaterThan(0);
  expect(saveBlob).not.toHaveBeenCalled();
  wrapper.unmount();
});

async function mountMenu(props: {
  projectId: string;
  revision?: number;
}): Promise<{ wrapper: VueWrapper }> {
  const rendered = await renderWithProviders(DownloadMenu, { props });
  mergeDownloadLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeDownloadLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", downloadZh);
  i18n.global.mergeLocaleMessage("en-US", downloadEn);
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

async function openMenu(wrapper: VueWrapper): Promise<void> {
  const trigger = wrapper.get('[aria-label="下载"]');
  await trigger.trigger("click");
  await flushPromises();
}

async function clickFormat(label: string): Promise<void> {
  const item = findMenuItem(label);
  expect(item).not.toBeNull();
  item!.click();
  await flushPromises();
}

function findMenuItem(label: string): HTMLElement | null {
  const items = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-dropdown-menu__item"),
  );
  return items.find((entry) => entry.textContent?.trim() === label) ?? null;
}
