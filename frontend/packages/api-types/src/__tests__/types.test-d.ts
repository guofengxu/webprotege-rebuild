import { expectTypeOf, test } from "vitest";
import type { ClassFrame, EntityNode, ProblemDetail } from "../index";

test("EntityNode exposes iri and displayName", () => {
  expectTypeOf<EntityNode>().toHaveProperty("iri");
  expectTypeOf<EntityNode>().toHaveProperty("displayName");
});

test("ClassFrame exposes the subject entity", () => {
  expectTypeOf<ClassFrame>().toHaveProperty("subject");
  expectTypeOf<NonNullable<ClassFrame["subject"]>>().toHaveProperty("iri");
});

test("ProblemDetail exposes status and the application code", () => {
  expectTypeOf<ProblemDetail["code"]>().toEqualTypeOf<string | undefined>();
  expectTypeOf<ProblemDetail["status"]>().toEqualTypeOf<number | undefined>();
});
