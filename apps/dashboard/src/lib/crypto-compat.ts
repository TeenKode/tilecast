/**
 * `crypto.randomUUID()` is hidden by browsers on HTTP LAN origins, even though
 * `crypto.getRandomValues()` remains available. Install the same RFC 4122 v4
 * UUID behavior before Studio (including plugins and lazy routes) is loaded.
 * Never fall back to Math.random() for identifiers.
 */
export function installRandomUUIDCompatibility(
  cryptoAPI: Crypto | undefined = globalThis.crypto,
): boolean {
  if (!cryptoAPI) return false;
  if (typeof cryptoAPI.randomUUID === "function") return true;
  if (typeof cryptoAPI.getRandomValues !== "function") return false;

  try {
    Object.defineProperty(cryptoAPI, "randomUUID", {
      configurable: true,
      writable: true,
      value: () => {
        const bytes = cryptoAPI.getRandomValues(new Uint8Array(16));
        bytes[6] = (bytes[6]! & 0x0f) | 0x40;
        bytes[8] = (bytes[8]! & 0x3f) | 0x80;
        const hex = Array.from(bytes, (byte) =>
          byte.toString(16).padStart(2, "0"),
        ).join("");
        return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
      },
    });
  } catch {
    // A locked-down host may forbid extending crypto; don't weaken randomness.
    return false;
  }
  return typeof cryptoAPI.randomUUID === "function";
}

installRandomUUIDCompatibility();
