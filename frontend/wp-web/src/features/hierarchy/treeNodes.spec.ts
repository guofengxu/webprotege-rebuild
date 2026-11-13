import type { EntityNode } from "@wp/api-types";
import { expect, test } from "vitest";
import {
  KEY_SEPARATOR,
  replaceChildrenForParentIri,
  replaceRoots,
  toTreeNode,
} from "./treeNodes";

function entity(
  iri: string,
  displayName: string,
  hasChildren: boolean,
): EntityNode {
  return {
    iri,
    displayName,
    deprecated: false,
    hasChildren,
    entityType: "CLASS",
  };
}

test("replaceChildrenForParentIri gives a former leaf a placeholder when hasChildren becomes true", () => {
  const parentIri = "http://example.org/Pallet";
  const childIri = "http://example.org/Turnover";
  const underRoot = [
    {
      key: "http://example.org/Root",
      iri: "http://example.org/Root",
      label: "Root",
      deprecated: false,
      node: entity("http://example.org/Root", "Root", true),
      children: [
        {
          ...toTreeNode(entity(parentIri, "托盘", false), [
            "http://example.org/Root",
          ]),
          children: undefined,
        },
      ],
    },
  ];

  const updated = replaceChildrenForParentIri(
    underRoot,
    "http://example.org/Root",
    [entity(parentIri, "托盘", true)],
  );
  const pallet = updated[0]?.children?.[0];
  expect(pallet?.node?.hasChildren).toBe(true);
  expect(pallet?.children).toHaveLength(1);
  expect(pallet?.children?.[0]?.placeholder).toBe(true);

  const withChild = replaceChildrenForParentIri(updated, parentIri, [
    entity(childIri, "周转托盘", false),
  ]);
  const loaded = withChild[0]?.children?.[0];
  expect(loaded?.children?.map((c) => c.iri)).toEqual([childIri]);
  expect(loaded?.key).toBe(
    ["http://example.org/Root", parentIri].join(KEY_SEPARATOR),
  );
});

test("replaceRoots keeps a placeholder when a former leaf root gains hasChildren", () => {
  const iri = "http://example.org/Pallet";
  const previous = [
    { ...toTreeNode(entity(iri, "托盘", false), []), children: undefined },
  ];
  const next = replaceRoots([entity(iri, "托盘", true)], previous);
  expect(next[0]?.children).toHaveLength(1);
  expect(next[0]?.children?.[0]?.placeholder).toBe(true);
});
