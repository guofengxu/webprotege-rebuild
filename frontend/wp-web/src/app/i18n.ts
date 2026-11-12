import { computed, type Ref } from "vue";
import { createI18n, useI18n } from "vue-i18n";

export type AppLocale = "zh-CN" | "en-US";

export const SUPPORTED_LOCALES = ["zh-CN", "en-US"] as const satisfies readonly AppLocale[];

const LOCALE_STORAGE_KEY = "wp.locale";

export type LocaleMessage = {
  readonly [key: string]: string | LocaleMessage;
};

export type LocaleModule = {
  readonly default: LocaleMessage;
};

const localeModules = import.meta.glob<LocaleModule>(
  "/src/**/locales/{zh-CN,en-US}.ts",
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

export function isAppLocale(value: string): value is AppLocale {
  return (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

export function resolveInitialLocale(): AppLocale {
  const stored = readStoredLocale();
  if (stored !== undefined) {
    return stored;
  }
  if (
    typeof navigator !== "undefined" &&
    navigator.language.toLowerCase().startsWith("zh")
  ) {
    return "zh-CN";
  }
  return "en-US";
}

export function createAppI18n(locale: AppLocale = resolveInitialLocale()) {
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

export function applyDocumentLang(locale: AppLocale): void {
  if (typeof document !== "undefined") {
    document.documentElement.lang = locale;
  }
}

export function useAppLocale(): {
  locale: Ref<AppLocale>;
  setLocale: (locale: AppLocale) => void;
} {
  const i18n = useI18n({ useScope: "global" });
  const locale = computed<AppLocale>(() =>
    isAppLocale(i18n.locale.value) ? i18n.locale.value : resolveInitialLocale(),
  );

  function setLocale(next: AppLocale): void {
    if (!isAppLocale(next)) {
      return;
    }
    i18n.locale.value = next;
    applyDocumentLang(next);
    writeStoredLocale(next);
  }

  applyDocumentLang(locale.value);
  return { locale, setLocale };
}

function readStoredLocale(): AppLocale | undefined {
  try {
    if (typeof localStorage === "undefined") {
      return undefined;
    }
    const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
    return stored !== null && isAppLocale(stored) ? stored : undefined;
  } catch {
    return undefined;
  }
}

function writeStoredLocale(locale: AppLocale): void {
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(LOCALE_STORAGE_KEY, locale);
    }
  } catch {
    // Private mode / quota — keep the in-memory locale.
  }
}
