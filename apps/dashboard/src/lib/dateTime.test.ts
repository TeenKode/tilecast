import { describe, expect, it } from "vitest";
import {
  formatDateTime,
  localDateTimeToRfc3339,
  rfc3339ToLocalDateTime,
} from "./dateTime";

describe("Studio timestamp display", () => {
  it("can show exact seconds without changing minute-only callers", () => {
    const instant = "2026-01-18T12:34:25Z";
    expect(formatDateTime(instant, "en-US", "—", true)).toMatch(/:25\b/);
    expect(formatDateTime(instant, "en-US")).not.toMatch(/:25\b/);
  });

  it.each([
    ["en-US", /Jan/],
    ["es-ES", /ene/],
    ["ru-RU", /янв/],
  ])("uses the supplied %s locale", (locale, month) => {
    const displayed = formatDateTime("2026-01-18T12:34:00Z", locale);
    expect(displayed).toMatch(month);
    expect(displayed).toContain("2026");
    expect(displayed).toMatch(/\d{1,2}:34/);
  });

  it.each([undefined, null, "", "invalid", "2026-99-99T00:00:00Z"])(
    "uses a caller's missing label for %s",
    (value) => {
      expect(formatDateTime(value, "en-US")).toBe("—");
      expect(formatDateTime(value, "es-ES", "Desconocido")).toBe("Desconocido");
    },
  );
});

describe("datetime input precision", () => {
  it("round-trips seconds when requested and keeps minute-only callers compatible", () => {
    const instant = "2026-01-18T12:34:25.000Z";
    const local = rfc3339ToLocalDateTime(instant, true);
    expect(local).toMatch(/:25$/);
    expect(localDateTimeToRfc3339(local)).toBe(instant);
    expect(rfc3339ToLocalDateTime(instant)).toBe(local.slice(0, 16));
    expect(rfc3339ToLocalDateTime("invalid", true)).toBe("");
  });
});
