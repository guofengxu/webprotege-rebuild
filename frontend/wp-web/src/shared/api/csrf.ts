const CSRF_COOKIE_NAME = "XSRF-TOKEN";

/** Reads the SPA CSRF cookie. Call this per request: login rotates the token. */
export function readCsrfToken(cookie = document.cookie): string | undefined {
  const pair = cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${CSRF_COOKIE_NAME}=`));
  if (pair === undefined) {
    return undefined;
  }
  const raw = pair.slice(CSRF_COOKIE_NAME.length + 1);
  if (raw.length === 0) {
    return undefined;
  }
  try {
    const decoded = decodeURIComponent(raw);
    return decoded.length === 0 ? undefined : decoded;
  } catch {
    return undefined;
  }
}
