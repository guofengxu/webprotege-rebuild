import type {
  EntityNode,
  RevisionChange,
  RevisionDiff,
  RevisionPage,
  RevisionSummary,
} from "@wp/api-types";

export const CLASS_NODE: EntityNode = {
  iri: "http://ex.org/Pizza",
  entityType: "CLASS",
  displayName: "Pizza",
  hasChildren: true,
  deprecated: false,
};

export const ONTOLOGY_NODE: EntityNode = {
  iri: "http://ex.org/ont",
  entityType: "ONTOLOGY",
  displayName: "ont",
  hasChildren: false,
  deprecated: false,
};

export const REVISION_3: RevisionSummary = {
  revNo: 3,
  userId: "alice",
  ts: "2026-03-01T10:00:00Z",
  description: "Add Pizza",
  changeCount: 2,
};

export const REVISION_2: RevisionSummary = {
  revNo: 2,
  userId: "bob",
  ts: "2026-02-01T09:00:00Z",
  description: "Rename",
  changeCount: 1,
};

export const REVISION_1: RevisionSummary = {
  revNo: 1,
  userId: "system",
  ts: "2026-01-01T08:00:00Z",
  description: "Initial",
  changeCount: 1,
};

export const REVISION_PAGE: RevisionPage = {
  page: 0,
  size: 50,
  total: 3,
  revisions: [REVISION_3, REVISION_2, REVISION_1],
};

export const DIFF_CHANGES: RevisionChange[] = [
  {
    op: "ADD",
    subject: CLASS_NODE,
    text: "Class: Pizza",
  },
  {
    op: "REMOVE",
    subject: CLASS_NODE,
    text: "Class: OldPizza",
  },
  {
    op: "ADD",
    subject: ONTOLOGY_NODE,
    text: "Ontology: <http://ex.org/ont>",
  },
];

export function revisionDiff(
  revNo: number,
  overrides: Partial<RevisionDiff> = {},
): RevisionDiff {
  return {
    revNo,
    changes: DIFF_CHANGES,
    limit: 200,
    total: DIFF_CHANGES.length,
    ...overrides,
  };
}
