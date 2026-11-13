import type { EntityNode } from "@wp/api-types";

/** Path separator for tree node keys (same IRI may appear under multiple parents). */
export const KEY_SEPARATOR = "\u0000";

export type TreeNodeData = {
  readonly key: string;
  readonly iri: string;
  readonly label: string;
  readonly deprecated: boolean;
  /** Original server node; null for the lazy-load placeholder. */
  readonly node: EntityNode | null;
  readonly placeholder?: boolean;
  readonly children?: readonly TreeNodeData[];
};

const ENTITY_TYPES = new Set([
  "CLASS",
  "OBJECT_PROPERTY",
  "DATA_PROPERTY",
  "ANNOTATION_PROPERTY",
  "NAMED_INDIVIDUAL",
  "DATATYPE",
  "IRI",
  "ONTOLOGY",
]);

export function isEntityNode(value: unknown): value is EntityNode {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record["iri"] === "string" &&
    typeof record["displayName"] === "string" &&
    typeof record["deprecated"] === "boolean" &&
    typeof record["hasChildren"] === "boolean" &&
    typeof record["entityType"] === "string" &&
    ENTITY_TYPES.has(record["entityType"])
  );
}

export function asEntityNodes(value: unknown): EntityNode[] | null {
  if (!Array.isArray(value) || !value.every(isEntityNode)) {
    return null;
  }
  return value;
}

export function pathKey(pathIris: readonly string[]): string {
  return pathIris.join(KEY_SEPARATOR);
}

export function parentKeyOf(nodeKey: string): string | null {
  const index = nodeKey.lastIndexOf(KEY_SEPARATOR);
  if (index < 0) {
    return null;
  }
  return nodeKey.slice(0, index);
}

export function toTreeNode(
  entity: EntityNode,
  parentPath: readonly string[],
): TreeNodeData {
  const path = [...parentPath, entity.iri];
  const key = pathKey(path);
  return {
    key,
    iri: entity.iri,
    label: entity.displayName,
    deprecated: entity.deprecated,
    node: entity,
    children: entity.hasChildren ? [placeholderChild(key)] : undefined,
  };
}

export function placeholderChild(parentKey: string): TreeNodeData {
  return {
    key: `${parentKey}${KEY_SEPARATOR}__placeholder__`,
    iri: "",
    label: "",
    deprecated: false,
    node: null,
    placeholder: true,
  };
}

export function isLoaded(node: TreeNodeData): boolean {
  if (node.placeholder === true || node.node === null) {
    return false;
  }
  if (node.node.hasChildren !== true) {
    return true;
  }
  const children = node.children ?? [];
  return children.length > 0 && children.every((child) => child.placeholder !== true);
}

export function findNodeByKey(
  nodes: readonly TreeNodeData[],
  key: string,
): TreeNodeData | null {
  for (const node of nodes) {
    if (node.key === key) {
      return node;
    }
    const nested = findNodeByKey(node.children ?? [], key);
    if (nested !== null) {
      return nested;
    }
  }
  return null;
}

export function findNodeByIri(
  nodes: readonly TreeNodeData[],
  iri: string,
): TreeNodeData | null {
  for (const node of nodes) {
    if (node.iri === iri && node.placeholder !== true) {
      return node;
    }
    const nested = findNodeByIri(node.children ?? [], iri);
    if (nested !== null) {
      return nested;
    }
  }
  return null;
}

export function collectKeys(nodes: readonly TreeNodeData[]): Set<string> {
  const keys = new Set<string>();
  walk(nodes, (node) => {
    keys.add(node.key);
  });
  return keys;
}

export function walk(
  nodes: readonly TreeNodeData[],
  visit: (node: TreeNodeData) => void,
): void {
  for (const node of nodes) {
    visit(node);
    if (node.children !== undefined) {
      walk(node.children, visit);
    }
  }
}

/**
 * Rebuild children for every occurrence of `parentIri`, preserving loaded
 * subtrees for children that still exist under the same path key.
 */
export function replaceChildrenForParentIri(
  nodes: readonly TreeNodeData[],
  parentIri: string,
  entities: readonly EntityNode[],
): TreeNodeData[] {
  return nodes.map((node) => mapReplace(node, parentIri, entities));
}

function mapReplace(
  node: TreeNodeData,
  parentIri: string,
  entities: readonly EntityNode[],
): TreeNodeData {
  const mappedChildren =
    node.children === undefined
      ? undefined
      : node.children.map((child) => mapReplace(child, parentIri, entities));

  if (node.iri !== parentIri || node.placeholder === true) {
    if (mappedChildren === undefined && node.children === undefined) {
      return node;
    }
    if (
      mappedChildren !== undefined &&
      node.children !== undefined &&
      sameNodeList(mappedChildren, node.children)
    ) {
      return node;
    }
    return { ...node, children: mappedChildren };
  }

  const previousByKey = new Map(
    (mappedChildren ?? node.children ?? [])
      .filter((child) => child.placeholder !== true)
      .map((child) => [child.key, child] as const),
  );
  const parentPath = node.key.split(KEY_SEPARATOR);
  const nextChildren = entities.map((entity) => {
    const fresh = toTreeNode(entity, parentPath);
    const previous = previousByKey.get(fresh.key);
    if (entity.hasChildren !== true) {
      return { ...fresh, children: undefined };
    }
    // A former leaf is "loaded" with children === undefined; keep fresh's
    // placeholder so the expand arrow appears after live hierarchy updates.
    if (
      previous === undefined ||
      !isLoaded(previous) ||
      previous.children === undefined
    ) {
      return fresh;
    }
    return {
      ...fresh,
      children: [...previous.children],
    };
  });
  return { ...node, children: nextChildren };
}

function sameNodeList(
  left: readonly TreeNodeData[],
  right: readonly TreeNodeData[],
): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((node, index) => node === right[index]);
}

export function replaceRoots(
  entities: readonly EntityNode[],
  previous: readonly TreeNodeData[],
): TreeNodeData[] {
  const previousByKey = new Map(
    previous
      .filter((node) => node.placeholder !== true)
      .map((node) => [node.key, node] as const),
  );
  return entities.map((entity) => {
    const fresh = toTreeNode(entity, []);
    const prior = previousByKey.get(fresh.key);
    if (entity.hasChildren !== true) {
      return { ...fresh, children: undefined };
    }
    if (
      prior === undefined ||
      !isLoaded(prior) ||
      prior.children === undefined
    ) {
      return fresh;
    }
    return {
      ...fresh,
      children: [...prior.children],
    };
  });
}
