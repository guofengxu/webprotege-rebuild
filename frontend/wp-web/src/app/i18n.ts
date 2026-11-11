import { createI18n } from "vue-i18n";

export type AppLocale = "zh-CN" | "en-US";

export type LocaleMessage = {
  readonly [key: string]: string | LocaleMessage;
};

export type LocaleModule = {
  readonly default: LocaleMessage;
};

const localeModules = import.meta.glob<LocaleModule>(
  "/src/**/locales/*.ts",
  { eager: true },
);

export function mergeLocaleMessages(
  modules: Readonly<Record<string, LocaleModule>>,
): LocaleMessage {
  return Object.entries(modules).reduce<Record<string, string | LocaleMessage>>(
    (messages, [path, localeModule]) => {
      const keys = Object.keys(localeModule.default);
      if (keys.length !== 1) {
        throw new Error(
          `Locale module ${path} must export exactly one top-level key, found: ${keys.join(", ") || "(none)"}`,
        );
      }
      const key = keys[0];
      if (key === undefined) {
        throw new Error(
          `Locale module ${path} must export exactly one top-level key`,
        );
      }
      if (Object.hasOwn(messages, key)) {
        throw new Error(`Duplicate top-level locale key "${key}" from ${path}`);
      }
      const value = localeModule.default[key];
      if (value === undefined) {
        throw new Error(`Locale module ${path} is missing a value for "${key}"`);
      }
      return { ...messages, [key]: value };
    },
    {},
  );
}

function modulesFor(locale: AppLocale): Record<string, LocaleModule> {
  return Object.fromEntries(
    Object.entries(localeModules).filter(([path]) =>
      path.endsWith(`/${locale}.ts`),
    ),
  );
}

export function loadLocaleMessages(locale: AppLocale): LocaleMessage {
  return mergeLocaleMessages(modulesFor(locale));
}

export function createAppI18n(locale: AppLocale = "zh-CN") {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: "en-US",
    messages: {
      "zh-CN": loadLocaleMessages("zh-CN"),
      "en-US": loadLocaleMessages("en-US"),
    },
  });
}
