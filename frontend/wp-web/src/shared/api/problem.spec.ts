import { expect, test } from "vitest";
import { createAppI18n } from "@/app/i18n";
import { ApiError, problemMessage, unwrap } from "./problem";

const ERROR_CODES = [
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
] as const;

const HTTP_STATUSES = [401, 403, 404, 409, 413, 500] as const;

function translate(locale: "zh-CN" | "en-US") {
  return createAppI18n(locale).global.t;
}

test("unwrap throws ApiError with the problem code for FRAME_CHANGED", () => {
  const problem = {
    status: 409,
    title: "Conflict",
    detail: "class frame changed",
    code: "FRAME_CHANGED",
  };
  const response = new Response(JSON.stringify(problem), { status: 409 });

  expect(() => unwrap({ error: problem, response })).toThrow(ApiError);
  try {
    unwrap({ error: problem, response });
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    if (!(error instanceof ApiError)) {
      return;
    }
    expect(error.status).toBe(409);
    expect(error.code).toBe("FRAME_CHANGED");
    expect(error.detail).toBe("class frame changed");
    expect(error.problem?.code).toBe("FRAME_CHANGED");
  }
});

test("unwrap turns a non-JSON 500 into ApiError without a code", () => {
  const response = new Response("boom", {
    status: 500,
    headers: { "content-type": "text/plain" },
  });

  expect(() => unwrap({ error: "boom", response })).toThrow(ApiError);
  try {
    unwrap({ error: "boom", response });
  } catch (error) {
    expect(error).toBeInstanceOf(ApiError);
    if (!(error instanceof ApiError)) {
      return;
    }
    expect(error.status).toBe(500);
    expect(error.code).toBeUndefined();
    expect(error.problem).toBeUndefined();
  }
});

test("unwrap returns data for a 2xx response without an error", () => {
  const data = { projectId: "p1" };
  const response = new Response(null, { status: 201 });
  expect(unwrap({ data, response })).toEqual(data);
});

test("problemMessage falls back from code to status to unknown, and appends detail", () => {
  const t = translate("zh-CN");
  const changed = new ApiError(409, {
    code: "FRAME_CHANGED",
    detail: "class frame changed",
  });
  const missing = new ApiError(404);
  const unexpected = new ApiError(418);

  expect(problemMessage(changed, t)).toBe(
    `${t("errors.FRAME_CHANGED")} (class frame changed)`,
  );
  expect(problemMessage(missing, t)).toBe(t("errors.http.404"));
  expect(problemMessage(unexpected, t)).toBe(t("errors.unknown"));
  expect(problemMessage(new TypeError("Failed to fetch"), t)).toBe(
    t("errors.network"),
  );
});

test("zh-CN and en-US define every error code and http status", () => {
  const zh = translate("zh-CN");
  const en = translate("en-US");

  for (const code of ERROR_CODES) {
    const zhMessage = zh(`errors.${code}`);
    const enMessage = en(`errors.${code}`);
    expect(zhMessage).not.toBe(`errors.${code}`);
    expect(enMessage).not.toBe(`errors.${code}`);
    expect(zhMessage).not.toBe(enMessage);
  }
  for (const status of HTTP_STATUSES) {
    expect(zh(`errors.http.${status}`)).not.toBe(`errors.http.${status}`);
    expect(en(`errors.http.${status}`)).not.toBe(`errors.http.${status}`);
  }
  expect(zh("errors.network")).not.toBe(en("errors.network"));
  expect(zh("errors.unknown")).not.toBe(en("errors.unknown"));
});
