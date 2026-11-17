/** Shows only scheme + host (and port) for list cells; full URL stays in the edit dialog. */
export function displayPayloadHost(url: string): string {
  try {
    const parsed = new URL(url);
    return `${parsed.protocol}//${parsed.host}`;
  } catch {
    return "";
  }
}

export function isAbsoluteHttpUrl(value: string): boolean {
  try {
    const parsed = new URL(value);
    return (
      (parsed.protocol === "http:" || parsed.protocol === "https:") &&
      !/\s/.test(value)
    );
  } catch {
    return false;
  }
}
