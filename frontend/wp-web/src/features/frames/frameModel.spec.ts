import { expect, test } from "vitest";
import {
  cloneFrame,
  framesEqual,
  normalizeClassFrame,
  normalizeNamedIndividualFrame,
  normalizeObjectPropertyFrame,
  rawIriNode,
  type ClassFrame,
} from "./frameModel";

test("rawIriNode uses entityType IRI and local-name displayName", () => {
  expect(rawIriNode("http://example.org/ont#Thing")).toEqual({
    iri: "http://example.org/ont#Thing",
    entityType: "IRI",
    displayName: "Thing",
    deprecated: false,
    hasChildren: false,
  });
  expect(rawIriNode("http://example.org/ont/Foo").displayName).toBe("Foo");
});

test("normalize*Frame fills missing arrays with empty lists", () => {
  const classFrame = normalizeClassFrame({
    subject: rawIriNode("http://ex.org#C"),
  });
  expect(classFrame.annotations).toEqual([]);
  expect(classFrame.parents).toEqual([]);
  expect(classFrame.propertyValues).toEqual([]);
  expect(classFrame.otherAxioms).toEqual([]);

  const objectFrame = normalizeObjectPropertyFrame({});
  expect(objectFrame.characteristics).toEqual([]);
  expect(objectFrame.domains).toEqual([]);
  expect(objectFrame.inverses).toEqual([]);
  expect(objectFrame.ranges).toEqual([]);

  const individual = normalizeNamedIndividualFrame({});
  expect(individual.types).toEqual([]);
  expect(individual.sameIndividuals).toEqual([]);
  expect(individual.objectPropertyAssertions).toEqual([]);
  expect(individual.dataPropertyAssertions).toEqual([]);
});

test("framesEqual is true for clones and false after a label change", () => {
  const frame: ClassFrame = {
    subject: {
      iri: "http://ex.org#C",
      entityType: "CLASS",
      displayName: "C",
      deprecated: false,
      hasChildren: false,
    },
    annotations: [],
    parents: [],
    propertyValues: [],
    otherAxioms: [],
  };
  expect(framesEqual(frame, cloneFrame(frame))).toBe(true);

  const changed = cloneFrame(frame);
  changed.subject = {
    ...changed.subject!,
    displayName: "Renamed",
  };
  expect(framesEqual(frame, changed)).toBe(false);
});
