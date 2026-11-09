/**
 * A single, untrimmed video in a looping Layout zone. Chromium's native
 * `video.loop` may hold its last frame while the decoder seeks to the start.
 * Keep another decoder paused on a decoded first frame, then start and reveal
 * it at the boundary. Only one decoder plays at a time outside the short swap.
 */
import type { RuntimeItem } from "../host/contract";
import type { MediaSurface, SurfaceEnvironment, SurfaceSink } from "./surface";
import { HtmlVideoSurface } from "./video-surface";

export class SeamlessLoopVideoSurface implements MediaSurface {
  readonly element: HTMLDivElement;
  private readonly videos: [HtmlVideoSurface, HtmlVideoSurface];
  private current: 0 | 1 = 0;
  private ready = false;
  private active = false;
  private disposed = false;
  private preparing = false;
  private recycling = false;
  private prepareGeneration = 0;
  private readonly audible: boolean;

  constructor(
    item: RuntimeItem,
    private readonly env: SurfaceEnvironment,
    mount: number,
  ) {
    this.audible = item.audioEnabled;
    this.element = document.createElement("div");
    this.element.style.position = "relative";
    this.videos = [0, 1].map((index) => {
      const sink: SurfaceSink = {
        ...env.sink,
        ended: (source) => {
          if (index === this.current && !this.disposed && this.active) {
            if (this.ready) this.swap();
            else {
              // A spare decoder failure must not leave a single-video zone
              // parked forever on the last frame.
              this.fallback();
              this.videos[this.current]!.restart();
            }
          }
        },
        failed: (reason) => {
          if (index === this.current) env.sink.failed(reason);
          else this.fallback();
        },
        evidence: (kind, zoneId) => {
          if (index === this.current) env.sink.evidence(kind, zoneId);
        },
      };
      const surface = new HtmlVideoSurface(
        item,
        { ...env, sink },
        mount + index,
        {
          presentFirstFrame: index === 1,
        },
      );
      const video = surface.element;
      video.style.position = "absolute";
      video.style.inset = "0";
      video.style.width = "100%";
      video.style.height = "100%";
      // Keep both layers composited. Some engines do not report presented
      // frames for a video with CSS visibility:hidden.
      video.style.zIndex = index === 0 ? "1" : "0";
      this.element.append(video);
      return surface;
    }) as [HtmlVideoSurface, HtmlVideoSurface];
  }

  prepare(): Promise<void> {
    return this.videos[0].prepare();
  }

  async activate(): Promise<void> {
    if (this.disposed || this.active) return;
    this.active = true;
    await this.videos[this.current]!.activate();
    if (!this.active || this.disposed) return;
    if (!this.ready && !this.recycling) this.prepareSpare();
  }

  pause(): void {
    this.active = false;
    this.videos[0].pause();
    this.videos[1].pause();
  }

  seek(seconds: number): Promise<void> {
    return this.videos[this.current]!.seek(seconds);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.pause();
    this.videos[0].dispose();
    this.videos[1].dispose();
  }

  private prepareSpare(): void {
    if (this.preparing) return;
    this.preparing = true;
    const generation = ++this.prepareGeneration;
    const spare = this.videos[1 - this.current]!;
    const video = spare.element;
    video.muted = true;
    let presented = false;
    const firstFrame = video.requestVideoFrameCallback(() => {
      presented = true;
    });
    // prepare() presents a real decoded first frame on the hidden element.
    void spare.prepare().then(
      () => {
        this.preparing = false;
        if (generation !== this.prepareGeneration || this.disposed) return;
        video.pause();
        if (!presented) {
          video.cancelVideoFrameCallback(firstFrame);
          this.fallback();
          return;
        }
        this.ready = true;
      },
      () => {
        this.preparing = false;
        video.cancelVideoFrameCallback(firstFrame);
        this.fallback();
      },
    );
  }

  private swap(): void {
    if (!this.ready || this.disposed || !this.active) return;
    const outgoing = this.videos[this.current]!;
    const incoming = this.videos[1 - this.current]!;
    incoming.element.loop = false;
    outgoing.element.loop = false;
    incoming.element.style.zIndex = "1";
    outgoing.element.style.zIndex = "0";
    this.current = this.current === 0 ? 1 : 0;
    incoming.element.muted = !this.audible;
    void incoming.activate();
    outgoing.pause();
    this.ready = false;
    this.env.sink.ended("ended");
    this.env.sink.resumed();
    // The outgoing decoder can seek and prepare its first frame offscreen.
    this.prepareRecycled(outgoing);
  }

  private prepareRecycled(surface: HtmlVideoSurface): void {
    this.recycling = true;
    const generation = ++this.prepareGeneration;
    const video = surface.element;
    video.muted = true;
    const seeked = () => {
      if (generation !== this.prepareGeneration || this.disposed) return;
      const firstFrame = video.requestVideoFrameCallback(() => {
        video.pause();
        if (generation === this.prepareGeneration && !this.disposed) {
          this.ready = true;
          this.recycling = false;
        }
      });
      video.play().catch(() => {
        video.cancelVideoFrameCallback(firstFrame);
        this.fallback();
      });
    };
    video.addEventListener("seeked", seeked, { once: true });
    video.currentTime = 0;
  }

  private fallback(): void {
    if (this.disposed) return;
    this.ready = false;
    this.recycling = false;
    this.videos[this.current]!.element.loop = true;
  }
}
