import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { ElMessage, ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import type { AuthUserResponse, MemberResponse } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import MembersTab from "./MembersTab.vue";
import membersEn from "./locales/en-US";
import membersZh from "./locales/zh-CN";

const PROJECT_ID = "p1";

const CURRENT_USER: AuthUserResponse = {
  userId: "ada",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: [],
};

const MEMBERS: MemberResponse[] = [
  { userId: "ada", role: "CAN_MANAGE" },
  { userId: "bob", role: "CAN_EDIT" },
];

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("renders the member list", async () => {
  const { wrapper } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({ json: MEMBERS }),
  });

  expect(wrapper.text()).toContain("ada");
  expect(wrapper.text()).toContain("bob");
  expect(wrapper.find("table").exists() || wrapper.find(".el-table").exists()).toBe(
    true,
  );
  wrapper.unmount();
});

test("adding a member sends PUT with {role}", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  let listed = [...MEMBERS];
  const { wrapper, requests } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({ json: listed }),
    "PUT /api/projects/{id}/members/{userId}": (req) => {
      const member: MemberResponse = {
        userId: req.params.userId,
        role: (req.body as { role?: string }).role,
      };
      listed = [...listed, member];
      return { json: member };
    },
  });

  await setAddUserId(wrapper, "carol");
  await wrapper.get("form.members-tab__add-row").trigger("submit");
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === `/api/projects/${PROJECT_ID}/members/carol`,
  );
  expect(put).toBeDefined();
  expect(put?.body).toEqual({ role: "CAN_VIEW" });
  expect(put?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  wrapper.unmount();
});

test("inline role change failure restores the previous role and shows LAST_MANAGER tip", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const errorSpy = vi
    .spyOn(ElMessage, "error")
    .mockImplementation(() => undefined as never);
  // Demoting the current user (ada) asks for an extra self-manage confirm first.
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  const { wrapper, requests } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({ json: MEMBERS }),
    "PUT /api/projects/{id}/members/{userId}": () => ({
      status: 409,
      problem: {
        status: 409,
        title: "Conflict",
        code: "LAST_MANAGER",
        detail: "Cannot remove the last manager",
      },
    }),
  });

  const adaSelect = findRoleSelectForUser(wrapper, "ada");
  expect(adaSelect).not.toBeNull();
  expect(selectValue(adaSelect!)).toBe("CAN_MANAGE");

  await setSelectValue(adaSelect!, "CAN_VIEW");
  await flushPromises();

  expect(
    requests.some(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === `/api/projects/${PROJECT_ID}/members/ada` &&
        (entry.body as { role?: string }).role === "CAN_VIEW",
    ),
  ).toBe(true);
  expect(selectValue(adaSelect!)).toBe("CAN_MANAGE");
  expect(errorSpy).toHaveBeenCalled();
  const messageArg = String(errorSpy.mock.calls[0]?.[0] ?? "");
  expect(messageArg).toMatch(/至少需要一位管理者/);
  wrapper.unmount();
});

test("remove asks for confirmation before DELETE", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  let listed = [...MEMBERS];
  const { wrapper, requests } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({ json: listed }),
    "DELETE /api/projects/{id}/members/{userId}": (req) => {
      listed = listed.filter((member) => member.userId !== req.params.userId);
      return { status: 204 };
    },
  });

  await clickRemoveForUser(wrapper, "bob");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  expect(
    requests.some(
      (entry) =>
        entry.method === "DELETE" &&
        entry.path === `/api/projects/${PROJECT_ID}/members/bob`,
    ),
  ).toBe(true);
  wrapper.unmount();
});

test("403 shows sharing-permission message instead of the table", async () => {
  const { wrapper } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({
      status: 403,
      problem: {
        status: 403,
        title: "Forbidden",
        code: "PERMISSION_DENIED",
      },
    }),
  });

  expect(wrapper.text()).toContain("需要共享权限才能查看成员");
  expect(wrapper.find(".el-table").exists()).toBe(false);
  wrapper.unmount();
});

test("adding an unknown user id shows 用户 ID 不存在", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountTab({
    "GET /api/auth/me": () => ({ json: CURRENT_USER }),
    "GET /api/projects/{id}/members": () => ({ json: MEMBERS }),
    "PUT /api/projects/{id}/members/{userId}": () => ({
      status: 404,
      problem: {
        status: 404,
        title: "Not Found",
        code: "NOT_FOUND",
        detail: "User not found",
      },
    }),
  });

  await setAddUserId(wrapper, "nobody");
  await wrapper.get("form.members-tab__add-row").trigger("submit");
  await flushPromises();

  expect(
    requests.some(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === `/api/projects/${PROJECT_ID}/members/nobody`,
    ),
  ).toBe(true);
  expect(wrapper.text()).toContain("用户 ID 不存在");
  wrapper.unmount();
});

async function mountTab(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  const rendered = await renderWithProviders(MembersTab, {
    props: { projectId: PROJECT_ID },
    handlers,
  });
  mergeMembersLocales(rendered.wrapper);
  await flushPromises();
  return rendered;
}

function mergeMembersLocales(wrapper: VueWrapper): void {
  const i18n = resolveI18n(wrapper);
  i18n.global.mergeLocaleMessage("zh-CN", membersZh);
  i18n.global.mergeLocaleMessage("en-US", membersEn);
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

async function setAddUserId(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  value: string,
): Promise<void> {
  const input = wrapper.find(
    'input[name="userId"], input[data-testid="add-user-id"]',
  );
  expect(input.exists()).toBe(true);
  await input.setValue(value);
  await input.trigger("input");
}

async function clickRemoveForUser(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  userId: string,
): Promise<void> {
  const button = wrapper.find(
    `button[data-testid="remove-member-${userId}"], [data-user-id="${userId}"] button`,
  );
  if (button.exists()) {
    await button.trigger("click");
    return;
  }
  const row = wrapper
    .findAll("tr")
    .find((entry) => entry.text().includes(userId));
  expect(row).toBeDefined();
  const remove = row!
    .findAll("button")
    .find((entry) => entry.text().includes("移除"));
  expect(remove).toBeDefined();
  await remove!.trigger("click");
}

function findRoleSelectForUser(
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"],
  userId: string,
): VueWrapper | null {
  const byTestId = wrapper.find(`[data-testid="member-role-${userId}"]`);
  if (byTestId.exists()) {
    const comp = wrapper
      .findAllComponents({ name: "ElSelect" })
      .find(
        (entry) =>
          (entry.element as HTMLElement).getAttribute("data-testid") ===
            `member-role-${userId}` ||
          entry.find(`[data-testid="member-role-${userId}"]`).exists(),
      );
    if (comp !== undefined) {
      return comp;
    }
  }
  const selects = wrapper.findAllComponents({ name: "ElSelect" });
  for (const select of selects) {
    const root = select.element as HTMLElement;
    if (root.getAttribute("data-testid") === `member-role-${userId}`) {
      return select;
    }
    if (root.closest(`[data-user-id="${userId}"]`) !== null) {
      return select;
    }
  }
  // Prefer the first table-row select (ada is first) when attrs are on the outer host.
  const rowSelect = selects.find((select) => {
    const host = select.element as HTMLElement;
    return host.closest("tr")?.textContent?.includes(userId) === true;
  });
  return rowSelect ?? null;
}

function selectValue(select: VueWrapper): string {
  const model = (select.props() as { modelValue?: string }).modelValue;
  if (typeof model === "string") {
    return model;
  }
  const native = select.find("input").element as HTMLInputElement;
  return native.value;
}

async function setSelectValue(select: VueWrapper, value: string): Promise<void> {
  await select.vm.$emit("change", value);
}
