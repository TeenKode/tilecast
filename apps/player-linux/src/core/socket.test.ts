import { describe, expect, it } from "vitest";
import {
  classifyDisconnectReason,
  parseSocketControlMessage,
  parseSocketRevision,
} from "./socket";

// The category is what telemetry reports; the underlying text stays in the log.
// These are the exact strings the socket produces.
describe("disconnect classification", () => {
  it("names a revoked credential rather than a network fault", () => {
    // The server closes with a policy violation for a revoked credential or a
    // disabled screen. Reporting that as "network lost" would send an operator
    // to check a switch instead of the screen's record.
    expect(classifyDisconnectReason("close 1008 revoked", true, false)).toBe(
      "credential_rejected",
    );
  });

  it("distinguishes our own shutdown from the peer's", () => {
    // A deliberate close terminates the socket, which produces the same 1006 a
    // dead peer does. Only the local flag tells them apart.
    expect(classifyDisconnectReason("close 1006 ", false, true)).toBe(
      "client_closed",
    );
    expect(
      classifyDisconnectReason("close 1001 going away", false, false),
    ).toBe("server_closed");
  });

  it("separates a name that will not resolve from a network that is down", () => {
    expect(
      classifyDisconnectReason(
        "error Error: getaddrinfo ENOTFOUND tilecast.local",
        false,
        false,
      ),
    ).toBe("server_unreachable");
    expect(
      classifyDisconnectReason(
        "error Error: connect ECONNREFUSED 10.0.0.5:443",
        false,
        false,
      ),
    ).toBe("server_unreachable");
    expect(
      classifyDisconnectReason(
        "error Error: connect ENETUNREACH 10.0.0.5:443",
        false,
        false,
      ),
    ).toBe("network_lost");
  });

  it("names a certificate problem as one", () => {
    expect(
      classifyDisconnectReason(
        "error Error: self-signed certificate in certificate chain",
        false,
        false,
      ),
    ).toBe("tls_failure");
  });

  it("treats the liveness watchdog as a timeout", () => {
    // A half-open socket delivers nothing while looking open, and the watchdog
    // is the only thing that notices. That is a timeout, not a clean close.
    expect(classifyDisconnectReason("liveness timeout", false, false)).toBe(
      "timeout",
    );
  });

  it("falls back to unknown rather than guessing", () => {
    expect(
      classifyDisconnectReason("error Error: something new", false, false),
    ).toBe("unknown");
  });
});

describe("untrusted socket control messages", () => {
  it("rejects malformed JSON and non-object envelopes", () => {
    for (const input of [
      "{",
      "null",
      "42",
      "true",
      '"manifest.changed"',
      "[]",
      '{"type":null}',
      '{"type":3}',
      '{"manifestVersion":5}',
    ]) {
      expect(parseSocketControlMessage(input)).toBeNull();
    }
  });

  it("accepts an object with a string message type", () => {
    expect(
      parseSocketControlMessage(
        '{"type":"manifest.changed","manifestVersion":7}',
      ),
    ).toMatchObject({ type: "manifest.changed", manifestVersion: 7 });
  });

  it("accepts non-negative safe integer revisions, including numeric strings", () => {
    expect(parseSocketRevision(0)).toBe(0);
    expect(parseSocketRevision(12)).toBe(12);
    expect(parseSocketRevision("12")).toBe(12);
  });

  it("rejects invalid or ambiguous revision values", () => {
    for (const value of [
      null,
      undefined,
      "",
      " ",
      true,
      false,
      -1,
      -0.5,
      1.5,
      "1.5",
      "Infinity",
      "NaN",
      Number.POSITIVE_INFINITY,
      Number.MAX_SAFE_INTEGER + 1,
      {},
      [],
    ]) {
      expect(parseSocketRevision(value)).toBeNull();
    }
  });
});
