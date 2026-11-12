import { expect, test } from "vitest";
import { createApiClient } from "@/shared/api/client";
import { ApiError } from "@/shared/api/problem";
import { createMockFetch } from "@/shared/testing/mockFetch";
import { downloadProject } from "./downloadProject";

test("sends format and revision query parameters", async () => {
  const { fetch, requests } = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 200,
      body: "ontology",
      headers: {
        "content-type": "text/turtle",
        "content-disposition": 'attachment; filename="demo-r2.ttl"',
      },
    }),
  });
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });

  const result = await downloadProject(client, "proj-1", {
    format: "ttl",
    revision: 2,
  });

  expect(result.fileName).toBe("demo-r2.ttl");
  expect(await result.blob.text()).toBe("ontology");
  expect(requests).toHaveLength(1);
  expect(requests[0]?.path).toBe("/api/projects/proj-1/download");
  expect(requests[0]?.query.get("format")).toBe("ttl");
  expect(requests[0]?.query.get("revision")).toBe("2");
});

test("omits revision when not provided", async () => {
  const { fetch, requests } = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 200,
      body: "owl",
      headers: {
        "content-disposition": 'attachment; filename="proj-r9.owl"',
      },
    }),
  });
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });

  await downloadProject(client, "proj-1", { format: "owl" });

  expect(requests[0]?.query.get("format")).toBe("owl");
  expect(requests[0]?.query.has("revision")).toBe(false);
});

test("parses UTF-8 filename* from Content-Disposition", async () => {
  const { fetch } = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 200,
      body: "ttl",
      headers: {
        "content-disposition":
          "attachment; filename*=UTF-8''%E4%BB%93%E5%BA%93-r3.ttl",
      },
    }),
  });
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });

  const result = await downloadProject(client, "proj-1", { format: "ttl" });

  expect(result.fileName).toBe("仓库-r3.ttl");
});

test("falls back to projectId.extension when Content-Disposition is missing", async () => {
  const { fetch } = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 200,
      body: "ofn",
    }),
  });
  const client = createApiClient({ fetch, baseUrl: "http://localhost" });

  const result = await downloadProject(client, "abc", { format: "ofn" });

  expect(result.fileName).toBe("abc.ofn");
});

test("404 and 403 throw ApiError with status", async () => {
  const missing = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 404,
      problem: {
        status: 404,
        code: "NOT_FOUND",
        detail: "revision missing",
      },
    }),
  });
  const missingClient = createApiClient({
    fetch: missing.fetch,
    baseUrl: "http://localhost",
  });
  await expect(
    downloadProject(missingClient, "p1", { format: "ttl", revision: 99 }),
  ).rejects.toMatchObject({
    name: "ApiError",
    status: 404,
    code: "NOT_FOUND",
  } satisfies Partial<ApiError>);

  const forbidden = createMockFetch({
    "GET /api/projects/{id}/download": () => ({
      status: 403,
      problem: {
        status: 403,
        code: "PERMISSION_DENIED",
        detail: "no download",
      },
    }),
  });
  const forbiddenClient = createApiClient({
    fetch: forbidden.fetch,
    baseUrl: "http://localhost",
  });
  await expect(
    downloadProject(forbiddenClient, "p1", { format: "owl" }),
  ).rejects.toMatchObject({
    name: "ApiError",
    status: 403,
    code: "PERMISSION_DENIED",
  } satisfies Partial<ApiError>);
});
