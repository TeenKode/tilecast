import { describe, expect, it, vi } from "vitest";
import { installRandomUUIDCompatibility } from "./crypto-compat";

describe("HTTP-compatible randomUUID", () => {
  it("installs a cryptographically random RFC 4122 v4 UUID on insecure origins", () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => bytes.fill(0xff));
    const cryptoAPI = { getRandomValues } as unknown as Crypto;
    expect(installRandomUUIDCompatibility(cryptoAPI)).toBe(true);
    expect(cryptoAPI.randomUUID()).toBe("ffffffff-ffff-4fff-bfff-ffffffffffff");
    expect(getRandomValues).toHaveBeenCalledTimes(1);
    expect(getRandomValues.mock.calls[0]?.[0]).toHaveLength(16);
  });

  it("preserves the native method when present", () => {
    const randomUUID = vi.fn(() => "native-uuid");
    const getRandomValues = vi.fn();
    const cryptoAPI = { randomUUID, getRandomValues } as unknown as Crypto;
    expect(installRandomUUIDCompatibility(cryptoAPI)).toBe(true);
    expect(cryptoAPI.randomUUID()).toBe("native-uuid");
    expect(getRandomValues).not.toHaveBeenCalled();
  });

  it("does not use insecure entropy when getRandomValues is unavailable", () => {
    const cryptoAPI = {} as Crypto;
    expect(installRandomUUIDCompatibility(cryptoAPI)).toBe(false);
    expect("randomUUID" in cryptoAPI).toBe(false);
  });

  it("does not throw in a non-extensible crypto host", () => {
    const cryptoAPI = Object.freeze({
      getRandomValues: (bytes: Uint8Array) => bytes.fill(0),
    }) as unknown as Crypto;
    expect(installRandomUUIDCompatibility(cryptoAPI)).toBe(false);
  });
});
