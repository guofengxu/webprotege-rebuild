import type { ProblemDetail } from "@wp/api-types";
import type { ComposerTranslation } from "vue-i18n";

const ERROR_CODES = new Set<string>([
  "VALIDATION_FAILED",
  "PERMISSION_DENIED",
  "NOT_FOUND",
  "CONFLICT",
  "PROJECT_TRASHED",
  "FRAME_CHANGED",
  "LAST_MANAGER",
  "LAST_SYSTEM_ADMIN",
  "UPLOAD_TOO_LARGE",
  "ONTOLOGY_PARSE_FAILED",
  "PASSWORD_RESET_REQUIRED",
  "CONCURRENT_APPEND",
  "INTERNAL_ERROR",
]);

const HTTP_STATUSES = new Set<number>([401, 403, 404, 409, 413, 500]);

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;
  readonly problem?: ProblemDetail;

  constructor(status: number, problem?: ProblemDetail) {
    const detail = textValue(problem?.detail);
    super(detail ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = textValue(problem?.code);
    this.detail = detail;
    this.problem = problem;
  }
}

export function unwrap<T>(result: {
  data?: T;
  error?: unknown;
  response: Response;
}): T {
  if (result.error !== undefined || !result.response.ok) {
    throw toApiError(result.response, result.error);
  }
  return result.data as T;
}

export function problemMessage(error: unknown, t: ComposerTranslation): string {
  if (!(error instanceof ApiError)) {
    return error instanceof TypeError ? t("errors.network") : t("errors.unknown");
  }
  const message = lookupMessage(error, t);
  if (error.detail === undefined) {
    return message;
  }
  return `${message} (${error.detail})`;
}

function toApiError(response: Response, error: unknown): ApiError {
  return new ApiError(response.status, asProblemDetail(error));
}

function asProblemDetail(error: unknown): ProblemDetail | undefined {
  if (typeof error !== "object" || error === null || Array.isArray(error)) {
    return undefined;
  }
  const record = error as Record<string, unknown>;
  const code = textValue(record["code"]);
  const detail = textValue(record["detail"]);
  const title = textValue(record["title"]);
  const type = textValue(record["type"]);
  const instance = textValue(record["instance"]);
  const status = typeof record["status"] === "number" ? record["status"] : undefined;
  if (
    code === undefined &&
    detail === undefined &&
    title === undefined &&
    type === undefined &&
    instance === undefined &&
    status === undefined
  ) {
    return undefined;
  }
  return { code, detail, title, type, instance, status };
}

function textValue(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

function lookupMessage(error: ApiError, t: ComposerTranslation): string {
  if (error.code !== undefined && ERROR_CODES.has(error.code)) {
    return t(`errors.${error.code}`);
  }
  if (HTTP_STATUSES.has(error.status)) {
    return t(`errors.http.${error.status}`);
  }
  return t("errors.unknown");
}
