/**
 * Upload uses XMLHttpRequest instead of openapi-fetch so the UI can show
 * upload progress via `xhr.upload.onprogress`. Request/response types still
 * come from `@wp/api-types`.
 */
import type { paths, ProblemDetail, ProjectResponse } from "@wp/api-types";
import { readCsrfToken } from "@/shared/api/csrf";
import { ApiError } from "@/shared/api/problem";

type UploadQuery = NonNullable<
  paths["/api/projects/upload"]["post"]["parameters"]["query"]
>;

export type UploadProjectInput = {
  file: File;
  name: string;
  description?: string;
};

export class UploadAbortedError extends Error {
  constructor(message = "Upload aborted") {
    super(message);
    this.name = "UploadAbortedError";
  }
}

export class UploadNetworkError extends Error {
  constructor(message = "Upload network error") {
    super(message);
    this.name = "UploadNetworkError";
  }
}

export function uploadProject(
  input: UploadProjectInput,
  onProgress: (ratio: number) => void,
  signal?: AbortSignal,
): Promise<ProjectResponse> {
  const query: UploadQuery = { name: input.name };
  if (input.description !== undefined && input.description.length > 0) {
    query.description = input.description;
  }
  const url = buildUploadUrl(query);
  const form = new FormData();
  form.set("file", input.file);

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    let settled = false;

    const fail = (error: Error): void => {
      if (settled) {
        return;
      }
      settled = true;
      reject(error);
    };

    const succeed = (project: ProjectResponse): void => {
      if (settled) {
        return;
      }
      settled = true;
      resolve(project);
    };

    const onAbortSignal = (): void => {
      xhr.abort();
    };

    if (signal !== undefined) {
      if (signal.aborted) {
        fail(new UploadAbortedError());
        return;
      }
      signal.addEventListener("abort", onAbortSignal, { once: true });
    }

    xhr.open("POST", url);
    const csrf = readCsrfToken();
    if (csrf !== undefined) {
      xhr.setRequestHeader("X-XSRF-TOKEN", csrf);
    }

    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable || event.total <= 0) {
        return;
      }
      onProgress(event.loaded / event.total);
    };

    xhr.onload = () => {
      if (signal !== undefined) {
        signal.removeEventListener("abort", onAbortSignal);
      }
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          succeed(JSON.parse(xhr.responseText) as ProjectResponse);
        } catch {
          fail(new ApiError(xhr.status));
        }
        return;
      }
      fail(new ApiError(xhr.status, parseProblem(xhr.responseText)));
    };

    xhr.onerror = () => {
      if (signal !== undefined) {
        signal.removeEventListener("abort", onAbortSignal);
      }
      fail(new UploadNetworkError());
    };

    xhr.onabort = () => {
      if (signal !== undefined) {
        signal.removeEventListener("abort", onAbortSignal);
      }
      fail(new UploadAbortedError());
    };

    xhr.send(form);
  });
}

function buildUploadUrl(query: UploadQuery): string {
  const params = new URLSearchParams();
  params.set("name", query.name);
  if (query.description !== undefined) {
    params.set("description", query.description);
  }
  return `/api/projects/upload?${params.toString()}`;
}

function parseProblem(raw: string): ProblemDetail | undefined {
  if (raw.trim().length === 0) {
    return undefined;
  }
  try {
    const value = JSON.parse(raw) as unknown;
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
      return undefined;
    }
    const record = value as Record<string, unknown>;
    return {
      code: textValue(record["code"]),
      detail: textValue(record["detail"]),
      title: textValue(record["title"]),
      type: textValue(record["type"]),
      instance: textValue(record["instance"]),
      status: typeof record["status"] === "number" ? record["status"] : undefined,
    };
  } catch {
    return undefined;
  }
}

function textValue(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}
