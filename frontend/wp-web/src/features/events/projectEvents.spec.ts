import type {
  BrowserTextChanged,
  EntityFrameChanged,
  EntityHierarchyChanged,
  PermissionsChanged,
  ProjectChanged,
  ProjectStreamMessage,
  ResyncSignal,
} from "@wp/api-types";
import { expect, test, vi } from "vitest";
import { parseProjectEvent } from "./projectEvents";
import type { SseMessage } from "./sseParser";

const projectChanged = {
  type: "ProjectChanged",
  revNo: 7,
  user: "alice",
  timestamp: "2026-11-11T12:00:00Z",
  description: "added a class",
  changeCount: 1,
} satisfies ProjectChanged;

const hierarchyChanged = {
  type: "EntityHierarchyChanged",
  revNo: 8,
  hierarchy: "class",
  added: [{ parent: "http://example.org/Parent", child: "http://example.org/Child" }],
  removed: [],
} satisfies EntityHierarchyChanged;

const frameChanged = {
  type: "EntityFrameChanged",
  revNo: 9,
  entity: { iri: "http://example.org/Child", entityType: "CLASS" },
} satisfies EntityFrameChanged;

const browserTextChanged = {
  type: "BrowserTextChanged",
  revNo: 11,
  entity: { iri: "http://example.org/Child", entityType: "CLASS" },
  displayName: "Child",
} satisfies BrowserTextChanged;

const permissionsChanged = {
  type: "PermissionsChanged",
  revNo: 12,
  projectId: "11111111-1111-1111-1111-111111111111",
} satisfies PermissionsChanged;

const resync = { type: "resync" } satisfies ResyncSignal;

const samples = [
  projectChanged,
  hierarchyChanged,
  frameChanged,
  browserTextChanged,
  permissionsChanged,
  resync,
] satisfies ProjectStreamMessage[];

function message(event: string, data: string): SseMessage {
  return { event, data };
}

test("parses each of the six project stream messages", () => {
  const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
  try {
    for (const sample of samples) {
      expect(parseProjectEvent(message(sample.type, JSON.stringify(sample)))).toEqual(sample);
    }
    expect(warn).not.toHaveBeenCalled();
  } finally {
    warn.mockRestore();
  }
});

test("returns null for an unknown type, malformed JSON, and the wrong shape", () => {
  const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
  const rejected = [
    message("Nope", '{"type":"Nope"}'),
    message("ProjectChanged", "not-json"),
    message("ProjectChanged", '{"type":"ProjectChanged"}'),
    message("comment", "heartbeat"),
    message("ProjectChanged", "null"),
    message("EntityHierarchyChanged", "[]"),
    message(
      "EntityHierarchyChanged",
      JSON.stringify({
        type: "EntityHierarchyChanged",
        revNo: 1,
        hierarchy: "class",
        added: ["not-a-link"],
        removed: [],
      }),
    ),
    message(
      "EntityFrameChanged",
      JSON.stringify({ type: "EntityFrameChanged", revNo: 1, entity: "class" }),
    ),
    message(
      "BrowserTextChanged",
      JSON.stringify({ type: "BrowserTextChanged", revNo: 1, entity: {}, displayName: 1 }),
    ),
    message("PermissionsChanged", JSON.stringify({ type: "PermissionsChanged", revNo: 1 })),
  ];
  try {
    for (const item of rejected) {
      expect(parseProjectEvent(item)).toBeNull();
    }
    expect(warn).toHaveBeenCalledTimes(rejected.length);
  } finally {
    warn.mockRestore();
  }
});

test("warns only while development mode is on", () => {
  const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
  try {
    vi.stubEnv("DEV", false);
    expect(parseProjectEvent(message("Nope", '{"type":"Nope"}'))).toBeNull();
    expect(warn).not.toHaveBeenCalled();

    vi.stubEnv("DEV", true);
    expect(parseProjectEvent(message("Nope", "not-json"))).toBeNull();
    expect(warn).toHaveBeenCalledTimes(1);
  } finally {
    vi.unstubAllEnvs();
    warn.mockRestore();
  }
});
