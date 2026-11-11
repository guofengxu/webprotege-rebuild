import { expect, test } from "vitest";
import { loadLocaleMessages, mergeLocaleMessages } from "./i18n";

test("merging locale modules throws when a top-level key is repeated", () => {
  expect(() =>
    mergeLocaleMessages({
      "/src/features/a/locales/zh-CN.ts": {
        default: { app: { title: "A" } },
      },
      "/src/features/b/locales/zh-CN.ts": {
        default: { app: { title: "B" } },
      },
    }),
  ).toThrow(/app/);
});

test("zh-CN and en-US catalogs have the same top-level keys", () => {
  expect(Object.keys(loadLocaleMessages("en-US")).sort()).toEqual(
    Object.keys(loadLocaleMessages("zh-CN")).sort(),
  );
});
