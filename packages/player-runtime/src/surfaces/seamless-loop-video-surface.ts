/**
 * Loop a single, untrimmed Layout video without holding its last frame during
 * a visible seek. Decode the next first frame shortly before the boundary,
 * then release the old decoder as soon as the prepared frame is shown.
 */
import type { RuntimeItem } from "../host/contract";
import type { MediaSurface, SurfaceEnvironment, SurfaceSink } from "./surface";
import { HtmlVideoSurface } from "./video-surface";

const PREPARE_BEFORE_END_SECONDS = 3;

export class SeamlessLoopVideoSurface implements MediaSurface {
  readonly element: HTMLDivElement;
  private readonly videos: [HtmlVideoSurface | null, HtmlVideoSurface | null];
  private current: 0 | 1 = 0;
  private ready = false;
  private preparing = false;
  private active = false;
  private disposed = false;
  private seamless = true;
  private generation = 0;
  private nextMount: number;

  constructor(
    private readonly item: RuntimeItem,
    private readonly env: SurfaceEnvironment,
    mount: number,
  ) {
    this.nextMount = mount;
    this.element = document.createElement("div");
    this.element.style.position = "relative";
    // The spare has no media source or decoder during most of the clip.
    this.videos = [this.makeVideo(0), null];
  }

  prepare(): Promise<void> {
    return this.videos[0]!.prepare();
  }

  async activate(): Promise<void> {
    if (this.disposed || this.active) return;
    this.active = true;
    await this.videos[this.current]!.activate();
    this.maybePrepare();
  }

  pause(): void {
    this.active = false;
    this.videos[this.current]?.pause();
    this.releaseSpare();
  }

  seek(seconds: number): Promise<void> {
    return this.videos[this.current]!.seek(seconds);
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.pause();
    const current = this.videos[this.current];
    current?.dispose();
    current?.element.remove();
    this.videos[this.current] = null;
  }

  private makeVideo(index: 0 | 1): HtmlVideoSurface {
    const sink: SurfaceSink = {
      ...this.env.sink,
      ended: () => {
        if (index !== this.current || !this.active || this.disposed) return;
        if (this.ready) this.swap();
        else {
          // A slow decoder must still let the screen keep playing.
          this.fallback();
          this.videos[this.current]!.restart();
        }
      },
      failed: (reason) => {
        if (index === this.current) this.env.sink.failed(reason);
        else this.fallback();
      },
      evidence: (kind, zoneId) => {
        if (index === this.current) this.env.sink.evidence(kind, zoneId);
      },
    };
    const surface = new HtmlVideoSurface(
      this.item,
      { ...this.env, sink },
      this.nextMount++,
      { presentFirstFrame: index !== this.current },
    );
    const video = surface.element;
    video.style.position = "absolute";
    video.style.inset = "0";
    video.style.width = "100%";
    video.style.height = "100%";
    // Keep the covered layer composited while it presents its first frame.
    video.style.zIndex = index === this.current ? "1" : "0";
    video.addEventListener("timeupdate", () => {
      if (index === this.current) this.maybePrepare();
    });
    this.element.append(video);
    return surface;
  }

  private maybePrepare(): void {
    if (
      !this.active ||
      this.disposed ||
      !this.seamless ||
      this.ready ||
      this.preparing
    )
      return;
    const video = this.videos[this.current]!.element;
    const remaining = video.duration - video.currentTime;
    if (
      Number.isFinite(remaining) &&
      remaining > 0 &&
      remaining <= PREPARE_BEFORE_END_SECONDS
    )
      this.prepareSpare();
  }

  private prepareSpare(): void {
    this.preparing = true;
    const generation = ++this.generation;
    const index = this.current === 0 ? 1 : 0;
    const spare = this.makeVideo(index);
    this.videos[index] = spare;
    const video = spare.element;
    video.muted = true;
    let presented = false;
    const firstFrame = video.requestVideoFrameCallback(() => {
      presented = true;
    });
    void spare.prepare().then(
      () => {
        if (generation !== this.generation || this.disposed) return;
        this.preparing = false;
        video.pause();
        if (!presented) {
          video.cancelVideoFrameCallback(firstFrame);
          this.fallback();
          return;
        }
        this.ready = true;
      },
      () => {
        if (generation !== this.generation || this.disposed) return;
        video.cancelVideoFrameCallback(firstFrame);
        this.fallback();
      },
    );
  }

  private swap(): void {
    if (!this.ready || this.disposed || !this.active) return;
    const oldIndex = this.current;
    const nextIndex = oldIndex === 0 ? 1 : 0;
    const outgoing = this.videos[oldIndex]!;
    const incoming = this.videos[nextIndex]!;
    incoming.element.style.zIndex = "1";
    this.current = nextIndex;
    this.ready = false;
    incoming.element.muted = !this.item.audioEnabled;
    void incoming.activate();
    outgoing.dispose();
    outgoing.element.remove();
    this.videos[oldIndex] = null;
    this.env.sink.ended("ended");
    this.env.sink.resumed();
  }

  private releaseSpare(): void {
    this.generation++;
    this.ready = false;
    this.preparing = false;
    const index = this.current === 0 ? 1 : 0;
    const spare = this.videos[index];
    spare?.dispose();
    spare?.element.remove();
    this.videos[index] = null;
  }

  private fallback(): void {
    if (this.disposed) return;
    this.seamless = false;
    this.releaseSpare();
    this.videos[this.current]!.element.loop = true;
  }
}
