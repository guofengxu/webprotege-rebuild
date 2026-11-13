import type {
  AnnotationPropertyFrame,
  AnnotationValue,
  ClassFrame,
  DataPropertyFrame,
  EntityNode,
  IriValue,
  LiteralValue,
  NamedIndividualFrame,
  ObjectPropertyFrame,
  PropertyValue,
} from "@wp/api-types";
import { iriLocalName } from "@/features/entities/iriLocalName";

export type {
  AnnotationPropertyFrame,
  AnnotationValue,
  ClassFrame,
  DataPropertyFrame,
  EntityNode,
  IriValue,
  LiteralValue,
  NamedIndividualFrame,
  ObjectPropertyFrame,
  PropertyValue,
};

export function isIriValue(
  value: IriValue | LiteralValue | undefined,
): value is IriValue {
  return value !== undefined && value.type === "iri";
}

export function isLiteralValue(
  value: IriValue | LiteralValue | undefined,
): value is LiteralValue {
  return value !== undefined && value.type === "literal";
}

export function iriValue(entity: EntityNode): IriValue {
  return { type: "iri", entity };
}

export function literalValue(
  lexical: string,
  datatype: string,
  lang?: string,
): LiteralValue {
  return lang === undefined
    ? { type: "literal", lexical, datatype }
    : { type: "literal", lexical, datatype, lang };
}

export function rawIriNode(iri: string): EntityNode {
  return {
    iri,
    entityType: "IRI",
    displayName: iriLocalName(iri),
    deprecated: false,
    hasChildren: false,
  };
}

export function cloneFrame<F>(frame: F): F {
  // JSON round-trip avoids DataCloneError on Vue reactive proxies from query cache.
  return JSON.parse(JSON.stringify(frame)) as F;
}

export function framesEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

function copyList<T>(value: readonly T[] | undefined): T[] {
  return value === undefined ? [] : [...value];
}

export function normalizeClassFrame(frame: ClassFrame): ClassFrame {
  return {
    ...frame,
    annotations: copyList(frame.annotations),
    otherAxioms: copyList(frame.otherAxioms),
    parents: copyList(frame.parents),
    propertyValues: copyList(frame.propertyValues),
  };
}

export function normalizeObjectPropertyFrame(
  frame: ObjectPropertyFrame,
): ObjectPropertyFrame {
  return {
    ...frame,
    annotations: copyList(frame.annotations),
    characteristics: copyList(frame.characteristics),
    domains: copyList(frame.domains),
    inverses: copyList(frame.inverses),
    otherAxioms: copyList(frame.otherAxioms),
    parents: copyList(frame.parents),
    ranges: copyList(frame.ranges),
  };
}

export function normalizeDataPropertyFrame(
  frame: DataPropertyFrame,
): DataPropertyFrame {
  return {
    ...frame,
    annotations: copyList(frame.annotations),
    domains: copyList(frame.domains),
    otherAxioms: copyList(frame.otherAxioms),
    parents: copyList(frame.parents),
    ranges: copyList(frame.ranges),
  };
}

export function normalizeAnnotationPropertyFrame(
  frame: AnnotationPropertyFrame,
): AnnotationPropertyFrame {
  return {
    ...frame,
    annotations: copyList(frame.annotations),
    domains: copyList(frame.domains),
    otherAxioms: copyList(frame.otherAxioms),
    parents: copyList(frame.parents),
    ranges: copyList(frame.ranges),
  };
}

export function normalizeNamedIndividualFrame(
  frame: NamedIndividualFrame,
): NamedIndividualFrame {
  return {
    ...frame,
    annotations: copyList(frame.annotations),
    dataPropertyAssertions: copyList(frame.dataPropertyAssertions),
    objectPropertyAssertions: copyList(frame.objectPropertyAssertions),
    otherAxioms: copyList(frame.otherAxioms),
    sameIndividuals: copyList(frame.sameIndividuals),
    types: copyList(frame.types),
  };
}
