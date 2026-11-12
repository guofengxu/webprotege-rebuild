import { expect, test } from "vitest";
import { loadLocaleMessages, type LocaleMessage } from "./i18n";

test("zh-CN and en-US catalogs share the same key tree and non-empty leaves", () => {
  const zh = loadLocaleMessages("zh-CN");
  const en = loadLocaleMessages("en-US");

  expect(collectKeys(zh)).toEqual(collectKeys(en));
  expect(collectEmptyLeaves(zh)).toEqual([]);
  expect(collectEmptyLeaves(en)).toEqual([]);
});

function collectKeys(messages: LocaleMessage, prefix = ""): string[] {
  return Object.keys(messages)
    .sort()
    .flatMap((key) => {
      const path = prefix === "" ? key : `${prefix}.${key}`;
      const value = messages[key];
      if (typeof value === "string") {
        return [path];
      }
      if (value === undefined) {
        return [path];
      }
      return collectKeys(value, path);
    });
}

function collectEmptyLeaves(messages: LocaleMessage, prefix = ""): string[] {
  return Object.keys(messages)
    .sort()
    .flatMap((key) => {
      const path = prefix === "" ? key : `${prefix}.${key}`;
      const value = messages[key];
      if (typeof value === "string") {
        return value.trim() === "" ? [path] : [];
      }
      if (value === undefined) {
        return [path];
      }
      return collectEmptyLeaves(value, path);
    });
}
