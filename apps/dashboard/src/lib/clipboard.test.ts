// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText, writeTextToClipboard } from "./clipboard";
import { toast } from "../components/ui/toast";

vi.mock("../components/ui/toast", () => ({ toast: { add: vi.fn() } }));
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  Reflect.deleteProperty(document, "execCommand");
});

const feedback = { success: "Copiado.", failure: "No se pudo copiar." };

describe("writeTextToClipboard", () => {
  it("uses the Clipboard API when it is available", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });

    expect(await writeTextToClipboard("clipboard test")).toBe(true);
    expect(writeText).toHaveBeenCalledWith("clipboard test");
  });

  it("falls back to execCommand when Clipboard is unavailable", async () => {
    vi.stubGlobal("navigator", {});
    const execCommand = vi.fn().mockReturnValue(true);
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: execCommand,
    });

    expect(await writeTextToClipboard("clipboard test")).toBe(true);
    expect(execCommand).toHaveBeenCalledWith("copy");
    expect(document.querySelector("textarea")).toBeNull();
  });

  it("falls back when Clipboard rejects the write", async () => {
    vi.stubGlobal("navigator", {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
    });
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: vi.fn().mockReturnValue(true),
    });

    expect(await writeTextToClipboard("clipboard test")).toBe(true);
  });
});

describe("copyText", () => {
  it("reports success only after the clipboard write finishes", async () => {
    let finish!: () => void;
    const writeText = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    const result = copyText("clipboard test", feedback);
    expect(writeText).toHaveBeenCalledWith("clipboard test");
    expect(toast.add).not.toHaveBeenCalled();
    finish();
    expect(await result).toBe(true);
    expect(toast.add).toHaveBeenCalledExactlyOnceWith({
      title: feedback.success,
      type: "success",
    });
  });

  it("uses supplied localized failure feedback when both copy paths fail", async () => {
    vi.stubGlobal("navigator", {});
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: vi.fn().mockReturnValue(false),
    });

    expect(await copyText("clipboard test", feedback)).toBe(false);
    expect(toast.add).toHaveBeenCalledExactlyOnceWith({
      title: feedback.failure,
      type: "error",
    });
  });
});

describe("plain HTTP clipboard fallback", () => {
  it("copies with a temporary selection when the secure Clipboard API is missing", async () => {
    vi.stubGlobal("navigator", {});
    const existing = Object.getOwnPropertyDescriptor(document, "execCommand");
    const execCommand = vi.fn(() => true);
    Object.defineProperty(document, "execCommand", {
      configurable: true,
      value: execCommand,
    });
    const focused = document.createElement("input");
    document.body.appendChild(focused);
    focused.focus();
    try {
      expect(await copyText("LAN address", feedback)).toBe(true);
      expect(execCommand).toHaveBeenCalledExactlyOnceWith("copy");
      expect(document.querySelector("textarea")).toBeNull();
      expect(document.activeElement).toBe(focused);
      expect(toast.add).toHaveBeenCalledExactlyOnceWith({
        title: feedback.success,
        type: "success",
      });
    } finally {
      focused.remove();
      if (existing) Object.defineProperty(document, "execCommand", existing);
      else Reflect.deleteProperty(document, "execCommand");
    }
  });
});
