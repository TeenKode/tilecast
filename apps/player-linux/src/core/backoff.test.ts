import { describe, expect, it, vi } from "vitest";
import { ReconnectBackoff, reconnectElapsedMs } from "./backoff";

const options = {
  baseDelayMs: 1_000,
  maxDelayMs: 60_000,
  healthyResetMs: 120_000,
  random: () => 1, // deterministic: always the ceiling
};

describe("ReconnectBackoff", () => {
  it("grows exponentially up to the cap", () => {
    const backoff = new ReconnectBackoff(options);
    const delays = [];
    let now = 0;
    for (let i = 0; i < 8; i++) {
      delays.push(backoff.onDisconnected(now));
      now += 1;
    }
    expect(delays).toEqual([
      1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000,
    ]);
  });

  it("resets the streak after a sustained healthy connection", () => {
    const backoff = new ReconnectBackoff(options);
    backoff.onDisconnected(0);
    backoff.onDisconnected(1_000);
    backoff.onDisconnected(2_000);
    expect(backoff.failureStreak).toBe(3);

    backoff.onConnected(10_000);
    // Healthy for 3 minutes, then a blip: streak restarts at 1.
    const delay = backoff.onDisconnected(10_000 + 180_000);
    expect(delay).toBe(1_000);
    expect(backoff.failureStreak).toBe(1);
  });

  it("does not reset after a short-lived connection", () => {
    const backoff = new ReconnectBackoff(options);
    backoff.onDisconnected(0);
    backoff.onDisconnected(1_000);
    backoff.onConnected(2_000);
    // Connection lasted only 5 seconds — still the same outage.
    const delay = backoff.onDisconnected(7_000);
    expect(delay).toBe(4_000);
  });

  it("measures reconnect health with a monotonic clock despite RTC changes", () => {
    const monotonic = vi.spyOn(performance, "now");
    const wallClock = vi.spyOn(Date, "now");
    try {
      const backoff = new ReconnectBackoff(options);
      backoff.onDisconnected(0);
      backoff.onDisconnected(1_000);
      expect(backoff.failureStreak).toBe(2);

      monotonic.mockReturnValue(10_000);
      wallClock.mockReturnValue(1_000_000);
      backoff.onConnected(reconnectElapsedMs());

      // NTP jumps forward by an hour, but only 5 seconds elapsed.
      monotonic.mockReturnValue(15_000);
      wallClock.mockReturnValue(4_600_000);
      expect(backoff.onDisconnected(reconnectElapsedMs())).toBe(4_000);
      expect(backoff.failureStreak).toBe(3);

      monotonic.mockReturnValue(20_000);
      backoff.onConnected(reconnectElapsedMs());

      // RTC moves backward while the socket actually remains healthy.
      monotonic.mockReturnValue(150_000);
      wallClock.mockReturnValue(100_000);
      expect(backoff.onDisconnected(reconnectElapsedMs())).toBe(1_000);
      expect(backoff.failureStreak).toBe(1);
    } finally {
      monotonic.mockRestore();
      wallClock.mockRestore();
    }
  });

  it("keeps a jitter floor above zero", () => {
    const backoff = new ReconnectBackoff({ ...options, random: () => 0 });
    const delay = backoff.onDisconnected(0);
    expect(delay).toBeGreaterThanOrEqual(500);
  });
});
