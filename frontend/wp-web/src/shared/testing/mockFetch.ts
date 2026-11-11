import type { ProblemDetail } from "@wp/api-types";

export type MockRequest = {
  params: Record<string, string>;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
};

export type MockResponse = {
  status?: number;
  json?: unknown;
  problem?: Partial<ProblemDetail>;
  body?: BodyInit;
  headers?: Record<string, string>;
};

export type MockHandler = (req: MockRequest) => MockResponse | Promise<MockResponse>;

export type RecordedRequest = {
  method: string;
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
};

type CompiledRoute = {
  method: string;
  template: string;
  pattern: RegExp;
  names: readonly string[];
  handle: MockHandler;
};

const PARAM_SEGMENT = /^\{([^{}]+)\}$/;

export function createMockFetch(handlers: Record<string, MockHandler>): {
  fetch: typeof fetch;
  requests: RecordedRequest[];
} {
  const routes = Object.entries(handlers).map(([key, handle]) =>
    compileRoute(key, handle),
  );
  const requests: RecordedRequest[] = [];
  const fetchImpl: typeof fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    return dispatch(routes, requests, request);
  };
  return { fetch: fetchImpl, requests };
}

async function dispatch(
  routes: readonly CompiledRoute[],
  requests: RecordedRequest[],
  request: Request,
): Promise<Response> {
  const url = new URL(request.url);
  const matched = matchRoute(routes, request.method.toUpperCase(), url.pathname);
  const text = await request.text();
  const contentType = request.headers.get("content-type") ?? "";
  const body = parseRecordedBody(
    text,
    contentType,
    request.method.toUpperCase(),
    url.pathname,
  );
  const query = new URLSearchParams(url.searchParams);
  requests.push({
    method: request.method.toUpperCase(),
    path: url.pathname,
    query,
    headers: new Headers(request.headers),
    body,
  });
  const mock = await matched.handle({
    params: matched.params,
    query: new URLSearchParams(query),
    headers: new Headers(request.headers),
    body,
  });
  return toResponse(mock);
}

function compileRoute(key: string, handle: MockHandler): CompiledRoute {
  const separator = key.indexOf(" ");
  if (separator <= 0) {
    throw new Error(`Mock handler key must look like "GET /api/projects/{id}", got "${key}"`);
  }
  const method = key.slice(0, separator).toUpperCase();
  const template = key.slice(separator + 1);
  if (!template.startsWith("/")) {
    throw new Error(`Mock handler path must start with /, got "${key}"`);
  }
  const names: string[] = [];
  const source = template
    .split("/")
    .map((segment) => {
      const name = PARAM_SEGMENT.exec(segment)?.[1];
      if (name !== undefined) {
        names.push(name);
        return "([^/]+)";
      }
      return segment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    })
    .join("/");
  return { method, template, pattern: new RegExp(`^${source}$`), names, handle };
}

function matchRoute(
  routes: readonly CompiledRoute[],
  method: string,
  path: string,
): { handle: MockHandler; params: Record<string, string> } {
  const matches = routes.flatMap((route) => {
    if (route.method !== method) {
      return [];
    }
    const found = route.pattern.exec(path);
    return found === null ? [] : [{ route, found }];
  });
  const ranked = [...matches].sort(preferSpecificRoute);
  const best = ranked[0];
  const next = ranked[1];
  if (best === undefined) {
    throw new Error(`No mock handler for ${method} ${path}`);
  }
  if (
    next !== undefined &&
    next.route.names.length === best.route.names.length &&
    next.route.template.length === best.route.template.length
  ) {
    throw new Error(
      `Ambiguous mock handlers for ${method} ${path}: ${best.route.template}, ${next.route.template}`,
    );
  }
  return {
    handle: best.route.handle,
    params: pathParams(best.route.names, best.found),
  };
}

function preferSpecificRoute(
  left: { route: CompiledRoute },
  right: { route: CompiledRoute },
): number {
  const byParams = left.route.names.length - right.route.names.length;
  if (byParams !== 0) {
    return byParams;
  }
  return right.route.template.length - left.route.template.length;
}

function pathParams(
  names: readonly string[],
  found: RegExpExecArray,
): Record<string, string> {
  return Object.fromEntries(
    names.map((name, index) => [name, decodeURIComponent(found[index + 1] ?? "")]),
  );
}

function parseRecordedBody(
  text: string,
  contentType: string,
  method: string,
  path: string,
): unknown {
  if (text.length === 0) {
    return undefined;
  }
  if (!contentType.includes("json")) {
    return text;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new Error(`Mock request body is not JSON: ${method} ${path}`);
  }
}

function toResponse(mock: MockResponse): Response {
  const headers = new Headers(mock.headers);
  if (mock.problem !== undefined) {
    if (!headers.has("content-type")) {
      headers.set("content-type", "application/problem+json");
    }
    return new Response(JSON.stringify(mock.problem), {
      status: mock.status ?? mock.problem.status ?? 400,
      headers,
    });
  }
  if (mock.json !== undefined) {
    if (!headers.has("content-type")) {
      headers.set("content-type", "application/json");
    }
    return new Response(JSON.stringify(mock.json), {
      status: mock.status ?? 200,
      headers,
    });
  }
  return new Response(mock.body ?? null, {
    status: mock.status ?? 200,
    headers,
  });
}
