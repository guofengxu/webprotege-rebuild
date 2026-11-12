const ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const ALPHABET_LENGTH = ALPHABET.length;
/** Largest multiple of ALPHABET_LENGTH that fits in a uint8 (rejection sampling). */
const MAX_UNBIASED = Math.floor(256 / ALPHABET_LENGTH) * ALPHABET_LENGTH;

/**
 * Uniform random password from [A-Za-z0-9] using rejection sampling
 * (no modulo bias).
 */
export function randomPassword(length = 20): string {
  if (!Number.isInteger(length) || length < 1) {
    throw new RangeError("length must be a positive integer");
  }
  const chars: string[] = [];
  const buffer = new Uint8Array(Math.max(length * 2, 32));
  while (chars.length < length) {
    crypto.getRandomValues(buffer);
    for (const value of buffer) {
      if (value >= MAX_UNBIASED) {
        continue;
      }
      chars.push(ALPHABET[value % ALPHABET_LENGTH]!);
      if (chars.length === length) {
        break;
      }
    }
  }
  return chars.join("");
}
