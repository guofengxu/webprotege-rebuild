import { expectTypeOf, test } from "vitest";
import type {
  ClassFrame,
  EntityFrameChanged,
  EntityNode,
  IriValue,
  LiteralValue,
  ProjectStreamMessage,
  RevisionPage,
} from "../index";
import type { operations } from "../schema";

type Json200<Op extends "getHierarchyRoots" | "getHierarchyPaths" | "getClassFrame" | "listRevisions"> =
  operations[Op]["responses"][200]["content"]["application/json"];

test("hierarchy roots are EntityNode arrays", () => {
  expectTypeOf<Json200<"getHierarchyRoots">>().toEqualTypeOf<EntityNode[]>();
});

test("hierarchy paths are paths of EntityNode", () => {
  expectTypeOf<Json200<"getHierarchyPaths">>().toEqualTypeOf<EntityNode[][]>();
});

test("class frame response is ClassFrame", () => {
  expectTypeOf<Json200<"getClassFrame">>().toEqualTypeOf<ClassFrame>();
});

test("revision page response is RevisionPage", () => {
  expectTypeOf<Json200<"listRevisions">>().toEqualTypeOf<RevisionPage>();
});

test("entity node iri is required", () => {
  expectTypeOf<EntityNode["iri"]>().toEqualTypeOf<string>();
});

test("value node types use the json names", () => {
  expectTypeOf<IriValue["type"]>().toEqualTypeOf<"iri">();
  expectTypeOf<LiteralValue["type"]>().toEqualTypeOf<"literal">();
});

test("entity type is the closed set used by frames and revisions", () => {
  expectTypeOf<EntityNode["entityType"]>().toEqualTypeOf<
    | "CLASS"
    | "OBJECT_PROPERTY"
    | "DATA_PROPERTY"
    | "ANNOTATION_PROPERTY"
    | "NAMED_INDIVIDUAL"
    | "DATATYPE"
    | "IRI"
    | "ONTOLOGY"
  >();
});

test("stream messages narrow on the event type", () => {
  const message = null as unknown as ProjectStreamMessage;
  if (message.type === "EntityFrameChanged") {
    expectTypeOf(message).toEqualTypeOf<EntityFrameChanged>();
  }
});
