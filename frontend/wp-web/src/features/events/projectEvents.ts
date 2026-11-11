import type { ProjectStreamMessage } from "@wp/api-types";
import type { SseMessage } from "./sseParser";

type EventType =
  | "ProjectChanged"
  | "EntityHierarchyChanged"
  | "EntityFrameChanged"
  | "BrowserTextChanged"
  | "PermissionsChanged"
  | "resync";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isEventType(value: string): value is EventType {
  switch (value) {
    case "ProjectChanged":
    case "EntityHierarchyChanged":
    case "EntityFrameChanged":
    case "BrowserTextChanged":
    case "PermissionsChanged":
    case "resync":
      return true;
    default:
      return false;
  }
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isLink(value: unknown): boolean {
  if (!isRecord(value)) {
    return false;
  }
  const parentOk = value.parent === undefined || isString(value.parent);
  const childOk = value.child === undefined || isString(value.child);
  return parentOk && childOk;
}

function isLinkList(value: unknown): boolean {
  return Array.isArray(value) && value.every(isLink);
}

function isEventEntity(value: unknown): boolean {
  if (!isRecord(value)) {
    return false;
  }
  const iriOk = value.iri === undefined || isString(value.iri);
  const typeOk = value.entityType === undefined || isString(value.entityType);
  return iriOk && typeOk;
}

function isProjectStreamMessage(value: unknown): value is ProjectStreamMessage {
  if (!isRecord(value) || !isString(value.type) || !isEventType(value.type)) {
    return false;
  }
  switch (value.type) {
    case "resync":
      return true;
    case "ProjectChanged":
      return (
        isFiniteNumber(value.revNo) &&
        isString(value.user) &&
        isString(value.timestamp) &&
        isString(value.description) &&
        isFiniteNumber(value.changeCount)
      );
    case "EntityHierarchyChanged":
      return (
        isFiniteNumber(value.revNo) &&
        isString(value.hierarchy) &&
        isLinkList(value.added) &&
        isLinkList(value.removed)
      );
    case "EntityFrameChanged":
      return isFiniteNumber(value.revNo) && isEventEntity(value.entity);
    case "BrowserTextChanged":
      return isFiniteNumber(value.revNo) && isString(value.displayName) && isEventEntity(value.entity);
    case "PermissionsChanged":
      return isFiniteNumber(value.revNo) && isString(value.projectId);
    default:
      return false;
  }
}

function warnRejected(message: SseMessage, reason: string): void {
  if (import.meta.env.DEV) {
    console.warn(`Ignoring project event (${reason})`, message.event);
  }
}

export function parseProjectEvent(message: SseMessage): ProjectStreamMessage | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(message.data);
  } catch (error) {
    warnRejected(message, error instanceof Error ? error.message : "data is not JSON");
    return null;
  }
  if (!isProjectStreamMessage(parsed)) {
    warnRejected(message, "unknown type or unexpected shape");
    return null;
  }
  return parsed;
}
