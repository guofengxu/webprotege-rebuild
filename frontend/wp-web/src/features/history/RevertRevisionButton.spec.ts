import { flushPromises } from "@vue/test-utils";
import { ElMessage } from "element-plus";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import type { MockResponse } from "@/shared/testing/mockFetch";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { REVISION_3 } from "./fixtures";
import RevertRevisionButton from "./RevertRevisionButton.vue";

const VIEW_ACTIONS = [
  "VIEW_PROJECT",
  "VIEW_CHANGES",
  "DOWNLOAD_PROJECT",
] as const;

const EDIT_ACTIONS = [
  ...VIEW_ACTIONS,
  "EDIT_ONTOLOGY",
  "EDIT_ONTOLOGY_ANNOTATIONS",
  "CREATE_CLASS",
  "DELETE_CLASS",
  "CREATE_PROPERTY",
  "DELETE_PROPERTY",
  "CREATE_INDIVIDUAL",
  "DELETE_INDIVIDUAL",
  "REVERT_CHANGES",
] as const;

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("confirm stays disabled until the typed revision number matches", async () => {
  const { wrapper } = await mountButton();
  await flushPromises();

  await openDialog(wrapper);
  const confirm = findConfirmButton();
  expect(confirm).not.toBeNull();
  expect(confirm!.disabled).toBe(true);

  const input = findDialogInput();
  expect(input).not.toBeNull();
  setInputValue(input!, "2");
  await flushPromises();
  expect(findConfirmButton()!.disabled).toBe(true);

  setInputValue(input!, "3");
  await flushPromises();
  expect(findConfirmButton()!.disabled).toBe(false);
  wrapper.unmount();
});

test("confirm calls revertRevision and invalidates project queries", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const successSpy = vi
    .spyOn(ElMessage, "success")
    .mockImplementation(() => ({}) as never);
  const { wrapper, requests, queryClient } = await mountButton();
  await flushPromises();

  queryClient.setQueryData(queryKeys.revisions("p1", 0, 50), { revisions: [] });
  queryClient.setQueryData(queryKeys.projectDetails("p1"), { projectId: "p1" });

  await openDialog(wrapper);
  setInputValue(findDialogInput()!, "3");
  await flushPromises();
  findConfirmButton()!.click();
  await flushPromises();

  const posted = requests.find(
    (entry) =>
      entry.method === "POST" &&
      entry.path === "/api/projects/p1/revisions/3/revert",
  );
  expect(posted).toBeDefined();
  expect(posted!.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(
    queryClient.getQueryState(queryKeys.revisions("p1", 0, 50))?.isInvalidated,
  ).toBe(true);
  expect(
    queryClient.getQueryState(queryKeys.projectDetails("p1"))?.isInvalidated,
  ).toBe(true);
  expect(successSpy).toHaveBeenCalled();
  wrapper.unmount();
});

test("409 shows revert-specific conflict copy", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper } = await mountButton({
    "POST /api/projects/{id}/revisions/{rev}/revert": () => ({
      status: 409,
      problem: {
        code: "CONFLICT",
        detail: "cannot revert",
        title: "Conflict",
        status: 409,
      },
    }),
  });
  await flushPromises();

  await openDialog(wrapper);
  setInputValue(findDialogInput()!, "3");
  await flushPromises();
  findConfirmButton()!.click();
  await flushPromises();

  expect(document.body.textContent ?? "").toContain("无法回滚该修订");
  wrapper.unmount();
});

test("403 shows revert-forbidden copy", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper } = await mountButton({
    "POST /api/projects/{id}/revisions/{rev}/revert": () => ({
      status: 403,
      problem: {
        code: "PERMISSION_DENIED",
        detail: "forbidden",
        title: "Forbidden",
        status: 403,
      },
    }),
  });
  await flushPromises();

  await openDialog(wrapper);
  setInputValue(findDialogInput()!, "3");
  await flushPromises();
  findConfirmButton()!.click();
  await flushPromises();

  expect(document.body.textContent ?? "").toContain("没有回滚权限");
  wrapper.unmount();
});

test("CAN_VIEW hides the revert button", async () => {
  const { wrapper } = await mountButton({
    "GET /api/projects/{id}/permissions/me": () => ({
      json: {
        projectId: "p1",
        role: "CAN_VIEW",
        systemAdmin: false,
        actions: [...VIEW_ACTIONS],
      },
    }),
  });
  await flushPromises();

  expect(findRevertTrigger(wrapper)).toBeUndefined();
  wrapper.unmount();
});

test("CAN_EDIT shows the revert button", async () => {
  const { wrapper } = await mountButton();
  await flushPromises();

  expect(findRevertTrigger(wrapper)).toBeDefined();
  wrapper.unmount();
});

test("pending permissions disable the revert button", async () => {
  let resolveResponse: ((value: MockResponse) => void) | undefined;
  const { wrapper } = await mountButton({
    "GET /api/projects/{id}/permissions/me": () =>
      new Promise<MockResponse>((resolve) => {
        resolveResponse = resolve;
      }),
  });

  const trigger = findRevertTrigger(wrapper);
  expect(trigger).toBeDefined();
  expect(trigger!.attributes("disabled")).toBeDefined();

  for (let attempt = 0; attempt < 20 && resolveResponse === undefined; attempt += 1) {
    await flushPromises();
  }
  resolveResponse?.({
    json: {
      projectId: "p1",
      role: "CAN_EDIT",
      systemAdmin: false,
      actions: [...EDIT_ACTIONS],
    },
  });
  await flushPromises();
  wrapper.unmount();
});

test("permissions 500 still shows the revert button", async () => {
  const { wrapper } = await mountButton({
    "GET /api/projects/{id}/permissions/me": () => ({
      status: 500,
      problem: { status: 500, code: "INTERNAL_ERROR", title: "Error" },
    }),
  });
  await flushPromises();

  const trigger = findRevertTrigger(wrapper);
  expect(trigger).toBeDefined();
  expect(trigger!.attributes("disabled")).toBeUndefined();
  wrapper.unmount();
});

async function mountButton(
  handlers: Record<string, (req: unknown) => unknown> = {},
) {
  const Host = defineComponent({
    name: "RevertHost",
    setup() {
      return () =>
        h(RevertRevisionButton, {
          projectId: "p1",
          revision: REVISION_3,
        });
    },
  });
  return renderWithProviders(Host, {
    route: "/projects/p1/history",
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: {
          projectId: "p1",
          role: "CAN_EDIT",
          systemAdmin: false,
          actions: [...EDIT_ACTIONS],
        },
      }),
      "POST /api/projects/{id}/revisions/{rev}/revert": () => ({
        json: { revision: 4 },
      }),
      ...handlers,
    },
  });
}

async function openDialog(
  wrapper: Awaited<ReturnType<typeof mountButton>>["wrapper"],
): Promise<void> {
  const trigger = findRevertTrigger(wrapper);
  expect(trigger).toBeDefined();
  await trigger!.trigger("click");
  await flushPromises();
}

function findRevertTrigger(
  wrapper: Awaited<ReturnType<typeof mountButton>>["wrapper"],
) {
  return wrapper
    .findAll("button")
    .find((button) => button.text() === "回滚");
}

function findDialogInput(): HTMLInputElement | null {
  return document.body.querySelector(".el-dialog input");
}

function findConfirmButton(): HTMLButtonElement | null {
  const buttons = Array.from(
    document.body.querySelectorAll<HTMLButtonElement>(".el-dialog button"),
  );
  return buttons.find((button) => button.textContent?.trim() === "确认回滚") ?? null;
}

function setInputValue(input: HTMLInputElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event("input", { bubbles: true }));
}
