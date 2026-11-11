import { afterEach, expect, test } from "vitest";
import { createApiClient } from "./client";
import { readCsrfToken } from "./csrf";

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

function recordingFetch(status: number, body: string) {
  const requests: Request[] = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    requests.push(request);
    return new Response(body, {
      status,
      headers: {
        "content-type":
          status >= 400 ? "application/problem+json" : "application/json",
      },
    });
  };
  return { fetch: fetchImpl, requests };
}

test("readCsrfToken decodes XSRF-TOKEN and ignores other cookies", () => {
  expect(readCsrfToken("theme=dark; XSRF-TOKEN=a%2Fb; other=1")).toBe("a/b");
  expect(readCsrfToken("XXSRF-TOKEN=no")).toBeUndefined();
  expect(readCsrfToken("")).toBeUndefined();
  expect(readCsrfToken("XSRF-TOKEN=")).toBeUndefined();
  expect(readCsrfToken("XSRF-TOKEN=%E0%A4%A")).toBeUndefined();
});

test("unsafe requests send the current CSRF token, then the rotated token", async () => {
  document.cookie = "XSRF-TOKEN=first";
  const { fetch: fetchImpl, requests } = recordingFetch(201, "{}");
  const client = createApiClient({
    baseUrl: "http://localhost",
    fetch: fetchImpl,
  });

  await client.POST("/api/projects", { body: { name: "One" } });
  document.cookie = `XSRF-TOKEN=${encodeURIComponent("second/token")}`;
  await client.POST("/api/projects", { body: { name: "Two" } });
  await client.PUT("/api/projects/{id}/settings/prefixes", {
    params: { path: { id: "p1" } },
    body: {},
  });
  await client.PATCH("/api/projects/{id}", {
    params: { path: { id: "p1" } },
    body: { name: "Renamed" },
  });
  await client.DELETE("/api/me/api-keys/{keyId}", {
    params: { path: { keyId: "k1" } },
  });

  expect(requests.map((request) => request.headers.get("X-XSRF-TOKEN"))).toEqual([
    "first",
    "second/token",
    "second/token",
    "second/token",
    "second/token",
  ]);
  expect(requests[0]?.credentials).toBe("same-origin");
});

test("GET does not send the CSRF header", async () => {
  document.cookie = "XSRF-TOKEN=first";
  const { fetch: fetchImpl, requests } = recordingFetch(200, "{}");
  const client = createApiClient({
    baseUrl: "http://localhost",
    fetch: fetchImpl,
  });

  await client.GET("/api/auth/me");

  expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBeNull();
});

test("401 calls onUnauthorized except for login and the current-user probe", async () => {
  const calls: number[] = [];
  const { fetch: fetchImpl } = recordingFetch(
    401,
    JSON.stringify({ status: 401, title: "Unauthorized" }),
  );
  const client = createApiClient({
    baseUrl: "http://localhost",
    fetch: fetchImpl,
    onUnauthorized: (response) => {
      calls.push(response.status);
    },
  });

  await client.GET("/api/projects");
  await client.GET("/api/auth/me");
  await client.POST("/api/auth/login", {
    body: { username: "ada", password: "secret" },
  });

  expect(calls).toEqual([401]);
});

test("onUnauthorized reads a clone and a throw still returns the 401 problem", async () => {
  const problem = { status: 401, title: "Unauthorized", detail: "sign in" };
  const { fetch: fetchImpl } = recordingFetch(401, JSON.stringify(problem));
  const client = createApiClient({
    baseUrl: "http://localhost",
    fetch: fetchImpl,
    onUnauthorized: async (response) => {
      const body = (await response.json()) as { detail?: string };
      if (body.detail !== "sign in") {
        throw new Error("missing detail");
      }
      throw new Error("redirect failed");
    },
  });

  const result = await client.GET("/api/projects");

  expect(result.response.status).toBe(401);
  expect(result.error).toMatchObject({ detail: "sign in" });
});
