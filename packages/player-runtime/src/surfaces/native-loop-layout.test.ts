// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManualClock } from "../clock/scheduler";
import type { RuntimeItem } from "../host/contract";
import type { SurfaceSink } from "./surface";
import { LayoutSurface } from "./layout-surface";

const item: RuntimeItem = {
  id: "layout-1",
  kind: "layout",
  src: "",
  durationMs: null,
  fitMode: "contain",
  audioEnabled: false,
  volume: 0,
  videoStartOffsetMs: null,
  videoEndOffsetMs: null,
  layout: {
    canvasWidth: 1920,
    canvasHeight: 1080,
    background: "#000",
    zones: [
      {
        id: "zone-1",
        x: 0,
        y: 0,
        width: 1920,
        height: 1080,
        layer: 0,
        opacity: 1,
        loop: true,
        playlistItems: [
          {
            id: "video-1",
            kind: "video",
            src: "tcmedia://video-1",
            durationMs: null,
            fit: "contain",
            muted: true,
            volume: 0,
            loop: true,
          },
        ],
      },
    ],
  },
};

const sink: SurfaceSink = {
  ended: vi.fn(),
  failed: vi.fn(),
  resumed: vi.fn(),
  evidence: vi.fn(),
  websiteFailed: vi.fn(),
  websiteRecovered: vi.fn(),
  fallbackShown: vi.fn(),
  zoneFailed: vi.fn(),
  widgetEmpty: vi.fn(),
};

const settle = async () => {
  for (let i = 0; i < 8; i++) await Promise.resolve();
};

afterEach(() => vi.restoreAllMocks());

describe("a looping Layout video", () => {
  it.each([
    ["Linux Electron", "Mozilla/5.0 (X11; Linux x86_64) Electron/39.0.0"],
    [
      "Android WebView",
      "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 wv",
    ],
  ])("keeps one native-looping decoder on %s", async (_platform, userAgent) => {
    vi.spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
    const layout = new LayoutSurface(item, {
      clock: new ManualClock({ wallMs: 0 }),
      sink,
      animationScale: 1,
    });
    const video = layout.element.querySelector("video")!;
    await layout.activate();
    video.dispatchEvent(new Event("canplay"));
    await settle();
    expect(video.loop).toBe(true);
    video.currentTime = 59.95;
    video.dispatchEvent(new Event("timeupdate"));
    video.currentTime = 0.05;
    video.dispatchEvent(new Event("timeupdate"));
    expect(layout.element.querySelectorAll("video")).toHaveLength(1);
    expect(layout.element.querySelector("video")).toBe(video);
    layout.dispose();
  });
});
