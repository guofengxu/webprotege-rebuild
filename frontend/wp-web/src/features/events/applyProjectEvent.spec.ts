import type { QueryClient } from "@tanstack/vue-query";
import { QueryClient as QueryClientImpl } from "@tanstack/vue-query";
import type { ProjectStreamMessage } from "@wp/api-types";
import { expect, test, vi } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { applyProjectEvent } from "./applyProjectEvent";

const projectId = "11111111-1111-1111-1111-111111111111";
const otherId = "22222222-2222-2222-2222-222222222222";
const grandparent = "http://example.org/Grandparent";
const parent = "http://example.org/Parent";
const child = "http://example.org/Child";
const individual = "http://example.org/Individual";
const listedClass = "http://example.org/ListedClass";

const KEYS = {
  details: queryKeys.projectDetails(projectId),
  revisions: queryKeys.revisions(projectId, 0, 20),
  diff: queryKeys.revisionDiff(projectId, 3, 100),
  classGrandparent: queryKeys.hierarchyChildren(projectId, "class", grandparent),
  classParent: queryKeys.hierarchyChildren(projectId, "class", parent),
  classRoots: queryKeys.hierarchyRoots(projectId, "class"),
  classPathsAll: queryKeys.hierarchyPathsAll(projectId, "class"),
  classPaths: queryKeys.hierarchyPaths(projectId, "class", child),
  objectChildren: queryKeys.hierarchyChildren(projectId, "object-property", parent),
  objectPaths: queryKeys.hierarchyPaths(projectId, "object-property", parent),
  dataRoots: queryKeys.hierarchyRoots(projectId, "data-property"),
  dataChildren: queryKeys.hierarchyChildren(projectId, "data-property", parent),
  dataPaths: queryKeys.hierarchyPaths(projectId, "data-property", parent),
  annotationRoots: queryKeys.hierarchyRoots(projectId, "annotation-property"),
  annotationChildren: queryKeys.hierarchyChildren(projectId, "annotation-property", parent),
  annotationPaths: queryKeys.hierarchyPaths(projectId, "annotation-property", parent),
  frames: queryKeys.frames(projectId),
  classFrame: queryKeys.frame(projectId, "CLASS", child),
  individualFrame: queryKeys.frame(projectId, "NAMED_INDIVIDUAL", individual),
  propertyFrame: queryKeys.frame(projectId, "OBJECT_PROPERTY", parent),
  individuals: queryKeys.individuals(projectId, listedClass, 0, 50),
  individualsNext: queryKeys.individuals(projectId, listedClass, 1, 25),
  search: queryKeys.search(projectId, "owl", ["CLASS"], 20),
  classHistory: queryKeys.entityHistory(projectId, child, 0, 20),
  individualHistory: queryKeys.entityHistory(projectId, individual, 0, 20),
  settings: queryKeys.settings(projectId, "prefixes"),
  members: queryKeys.members(projectId),
  projects: queryKeys.projects(),
  trashed: queryKeys.trashedProjects(),
  me: queryKeys.me(),
  otherDetails: queryKeys.projectDetails(otherId),
  otherClassRoots: queryKeys.hierarchyRoots(otherId, "class"),
  otherClassPaths: queryKeys.hierarchyPaths(otherId, "class", child),
  otherIndividuals: queryKeys.individuals(otherId, child, 0, 50),
} as const;

const OUTSIDE_PROJECT = new Set([
  "projects",
  "trashed",
  "me",
  "otherDetails",
  "otherClassRoots",
  "otherClassPaths",
  "otherIndividuals",
]);

const PROJECT_KEYS = (Object.keys(KEYS) as (keyof typeof KEYS)[]).filter(
  (name) => !OUTSIDE_PROJECT.has(name),
);

const HIERARCHY_KEYS = [
  "classGrandparent",
  "classParent",
  "classRoots",
  "classPathsAll",
  "classPaths",
  "objectChildren",
  "objectPaths",
  "dataRoots",
  "dataChildren",
  "dataPaths",
  "annotationRoots",
  "annotationChildren",
  "annotationPaths",
] as const;

const FRAME_KEYS = ["frames", "classFrame", "individualFrame", "propertyFrame"] as const;

function seededClient(): QueryClient {
  const client = new QueryClientImpl({ defaultOptions: { queries: { retry: false } } });
  for (const key of Object.values(KEYS)) {
    client.setQueryData(key, "seed");
  }
  return client;
}

function invalidated(client: QueryClient): string[] {
  return Object.entries(KEYS)
    .filter(([, key]) => {
      return client.getQueryCache().find({ queryKey: key, exact: true })?.state.isInvalidated === true;
    })
    .map(([name]) => name)
    .sort();
}

function expectEffect(
  event: ProjectStreamMessage,
  names: readonly string[],
  reconnect: boolean,
): void {
  const client = seededClient();
  expect(applyProjectEvent(client, projectId, event)).toEqual({ reconnect });
  expect(invalidated(client)).toEqual([...names].sort());
  for (const key of Object.values(KEYS)) {
    expect(client.getQueryData(key)).toBe("seed");
  }
}

test("ProjectChanged invalidates revision history and project details", () => {
  expectEffect(
    {
      type: "ProjectChanged",
      revNo: 7,
      user: "alice",
      timestamp: "2026-11-11T12:00:00Z",
      description: "added a class",
      changeCount: 1,
    },
    ["revisions", "diff", "details"],
    false,
  );
});

test("EntityHierarchyChanged invalidates the whole hierarchy prefix, including the grandparent", () => {
  expectEffect(
    {
      type: "EntityHierarchyChanged",
      revNo: 8,
      hierarchy: "class",
      added: [{ parent, child }],
      removed: [],
    },
    ["classGrandparent", "classParent", "classRoots", "classPathsAll", "classPaths", ...FRAME_KEYS, "individuals", "individualsNext"],
    false,
  );
});

test("EntityHierarchyChanged refetches inactive hierarchy queries (refetchType all)", () => {
  const client = seededClient();
  const spy = vi.spyOn(client, "invalidateQueries");
  applyProjectEvent(client, projectId, {
    type: "EntityHierarchyChanged",
    revNo: 8,
    hierarchy: "class",
    added: [{ parent, child }],
    removed: [],
  });
  const hierarchyCalls = spy.mock.calls.filter((args) => {
    const options = args[0] as {
      queryKey?: readonly unknown[];
      refetchType?: string;
    };
    return (
      Array.isArray(options.queryKey) &&
      options.queryKey.includes("hierarchy") &&
      options.refetchType === "all"
    );
  });
  expect(hierarchyCalls.length).toBeGreaterThan(0);
  spy.mockRestore();
});

test("a non-class hierarchy change leaves individuals and the other hierarchies fresh", () => {
  expectEffect(
    {
      type: "EntityHierarchyChanged",
      revNo: 8,
      hierarchy: "object-property",
      added: [{ parent, child }],
      removed: [],
    },
    ["objectChildren", "objectPaths", ...FRAME_KEYS],
    false,
  );
});

test("EntityFrameChanged invalidates that frame and its entity history", () => {
  expectEffect(
    {
      type: "EntityFrameChanged",
      revNo: 9,
      entity: { iri: child, entityType: "CLASS" },
    },
    ["classFrame", "classHistory"],
    false,
  );
});

test("a named individual frame change also invalidates the individual list", () => {
  expectEffect(
    {
      type: "EntityFrameChanged",
      revNo: 10,
      entity: { iri: individual, entityType: "NAMED_INDIVIDUAL" },
    },
    ["individualFrame", "individualHistory", "individuals", "individualsNext"],
    false,
  );
});

test("BrowserTextChanged invalidates every hierarchy, individuals, search, and frames", () => {
  expectEffect(
    {
      type: "BrowserTextChanged",
      revNo: 11,
      entity: { iri: child, entityType: "CLASS" },
      displayName: "Child",
    },
    [...HIERARCHY_KEYS, "individuals", "individualsNext", "search", ...FRAME_KEYS],
    false,
  );
});

test("PermissionsChanged drops the project and the project list and asks to reconnect", () => {
  expectEffect(
    { type: "PermissionsChanged", revNo: 12, projectId },
    [...PROJECT_KEYS, "projects"],
    true,
  );
});

test("a data-property hierarchy change invalidates that prefix and frames only", () => {
  expectEffect(
    {
      type: "EntityHierarchyChanged",
      revNo: 8,
      hierarchy: "data-property",
      added: [{ parent, child }],
      removed: [],
    },
    ["dataRoots", "dataChildren", "dataPaths", ...FRAME_KEYS],
    false,
  );
});

test("an annotation-property hierarchy change invalidates that prefix and frames only", () => {
  expectEffect(
    {
      type: "EntityHierarchyChanged",
      revNo: 8,
      hierarchy: "annotation-property",
      added: [{ parent, child }],
      removed: [],
    },
    ["annotationRoots", "annotationChildren", "annotationPaths", ...FRAME_KEYS],
    false,
  );
});

test("a frame change without an iri does not invalidate another entity", () => {
  expectEffect(
    {
      type: "EntityFrameChanged",
      revNo: 9,
      entity: { entityType: "CLASS" },
    },
    [],
    false,
  );
});

test("a named individual frame change without an iri still refreshes the individual list", () => {
  expectEffect(
    {
      type: "EntityFrameChanged",
      revNo: 10,
      entity: { entityType: "NAMED_INDIVIDUAL" },
    },
    ["individuals", "individualsNext"],
    false,
  );
});

test("resync drops every query for this project and leaves other projects alone", () => {
  expectEffect({ type: "resync" }, PROJECT_KEYS, false);
});

test("logs a failed invalidation and still returns", async () => {
  const client = new QueryClientImpl({ defaultOptions: { queries: { retry: false } } });
  const failure = new Error("refetch failed");
  vi.spyOn(client, "invalidateQueries").mockRejectedValue(failure);
  const logged = vi.spyOn(console, "error").mockImplementation(() => undefined);
  try {
    expect(applyProjectEvent(client, projectId, { type: "resync" })).toEqual({ reconnect: false });
    await Promise.resolve();
    expect(logged).toHaveBeenCalledWith("Failed to invalidate queries after a project event", failure);
  } finally {
    logged.mockRestore();
  }
});
