// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManualClock } from "../clock/scheduler";
import type { RuntimeItem } from "../host/contract";
import type { SurfaceSink } from "./surface";
import { SeamlessLoopVideoSurface } from "./seamless-loop-video-surface";
import { LayoutSurface } from "./layout-surface";

const item: RuntimeItem = {
  id: "video-1",
  kind: "video",
  src: "tcmedia://video-1",
  durationMs: null,
  fitMode: "contain",
  audioEnabled: false,
  volume: 0,
  videoStartOffsetMs: null,
  videoEndOffsetMs: null,
};

const settle = async () => {
  for (let i = 0; i < 8; i++) await Promise.resolve();
};

afterEach(() => {
  vi.restoreAllMocks();
  delete (HTMLVideoElement.prototype as Partial<HTMLVideoElement>)
    .requestVideoFrameCallback;
  delete (HTMLVideoElement.prototype as Partial<HTMLVideoElement>)
    .cancelVideoFrameCallback;
});

describe("a single looping Layout video", () => {
  it("mounts a spare near the boundary and releases the old decoder after the swap", async () => {
    const callbacks = new Map<
      HTMLVideoElement,
      Map<number, VideoFrameRequestCallback>
    >();
    let nextId = 0;
    HTMLVideoElement.prototype.requestVideoFrameCallback = function (
      this: HTMLVideoElement,
      callback,
    ) {
      const pending = callbacks.get(this) ?? new Map();
      callbacks.set(this, pending);
      const id = ++nextId;
      pending.set(id, callback);
      return id;
    };
    HTMLVideoElement.prototype.cancelVideoFrameCallback = function (
      this: HTMLVideoElement,
      id,
    ) {
      callbacks.get(this)?.delete(id);
    };
    const play = vi
      .spyOn(HTMLMediaElement.prototype, "play")
      .mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
    vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
    const frame = (video: HTMLVideoElement) => {
      const pending = callbacks.get(video)!;
      const next = [...pending.values()];
      pending.clear();
      for (const callback of next)
        callback(0, {} as VideoFrameCallbackMetadata);
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
    const surface = new SeamlessLoopVideoSurface(
      item,
      {
        clock: new ManualClock({ wallMs: 0 }),
        sink,
        animationScale: 1,
      },
      1,
    );
    const first = surface.element.querySelector("video")!;
    expect(first).toBeDefined();
    expect(surface.element.querySelectorAll("video")).toHaveLength(1);
    Object.defineProperty(first, "duration", { value: 60 });

    const preparing = surface.prepare();
    first!.dispatchEvent(new Event("canplay"));
    await preparing;
    const activating = surface.activate();
    await activating;
    first.currentTime = 57.5;
    first.dispatchEvent(new Event("timeupdate"));
    const second = surface.element.querySelectorAll("video")[1]!;
    Object.defineProperty(second, "duration", { value: 60 });
    second!.dispatchEvent(new Event("canplay"));
    frame(second!); // first decoded frame prepared offscreen
    await settle();
    expect(second!.style.zIndex).toBe("0");

    first!.currentTime = 59.95;
    frame(first!);
    // Never cut off the tail merely because the next decoder is prepared.
    expect(first!.style.zIndex).toBe("1");
    expect(second!.currentTime).toBe(0);
    expect(
      play.mock.contexts.filter((context) => context === second),
    ).toHaveLength(1);
    first!.dispatchEvent(new Event("ended"));
    expect(
      play.mock.contexts.filter((context) => context === second),
    ).toHaveLength(2);
    expect(second!.style.zIndex).toBe("1");
    expect(surface.element.querySelectorAll("video")).toHaveLength(1);
    // The old decoder is released throughout the next minute.
    expect(surface.element.contains(first!)).toBe(false);
    second!.currentTime = 57.5;
    second!.dispatchEvent(new Event("timeupdate"));
    const third = surface.element.querySelectorAll("video")[1]!;
    third.dispatchEvent(new Event("canplay"));
    frame(third);
    await settle();
    second!.currentTime = 59.95;
    frame(second!);
    expect(second!.style.zIndex).toBe("1");
    second!.dispatchEvent(new Event("ended"));
    expect(third.style.zIndex).toBe("1");
    expect(surface.element.contains(second!)).toBe(false);
    expect(surface.element.querySelectorAll("video")).toHaveLength(1);
    expect(sink.failed).not.toHaveBeenCalled();
    surface.dispose();

    const layout = new LayoutSurface(
      {
        ...item,
        id: "layout-1",
        kind: "layout",
        src: "",
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
                  id: item.id,
                  kind: "video",
                  src: item.src,
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
      },
      { clock: new ManualClock({ wallMs: 0 }), sink, animationScale: 1 },
    );
    expect(layout.element.querySelectorAll("video")).toHaveLength(1);
    layout.dispose();
  });
});
