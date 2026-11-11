import type { EntityNode } from "@wp/api-types";
import { expectTypeOf, test } from "vitest";

test("EntityNode from @wp/api-types has an iri field", () => {
  expectTypeOf<EntityNode>().toHaveProperty("iri");
});
