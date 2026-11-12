import { afterEach, expect, test, vi } from "vitest";
import { ApiError } from "@/shared/api/problem";
import {
  UploadAbortedError,
  UploadNetworkError,
  uploadProject,
} from "./uploadProject";

type FakeXhr = {
  status: number;
  responseText: string;
  upload: { onprogress: ((event: ProgressEvent) => void) | null };
  onload: (() => void) | null;
  onerror: (() => void) | null;
  onabort: (() => void) | null;
  open: ReturnType<typeof vi.fn>;
  setRequestHeader: ReturnType<typeof vi.fn>;
  send: ReturnType<typeof vi.fn>;
  abort: ReturnType<typeof vi.fn>;
};

let lastXhr: FakeXhr | undefined;
const OriginalXHR = globalThis.XMLHttpRequest;

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  globalThis.XMLHttpRequest = OriginalXHR;
  lastXhr = undefined;
  vi.restoreAllMocks();
});

test("reports upload progress and sends CSRF plus query parameters", async () => {
  document.cookie = "XSRF-TOKEN=csrf%2Ftoken";
  installFakeXhr();
  const ratios: number[] = [];
  const file = new File(["ontology"], "demo.owl", { type: "application/rdf+xml" });

  const pending = uploadProject(
    { file, name: "Demo", description: "from file" },
    (ratio) => {
      ratios.push(ratio);
    },
  );

  expect(lastXhr).toBeDefined();
  const xhr = lastXhr!;
  expect(xhr.open).toHaveBeenCalledWith(
    "POST",
    expect.stringMatching(/\/api\/projects\/upload\?/),
  );
  const openedUrl = String(xhr.open.mock.calls[0]?.[1]);
  const url = new URL(openedUrl, "http://localhost");
  expect(url.pathname).toBe("/api/projects/upload");
  expect(url.searchParams.get("name")).toBe("Demo");
  expect(url.searchParams.get("description")).toBe("from file");
  expect(xhr.setRequestHeader).toHaveBeenCalledWith("X-XSRF-TOKEN", "csrf/token");

  xhr.upload.onprogress?.(
    new ProgressEvent("progress", { lengthComputable: true, loaded: 40, total: 100 }),
  );
  xhr.status = 201;
  xhr.responseText = JSON.stringify({
    projectId: "p1",
    displayName: "Demo",
  });
  xhr.onload?.();

  await expect(pending).resolves.toEqual({
    projectId: "p1",
    displayName: "Demo",
  });
  expect(ratios).toEqual([0.4]);
  expect(xhr.send).toHaveBeenCalledOnce();
  const body = xhr.send.mock.calls[0]?.[0];
  expect(body).toBeInstanceOf(FormData);
  expect((body as FormData).get("file")).toBe(file);
});

test("413 and 400 responses become ApiError with codes", async () => {
  installFakeXhr();
  const file = new File(["x"], "big.owl");

  const tooLarge = uploadProject({ file, name: "Big" }, () => undefined);
  lastXhr!.status = 413;
  lastXhr!.responseText = JSON.stringify({
    status: 413,
    code: "UPLOAD_TOO_LARGE",
    detail: "200MB",
  });
  lastXhr!.onload?.();
  await expect(tooLarge).rejects.toMatchObject({
    name: "ApiError",
    status: 413,
    code: "UPLOAD_TOO_LARGE",
  } satisfies Partial<ApiError>);

  installFakeXhr();
  const parseFailed = uploadProject({ file, name: "Broken" }, () => undefined);
  lastXhr!.status = 400;
  lastXhr!.responseText = JSON.stringify({
    status: 400,
    code: "ONTOLOGY_PARSE_FAILED",
    detail: "line 12: unexpected token",
  });
  lastXhr!.onload?.();
  await expect(parseFailed).rejects.toMatchObject({
    name: "ApiError",
    status: 400,
    code: "ONTOLOGY_PARSE_FAILED",
    detail: "line 12: unexpected token",
  } satisfies Partial<ApiError>);
});

test("abort throws UploadAbortedError and network failure throws UploadNetworkError", async () => {
  installFakeXhr();
  const file = new File(["x"], "demo.ttl");
  const controller = new AbortController();
  const pending = uploadProject({ file, name: "Demo" }, () => undefined, controller.signal);
  controller.abort();
  lastXhr!.onabort?.();
  await expect(pending).rejects.toBeInstanceOf(UploadAbortedError);

  installFakeXhr();
  const network = uploadProject({ file, name: "Demo" }, () => undefined);
  lastXhr!.onerror?.();
  await expect(network).rejects.toBeInstanceOf(UploadNetworkError);
});

function installFakeXhr(): void {
  globalThis.XMLHttpRequest = vi.fn(function FakeXMLHttpRequest(this: FakeXhr) {
    this.status = 0;
    this.responseText = "";
    this.upload = { onprogress: null };
    this.onload = null;
    this.onerror = null;
    this.onabort = null;
    this.open = vi.fn();
    this.setRequestHeader = vi.fn();
    this.send = vi.fn();
    this.abort = vi.fn();
    // Expose the constructed instance to assertions; XMLHttpRequest must be new-able.
    // eslint-disable-next-line @typescript-eslint/no-this-alias -- test fake constructor
    lastXhr = this;
  }) as unknown as typeof XMLHttpRequest;
}
