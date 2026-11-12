const MAX_NAMES = 100;

export type ParsedNames =
  | { ok: true; names: string[] }
  | { ok: false; reason: "empty" | "tooMany" };

/** Trim lines, drop empties, dedupe (first wins), cap at 100. */
export function parseNames(raw: string): ParsedNames {
  const seen = new Set<string>();
  const names: string[] = [];
  for (const line of raw.split(/\r?\n/)) {
    const name = line.trim();
    if (name.length === 0 || seen.has(name)) {
      continue;
    }
    seen.add(name);
    names.push(name);
  }
  if (names.length === 0) {
    return { ok: false, reason: "empty" };
  }
  if (names.length > MAX_NAMES) {
    return { ok: false, reason: "tooMany" };
  }
  return { ok: true, names };
}

export const MAX_ENTITY_NAMES = MAX_NAMES;
