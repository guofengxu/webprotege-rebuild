import { flushPromises } from "@vue/test-utils";
import { defineComponent, h, ref, type Ref } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import type { MockHandler } from "@/shared/testing/mockFetch";
import { applyProjectEvent } from "./applyProjectEvent";
import {
  useProjectEvents,
  type ProjectEventsStatus,
  type UseProjectEventsResult,
} from "./useProjectEvents";

vi.mock("./applyProjectEvent", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./applyProjectEvent")>();
  return {
    ...actual,
    applyProjectEvent: vi.fn(actual.applyProjectEvent),
  };
});

const applyMock = vi.mocked(applyProjectEvent);

type StreamControl = {
  enqueue: (text: string) => void;
  close: () => void;
  stream: ReadableStream<Uint8Array>;
};

function controllableStream(): StreamControl {
  const encoder = new TextEncoder();
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    stream,
    enqueue(text) {
      controller.enqueue(encoder.encode(text));
    },
    close() {
      controller.close();
    },
  };
}

function sseFrame(lines: readonly string[]): string {
  return `${lines.join("\n")}\n\n`;
}

function projectChangedFrame(id: string): string {
  return sseFrame([
    `id: ${id}`,
    "event: ProjectChanged",
    `data: ${JSON.stringify({
      type: "ProjectChanged",
      revNo: Number(id),
      user: "alice",
      timestamp: "2026-01-01T00:00:00Z",
      description: "edit",
      changeCount: 1,
    })}`,
  ]);
}

function permissionsChangedFrame(id: string): string {
  return sseFrame([
    `id: ${id}`,
    "event: PermissionsChanged",
    `data: ${JSON.stringify({
      type: "PermissionsChanged",
      revNo: Number(id),
      projectId: "p1",
    })}`,
  ]);
}

function resyncFrame(): string {
  return sseFrame(["event: resync", 'data: {"type":"resync"}']);
}

beforeEach(() => {
  vi.useFakeTimers();
  applyMock.mockClear();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.removeItem("wp.locale");
});

test("applies parsed events via applyProjectEvent", async () => {
  const streams: StreamControl[] = [];
  const { api, wrapper } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  expect(api.status.value).toBe("open");
  streams[0]!.enqueue(projectChangedFrame("7"));
  await flushPromises();

  expect(applyMock).toHaveBeenCalledWith(
    expect.anything(),
    "p1",
    expect.objectContaining({ type: "ProjectChanged", revNo: 7 }),
  );
  wrapper.unmount();
});

test("reconnects with backoff and Last-Event-ID after the stream ends", async () => {
  const streams: StreamControl[] = [];
  const { api, wrapper, requests } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  streams[0]!.enqueue(projectChangedFrame("12"));
  await flushPromises();
  streams[0]!.close();
  await flushPromises();

  expect(api.status.value).toBe("reconnecting");
  await vi.advanceTimersByTimeAsync(1000);
  await flushPromises();

  expect(streams).toHaveLength(2);
  const second = requests.filter((r) => r.path === "/api/projects/p1/events")[1];
  expect(second?.headers.get("Last-Event-ID")).toBe("12");
  expect(api.status.value).toBe("open");
  wrapper.unmount();
});

test("clears Last-Event-ID after resync so the next reconnect has no cursor", async () => {
  const streams: StreamControl[] = [];
  const { wrapper, requests } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  streams[0]!.enqueue(projectChangedFrame("20"));
  await flushPromises();
  streams[0]!.enqueue(resyncFrame());
  await flushPromises();
  streams[0]!.close();
  await flushPromises();
  await vi.advanceTimersByTimeAsync(1000);
  await flushPromises();

  const second = requests.filter((r) => r.path === "/api/projects/p1/events")[1];
  expect(second?.headers.get("Last-Event-ID")).toBeNull();
  wrapper.unmount();
});

test("invalidates the project query once on first open without a cursor", async () => {
  const streams: StreamControl[] = [];
  const invalidate = vi.spyOn(
    (await import("@tanstack/vue-query")).QueryClient.prototype,
    "invalidateQueries",
  );
  const { wrapper } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  expect(invalidate).toHaveBeenCalledWith(
    expect.objectContaining({ queryKey: queryKeys.project("p1") }),
  );
  invalidate.mockRestore();
  wrapper.unmount();
});

test("PermissionsChanged triggers an immediate reconnect with Last-Event-ID", async () => {
  const streams: StreamControl[] = [];
  const { wrapper, requests } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  streams[0]!.enqueue(permissionsChangedFrame("33"));
  await flushPromises();
  await vi.advanceTimersByTimeAsync(0);
  await flushPromises();

  expect(streams).toHaveLength(2);
  const second = requests.filter((r) => r.path === "/api/projects/p1/events")[1];
  expect(second?.headers.get("Last-Event-ID")).toBe("33");
  wrapper.unmount();
});

test("403 stops reconnecting", async () => {
  const { api, wrapper, requests } = await mountEvents({
    enabled: true,
    eventsHandler: () => ({
      status: 403,
      problem: {
        status: 403,
        code: "PERMISSION_DENIED",
        title: "Forbidden",
      },
    }),
  });

  await flushPromises();
  expect(api.status.value).toBe("stopped");
  expect(api.stopReason.value).toBe("forbidden");
  await vi.advanceTimersByTimeAsync(30_000);
  await flushPromises();
  expect(requests.filter((r) => r.path === "/api/projects/p1/events")).toHaveLength(1);
  wrapper.unmount();
});

test("does not request when enabled is false", async () => {
  const { wrapper, requests } = await mountEvents({
    enabled: false,
    eventsHandler: () => {
      throw new Error("events handler should not run");
    },
  });

  await flushPromises();
  expect(requests.filter((r) => r.path.includes("/events"))).toHaveLength(0);
  wrapper.unmount();
});

test("unmount aborts the request and leaves no reconnect timers", async () => {
  const streams: StreamControl[] = [];
  const { wrapper, requests } = await mountEvents({
    enabled: true,
    eventsHandler: () => {
      const control = controllableStream();
      streams.push(control);
      return eventStreamResponse(control.stream);
    },
  });

  await flushPromises();
  streams[0]!.close();
  await flushPromises();
  expect(requests.filter((r) => r.path === "/api/projects/p1/events")).toHaveLength(1);

  wrapper.unmount();
  await vi.advanceTimersByTimeAsync(60_000);
  await flushPromises();
  expect(requests.filter((r) => r.path === "/api/projects/p1/events")).toHaveLength(1);
});

async function mountEvents(options: {
  enabled: boolean;
  eventsHandler: MockHandler;
}): Promise<{
  api: UseProjectEventsResult & {
    enabled: Ref<boolean>;
    projectId: Ref<string>;
    status: Ref<ProjectEventsStatus>;
  };
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"];
  requests: Awaited<ReturnType<typeof renderWithProviders>>["requests"];
  queryClient: Awaited<ReturnType<typeof renderWithProviders>>["queryClient"];
}> {
  let api!: UseProjectEventsResult & {
    enabled: Ref<boolean>;
    projectId: Ref<string>;
  };
  const Host = defineComponent({
    name: "ProjectEventsHost",
    setup() {
      const projectId = ref("p1");
      const enabled = ref(options.enabled);
      const events = useProjectEvents(projectId, enabled);
      api = { ...events, enabled, projectId };
      return () =>
        h("div", {
          "data-status": events.status.value,
          "data-stop": events.stopReason.value ?? "",
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers: {
      "GET /api/projects/{id}/events": options.eventsHandler,
    },
  });
  return { ...rendered, api };
}

function eventStreamResponse(stream: ReadableStream<Uint8Array>) {
  return {
    status: 200,
    headers: { "content-type": "text/event-stream" },
    body: stream,
  };
}
