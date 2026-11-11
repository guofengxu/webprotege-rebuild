import createClient, { type Client } from "openapi-fetch";
import type { paths } from "@wp/api-types";
import { inject, type App, type InjectionKey } from "vue";
import { readCsrfToken } from "./csrf";

export type ApiClient = Client<paths>;

export type ApiClientOptions = {
  baseUrl?: string;
  fetch?: typeof fetch;
  onUnauthorized?: (response: Response) => void;
};

const apiClientKey: InjectionKey<ApiClient> = Symbol("apiClient");
const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

export function createApiClient(options: ApiClientOptions = {}): ApiClient {
  const fetchImpl = options.fetch;
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    credentials: "same-origin",
    fetch: fetchImpl === undefined ? undefined : (input) => fetchImpl(input),
  });
  client.use({
    onRequest({ request }) {
      return withCsrfHeader(request);
    },
    onResponse({ request, response, schemaPath }) {
      if (
        response.status === 401 &&
        options.onUnauthorized !== undefined &&
        !isAuthProbe(request.method, schemaPath)
      ) {
        return notifyUnauthorized(options.onUnauthorized, response);
      }
      return response;
    },
  });
  return client;
}

export function provideApiClient(app: App, client: ApiClient): void {
  app.provide(apiClientKey, client);
}

export function useApiClient(): ApiClient {
  const client = inject(apiClientKey);
  if (client === undefined) {
    throw new Error("ApiClient is not provided");
  }
  return client;
}

function withCsrfHeader(request: Request): Request {
  if (!UNSAFE_METHODS.has(request.method.toUpperCase())) {
    return request;
  }
  const token = readCsrfToken();
  if (token === undefined) {
    return request;
  }
  const headers = new Headers(request.headers);
  headers.set("X-XSRF-TOKEN", token);
  return new Request(request, { headers });
}

function isAuthProbe(method: string, schemaPath: string): boolean {
  const normalized = method.toUpperCase();
  return (
    (normalized === "POST" && schemaPath === "/api/auth/login") ||
    (normalized === "GET" && schemaPath === "/api/auth/me")
  );
}

async function notifyUnauthorized(
  onUnauthorized: (response: Response) => void,
  response: Response,
): Promise<Response> {
  try {
    // Clone so a handler can read the body while openapi-fetch still parses the original.
    await Promise.resolve(onUnauthorized(response.clone()));
  } catch {
    // Navigation failures must not hide the 401 result.
  }
  return response;
}
