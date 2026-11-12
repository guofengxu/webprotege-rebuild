import type { EntityNode } from "@wp/api-types";

export const HIERARCHY_KINDS = [
  "class",
  "object-property",
  "data-property",
  "annotation-property",
] as const;

export type HierarchyKind = (typeof HIERARCHY_KINDS)[number];

export const WORKSPACE_KINDS = [...HIERARCHY_KINDS, "individual"] as const;

export type WorkspaceKind = (typeof WORKSPACE_KINDS)[number];

export type EntityTypeName = EntityNode["entityType"];

export type SearchType =
  | "class"
  | "object-property"
  | "data-property"
  | "annotation-property"
  | "named-individual"
  | "datatype";

const KIND_TO_ENTITY_TYPE = {
  class: "CLASS",
  "object-property": "OBJECT_PROPERTY",
  "data-property": "DATA_PROPERTY",
  "annotation-property": "ANNOTATION_PROPERTY",
  individual: "NAMED_INDIVIDUAL",
} as const satisfies Record<WorkspaceKind, EntityTypeName>;

const ENTITY_TYPE_TO_KIND: Record<EntityTypeName, WorkspaceKind | null> = {
  CLASS: "class",
  OBJECT_PROPERTY: "object-property",
  DATA_PROPERTY: "data-property",
  ANNOTATION_PROPERTY: "annotation-property",
  NAMED_INDIVIDUAL: "individual",
  DATATYPE: null,
  IRI: null,
  ONTOLOGY: null,
};

const ENTITY_TYPE_TO_SEARCH_TYPE: Record<EntityTypeName, SearchType | null> = {
  CLASS: "class",
  OBJECT_PROPERTY: "object-property",
  DATA_PROPERTY: "data-property",
  ANNOTATION_PROPERTY: "annotation-property",
  NAMED_INDIVIDUAL: "named-individual",
  DATATYPE: "datatype",
  IRI: null,
  ONTOLOGY: null,
};

export function isWorkspaceKind(value: string): value is WorkspaceKind {
  return (WORKSPACE_KINDS as readonly string[]).includes(value);
}

export function kindToEntityType(kind: WorkspaceKind): EntityTypeName {
  return KIND_TO_ENTITY_TYPE[kind];
}

export function entityTypeToKind(entityType: EntityTypeName): WorkspaceKind | null {
  return ENTITY_TYPE_TO_KIND[entityType];
}

export function searchTypeOf(entityType: EntityTypeName): SearchType | null {
  return ENTITY_TYPE_TO_SEARCH_TYPE[entityType];
}

export function joinSearchTypes(types: readonly SearchType[]): string {
  return types.join(",");
}
