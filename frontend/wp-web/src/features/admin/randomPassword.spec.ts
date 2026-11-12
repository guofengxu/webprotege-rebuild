import { expect, test } from "vitest";
import { randomPassword } from "./randomPassword";

test("default length is 20", () => {
  expect(randomPassword()).toHaveLength(20);
});

test("only contains letters and digits", () => {
  for (let i = 0; i < 20; i += 1) {
    expect(randomPassword(32)).toMatch(/^[A-Za-z0-9]+$/);
  }
});

test("100 consecutive generations are unique", () => {
  const seen = new Set<string>();
  for (let i = 0; i < 100; i += 1) {
    seen.add(randomPassword());
  }
  expect(seen.size).toBe(100);
});
