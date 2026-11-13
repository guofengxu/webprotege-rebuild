import { useQueryClient } from "@tanstack/vue-query";
import {
  onScopeDispose,
  ref,
  watch,
  type InjectionKey,
  type Ref,
} from "vue";
import { useApiClient } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queryKeys";
import { applyProjectEvent } from "./applyProjectEvent";
import { parseProjectEvent } from "./projectEvents";
import { parseSse, type SseMessage } from "./sseParser";

export type ProjectEventsStatus =
  | "idle"
  | "connecting"
  | "open"
  | "reconnecting"
  | "stopped";

export type UseProjectEventsResult = {
  readonly status: Ref<ProjectEventsStatus>;
  readonly stopReason: Ref<string | null>;
  readonly retry: () => void;
};

export type ProjectEventsConnection = {
  readonly status: Ref<ProjectEventsStatus>;
  readonly stopReason: Ref<string | null>;
  readonly retry: () => void;
};

export const projectEventsConnectionKey: InjectionKey<ProjectEventsConnection> =
  Symbol("wp.projectEvents.connection");

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30_000;
const HEARTBEAT_TIMEOUT_MS = 60_000;
const HIDDEN_DISCONNECT_MS = 5 * 60_000;

export function useProjectEvents(
  projectId: Readonly<Ref<string>>,
  enabled: Readonly<Ref<boolean>>,
): UseProjectEventsResult {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const status = ref<ProjectEventsStatus>("idle");
  const stopReason = ref<string | null>(null);

  let lastId: string | undefined;
  let abortController: AbortController | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
  let hiddenTimer: ReturnType<typeof setTimeout> | null = null;
  let backoffMs = INITIAL_BACKOFF_MS;
  let runId = 0;
  let stopped = false;
  let hiddenDisconnected = false;
  let sse: AsyncGenerator<SseMessage> | null = null;

  function clearReconnectTimer(): void {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  function clearHeartbeatTimer(): void {
    if (heartbeatTimer !== null) {
      clearTimeout(heartbeatTimer);
      heartbeatTimer = null;
    }
  }

  function clearHiddenTimer(): void {
    if (hiddenTimer !== null) {
      clearTimeout(hiddenTimer);
      hiddenTimer = null;
    }
  }

  function clearTimers(): void {
    clearReconnectTimer();
    clearHeartbeatTimer();
    clearHiddenTimer();
  }

  async function abortCurrent(): Promise<void> {
    clearHeartbeatTimer();
    const controller = abortController;
    abortController = null;
    const currentSse = sse;
    sse = null;
    controller?.abort();
    if (currentSse !== null) {
      await currentSse.return(undefined).catch(() => undefined);
    }
  }

  function resetHeartbeat(currentRun: number): void {
    clearHeartbeatTimer();
    heartbeatTimer = setTimeout(() => {
      if (currentRun !== runId || stopped) {
        return;
      }
      void onHeartbeatTimeout(currentRun);
    }, HEARTBEAT_TIMEOUT_MS);
  }

  async function onHeartbeatTimeout(currentRun: number): Promise<void> {
    if (currentRun !== runId || stopped) {
      return;
    }
    await abortCurrent();
    scheduleReconnect(currentRun);
  }

  function scheduleReconnect(currentRun: number, immediate = false): void {
    if (stopped || currentRun !== runId || !enabled.value) {
      return;
    }
    clearHeartbeatTimer();
    clearReconnectTimer();
    status.value = "reconnecting";
    const delay = immediate ? 0 : backoffMs;
    if (!immediate) {
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    }
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (stopped || currentRun !== runId || !enabled.value) {
        return;
      }
      void connect(currentRun);
    }, delay);
  }

  function stopWithReason(reason: string): void {
    stopped = true;
    clearTimers();
    status.value = "stopped";
    stopReason.value = reason;
    void abortCurrent();
  }

  function stopReasonForStatus(httpStatus: number): string | null {
    switch (httpStatus) {
      case 401:
        return "unauthorized";
      case 403:
        return "forbidden";
      case 404:
        return "notFound";
      case 409:
        return "conflict";
      default:
        return null;
    }
  }

  async function connect(currentRun: number): Promise<void> {
    if (stopped || currentRun !== runId || !enabled.value) {
      return;
    }
    if (
      typeof document !== "undefined" &&
      document.visibilityState === "hidden" &&
      hiddenDisconnected
    ) {
      status.value = "reconnecting";
      return;
    }

    clearReconnectTimer();
    await abortCurrent();
    if (stopped || currentRun !== runId || !enabled.value) {
      return;
    }

    const id = projectId.value;
    if (id.length === 0) {
      return;
    }

    const openedWithoutCursor = lastId === undefined;
    const controller = new AbortController();
    abortController = controller;
    status.value = "connecting";

    try {
      const result = await client.GET("/api/projects/{id}/events", {
        params:
          lastId === undefined
            ? { path: { id } }
            : { path: { id }, header: { "Last-Event-ID": lastId } },
        parseAs: "stream",
        signal: controller.signal,
      });

      if (stopped || currentRun !== runId) {
        const staleBody = result.data;
        if (staleBody !== undefined && staleBody !== null) {
          void staleBody.cancel().catch(() => undefined);
        }
        return;
      }

      if (!result.response.ok) {
        const reason = stopReasonForStatus(result.response.status);
        if (reason !== null) {
          stopWithReason(reason);
          return;
        }
        scheduleReconnect(currentRun);
        return;
      }

      const body = result.data;
      if (body === undefined || body === null) {
        scheduleReconnect(currentRun);
        return;
      }

      status.value = "open";
      stopReason.value = null;
      backoffMs = INITIAL_BACKOFF_MS;
      if (openedWithoutCursor) {
        void queryClient
          .invalidateQueries({ queryKey: queryKeys.project(id) })
          .catch((error: unknown) => {
            console.error("Failed to invalidate project queries after SSE open", error);
          });
      }
      resetHeartbeat(currentRun);

      let resyncApplied = false;
      const generator = parseSse(body);
      sse = generator;

      for await (const message of generator) {
        if (stopped || currentRun !== runId) {
          return;
        }
        resetHeartbeat(currentRun);

        if (message.event === "comment") {
          continue;
        }

        if (message.id !== undefined && message.id.length > 0) {
          lastId = message.id;
        }

        const event = parseProjectEvent(message);
        if (event === null) {
          continue;
        }

        if (event.type === "resync") {
          lastId = undefined;
          if (resyncApplied) {
            continue;
          }
          resyncApplied = true;
        }

        const outcome = applyProjectEvent(queryClient, id, event);
        if (outcome.reconnect) {
          await abortCurrent();
          if (stopped || currentRun !== runId) {
            return;
          }
          scheduleReconnect(currentRun, true);
          return;
        }
      }

      if (stopped || currentRun !== runId || controller.signal.aborted) {
        return;
      }
      scheduleReconnect(currentRun);
    } catch (error) {
      if (stopped || currentRun !== runId || controller.signal.aborted) {
        return;
      }
      if (isAbortError(error)) {
        return;
      }
      scheduleReconnect(currentRun);
    }
  }

  function start(): void {
    stopped = false;
    stopReason.value = null;
    clearTimers();
    runId += 1;
    const currentRun = runId;
    void connect(currentRun);
  }

  function stopIdle(): void {
    stopped = true;
    clearTimers();
    backoffMs = INITIAL_BACKOFF_MS;
    status.value = "idle";
    stopReason.value = null;
    void abortCurrent();
  }

  function retry(): void {
    if (!enabled.value) {
      return;
    }
    stopped = false;
    stopReason.value = null;
    backoffMs = INITIAL_BACKOFF_MS;
    runId += 1;
    const currentRun = runId;
    void connect(currentRun);
  }

  function onVisibilityChange(): void {
    if (typeof document === "undefined") {
      return;
    }
    if (document.visibilityState === "hidden") {
      clearHiddenTimer();
      hiddenTimer = setTimeout(() => {
        hiddenTimer = null;
        if (!enabled.value || stopped) {
          return;
        }
        hiddenDisconnected = true;
        void abortCurrent();
        status.value = "reconnecting";
      }, HIDDEN_DISCONNECT_MS);
      return;
    }
    clearHiddenTimer();
    if (hiddenDisconnected && enabled.value && !stopped) {
      hiddenDisconnected = false;
      runId += 1;
      const currentRun = runId;
      void connect(currentRun);
    } else {
      hiddenDisconnected = false;
    }
  }

  watch(
    [enabled, projectId],
    ([isEnabled, id], previous) => {
      if (!isEnabled || id.length === 0) {
        stopIdle();
        return;
      }
      const wasEnabled = previous?.[0];
      const previousId = previous?.[1];
      if (wasEnabled === true && previousId !== undefined && previousId !== id) {
        lastId = undefined;
        backoffMs = INITIAL_BACKOFF_MS;
      }
      hiddenDisconnected = false;
      start();
    },
    { immediate: true },
  );

  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", onVisibilityChange);
  }

  onScopeDispose(() => {
    stopped = true;
    clearTimers();
    if (typeof document !== "undefined") {
      document.removeEventListener("visibilitychange", onVisibilityChange);
    }
    void abortCurrent();
  });

  return { status, stopReason, retry };
}

function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === "AbortError") ||
    (error instanceof Error && error.name === "AbortError")
  );
}
