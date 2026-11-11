import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import LoginPage from "./LoginPage.vue";

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

const signedIn = {
  userId: "u1",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: [] as string[],
};

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

test("a wrong password shows the 401 message", async () => {
  const { wrapper, requests } = await renderLogin("/login", {
    "POST /api/auth/login": () => ({
      status: 401,
      problem: { status: 401, title: "Unauthorized" },
    }),
  });

  await submit(wrapper, "ada", "wrong");

  expect(wrapper.text()).toContain("登录已失效，请重新登录");
  expect(requests.filter((request) => request.path === "/api/auth/login")).toHaveLength(1);
  wrapper.unmount();
});

test("PASSWORD_RESET_REQUIRED shows the administrator reset prompt", async () => {
  const { wrapper } = await renderLogin("/login", {
    "POST /api/auth/login": () => ({
      status: 401,
      problem: {
        status: 401,
        title: "Password reset required",
        code: "PASSWORD_RESET_REQUIRED",
        detail: "empty password hash",
      },
    }),
  });

  await submit(wrapper, "ada", "secret");

  expect(wrapper.text()).toContain("需要管理员重置密码");
  expect(wrapper.text()).not.toContain("需要先重置密码才能登录");
  expect(wrapper.text()).not.toContain("empty password hash");
  wrapper.unmount();
});

test("submit calls login with the CSRF header and returns to the redirect path", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests, router } = await renderLogin("/login?redirect=/projects", {
    "POST /api/auth/login": () => ({ json: signedIn }),
    "GET /api/auth/me": () => ({ json: signedIn }),
  });

  await submit(wrapper, "ada", "secret");

  const login = requests.find((request) => request.path === "/api/auth/login");
  expect(login?.method).toBe("POST");
  expect(login?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(login?.body).toEqual({ username: "ada", password: "secret" });
  expect(router.currentRoute.value.path).toBe("/projects");
  wrapper.unmount();
});

test("an external redirect is replaced with home", async () => {
  const { wrapper, router } = await renderLogin("/login?redirect=//evil.example", {
    "POST /api/auth/login": () => ({ json: signedIn }),
    "GET /api/auth/me": () => ({ json: signedIn }),
  });

  await submit(wrapper, "ada", "secret");

  expect(router.currentRoute.value.path).toBe("/");
  wrapper.unmount();
});

test("a network reason is shown before the user submits", async () => {
  const { wrapper, requests } = await renderLogin("/login?reason=network", {});

  expect(wrapper.text()).toContain("网络连接失败，请稍后重试");
  expect(requests).toHaveLength(0);
  wrapper.unmount();
});

test("empty fields are rejected without calling login", async () => {
  const { wrapper, requests } = await renderLogin("/login", {
    "POST /api/auth/login": () => ({ json: signedIn }),
  });

  await wrapper.get("form").trigger("submit");
  await flushPromises();

  expect(wrapper.text()).toContain("请输入用户名");
  expect(wrapper.text()).toContain("请输入密码");
  expect(requests).toHaveLength(0);
  wrapper.unmount();
});

test("pressing enter in the form submits the credentials", async () => {
  const { wrapper, requests } = await renderLogin("/login", {
    "POST /api/auth/login": () => ({
      status: 401,
      problem: { status: 401, title: "Unauthorized" },
    }),
  });

  await fill(wrapper, "ada", "wrong");
  await wrapper.get("form").trigger("keyup", { key: "Enter" });
  await flushPromises();

  expect(requests.filter((request) => request.path === "/api/auth/login")).toHaveLength(1);
  wrapper.unmount();
});

async function renderLogin(
  route: string,
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never,
) {
  return renderWithProviders(LoginPage, {
    route,
    handlers,
    routes: [
      { path: "/login", component: LoginPage },
      { path: "/", component: StubPage },
      { path: "/projects", component: StubPage },
    ],
  });
}

async function submit(
  wrapper: Awaited<ReturnType<typeof renderLogin>>["wrapper"],
  username: string,
  password: string,
) {
  await fill(wrapper, username, password);
  await wrapper.get("form").trigger("submit");
  await flushPromises();
}

async function fill(
  wrapper: Awaited<ReturnType<typeof renderLogin>>["wrapper"],
  username: string,
  password: string,
) {
  const inputs = wrapper.findAll("input");
  await inputs[0]?.setValue(username);
  await inputs[1]?.setValue(password);
}
