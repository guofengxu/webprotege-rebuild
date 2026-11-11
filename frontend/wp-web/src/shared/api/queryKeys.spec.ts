import { expect, test } from "vitest";
import { queryKeys } from "./queryKeys";

const id = "project-1";
const kind = "class";
const iri = "http://example.org/Child";

function expectPrefix(key: readonly unknown[], prefix: readonly unknown[]): void {
  expect(key.slice(0, prefix.length)).toEqual([...prefix]);
}

test("named roots match the stable tuples", () => {
  expect(queryKeys.me()).toEqual(["auth", "me"]);
  expect(queryKeys.projects()).toEqual(["projects", "active"]);
  expect(queryKeys.trashedProjects()).toEqual(["projects", "trash"]);
  expect(queryKeys.project(id)).toEqual(["project", id]);
});

test("project data keys start with project(id)", () => {
  const prefix = queryKeys.project(id);
  const keys = [
    queryKeys.project(id),
    queryKeys.projectDetails(id),
    queryKeys.hierarchy(id, kind),
    queryKeys.hierarchyRoots(id, kind),
    queryKeys.hierarchyChildren(id, kind, "http://example.org/Parent"),
    queryKeys.hierarchyPathsAll(id, kind),
    queryKeys.hierarchyPaths(id, kind, iri),
    queryKeys.frames(id),
    queryKeys.frame(id, "CLASS", iri),
    queryKeys.individualsAll(id),
    queryKeys.individuals(id, iri, 0, 50),
    queryKeys.searchAll(id),
    queryKeys.search(id, "owl", ["CLASS"], 50),
    queryKeys.revisionsAll(id),
    queryKeys.revisions(id, 0, 20),
    queryKeys.revisionDiff(id, 3, 100),
    queryKeys.entityHistoryAll(id, iri),
    queryKeys.entityHistory(id, iri, 0, 20),
    queryKeys.settings(id, "prefixes"),
    queryKeys.members(id),
  ];
  for (const key of keys) {
    expectPrefix(key, prefix);
  }
});

test("hierarchy keys start with hierarchy(id, kind)", () => {
  const prefix = queryKeys.hierarchy(id, kind);
  const keys = [
    queryKeys.hierarchyRoots(id, kind),
    queryKeys.hierarchyChildren(id, kind, "http://example.org/Parent"),
    queryKeys.hierarchyPathsAll(id, kind),
    queryKeys.hierarchyPaths(id, kind, iri),
  ];
  for (const key of keys) {
    expectPrefix(key, prefix);
  }
  expectPrefix(queryKeys.hierarchy(id, "object-property"), queryKeys.project(id));
  expect(queryKeys.hierarchy(id, kind)).not.toEqual(
    queryKeys.hierarchy(id, "object-property"),
  );
});

test("parameterized keys start with their prefix and differ by parameter", () => {
  expectPrefix(
    queryKeys.hierarchyPaths(id, kind, iri),
    queryKeys.hierarchyPathsAll(id, kind),
  );
  expectPrefix(queryKeys.individuals(id, iri, 0, 50), queryKeys.individualsAll(id));
  expectPrefix(queryKeys.frame(id, "CLASS", iri), queryKeys.frames(id));
  expectPrefix(queryKeys.search(id, "owl", ["CLASS"], 50), queryKeys.searchAll(id));
  expectPrefix(queryKeys.revisions(id, 0, 20), queryKeys.revisionsAll(id));
  expectPrefix(
    queryKeys.entityHistory(id, iri, 1, 20),
    queryKeys.entityHistoryAll(id, iri),
  );
  expectPrefix(queryKeys.adminUsers(1, 20), queryKeys.adminUsersAll());

  expect(queryKeys.individuals(id, iri, 0, 50)).not.toEqual(
    queryKeys.individuals(id, iri, 1, 50),
  );
  expect(queryKeys.frame(id, "CLASS", iri)).not.toEqual(
    queryKeys.frame(id, "OBJECT_PROPERTY", iri),
  );
  expect(queryKeys.search(id, "owl", ["CLASS"], 50)).not.toEqual(
    queryKeys.search(id, "owl", ["CLASS"], 20),
  );
  expect(queryKeys.search(id, "owl", ["CLASS"], 50)).not.toEqual(
    queryKeys.search(id, "owl", ["OBJECT_PROPERTY"], 50),
  );
  expect(queryKeys.revisions(id, 0, 20)).not.toEqual(queryKeys.revisions(id, 1, 20));
  expect(queryKeys.entityHistory(id, iri, 0, 20)).not.toEqual(
    queryKeys.entityHistory(id, "http://example.org/Other", 0, 20),
  );
  expect(queryKeys.adminUsers(0, 20)).not.toEqual(queryKeys.adminUsers(1, 20));
  expect(queryKeys.revisionDiff(id, 3, 100)).not.toEqual(
    queryKeys.revisionDiff(id, 4, 100),
  );
  const types = ["CLASS"];
  const searchKey = queryKeys.search(id, "owl", types, 50);
  types.push("INDIVIDUAL");
  expect(searchKey[4]).toEqual(["CLASS"]);
});
