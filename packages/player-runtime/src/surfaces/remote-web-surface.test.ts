// @vitest-environment jsdom
/**
 * Website failure policies on the host-view path: `skip`, `fallback_image`,
 * `placeholder` and `last_success` behave distinctly, `prepare()` always
 * settles, and the presentation machine is never left waiting.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ManualClock } from "../clock/scheduler";
import type {
  RemoteWebCreateResultV1,
  RemoteWebEventV1,
  RemoteWebSurfaceSpecV1,
  RuntimeRemoteWebSpecV1,
} from "../host/contract";
import { RemoteWebPort } from "../remote-web/port";
import {
  normalizeFailureBehavior,
  specFromWebDescriptor,
} from "../remote-web/spec";
import { HostRemoteWebSurface } from "./remote-web-surface";
import type { SurfaceEnvironment, SurfaceSink } from "./surface";

const FALLBACK = "tcmedia://cap/" + "f".repeat(64);

function spec(
  failureBehavior: string,
  fallbackSrc: string | null = null,
): RuntimeRemoteWebSpecV1 {
  return {
    content: {
      kind: "page",
      url: "https://example.com/signage",
      allowedHosts: ["example.com"],
      javascriptEnabled: true,
      domStorageEnabled: true,
      cookiePolicy: "first_party",
      userAgent: "",
      zoomPercent: 100,
      scrollX: 0,
      scrollY: 0,
      backgroundColor: "#0E141B",
    },
    presentation: {
      loadTimeoutSeconds: 20,
      reloadIntervalSeconds: null,
      lifecycle: "destroy_on_hide",
      warmSeconds: 0,
      onlineOnly: false,
      failureBehavior,
      fallbackSrc,
      playUntilEnd: false,
    },
  };
}

interface ScriptedHost {
  createImpl: (
    spec: RemoteWebSurfaceSpecV1,
  ) => Promise<RemoteWebCreateResultV1>;
  destroyed: string[];
  muted: Array<{ id: string; muted: boolean }>;
}

function scriptedHost(): {
  state: ScriptedHost;
  remoteWeb: NonNullable<
    import("../host/contract").TilecastRuntimeHostV1["remoteWeb"]
  >;
} {
  const state: ScriptedHost = {
    createImpl: () =>
      Promise.resolve({
        ok: true,
        target: { kind: "media-uri", uri: "tcweb://cap/1" },
      }),
    destroyed: [],
    muted: [],
  };
  return {
    state,
    remoteWeb: {
      reportRecovered: () => undefined,
      create: (s) => state.createImpl(s),
      updateViewport: () => undefined,
      setVisible: () => undefined,
      setMuted: (surfaceId, muted) => {
        state.muted.push({ id: surfaceId, muted });
      },
      reload: () => undefined,
      destroy: (surfaceId) => {
        state.destroyed.push(surfaceId);
      },
    },
  };
}

interface Harness {
  surface: HostRemoteWebSurface;
  port: RemoteWebPort;
  host: ReturnType<typeof scriptedHost>;
  clock: ManualClock;
  sink: SurfaceSink & {
    websiteFailures: Array<{ reason: string; fallback: boolean }>;
    fallbackShownCount: number;
    recovered: number;
    failures: string[];
  };
  settled: () => string;
  preparePromise: ReturnType<HostRemoteWebSurface["prepare"]>;
}

function harness(
  behavior: string,
  fallbackSrc: string | null = null,
  createImpl: (
    spec: RemoteWebSurfaceSpecV1,
  ) => Promise<RemoteWebCreateResultV1> = () =>
    Promise.resolve({
      ok: true,
      target: { kind: "media-uri", uri: "tcweb://cap/1" },
    }),
  presentationSpec?: RuntimeRemoteWebSpecV1,
): Harness {
  const clock = new ManualClock({ wallMs: 1_000_000 });
  const sink = {
    websiteFailures: [] as Array<{ reason: string; fallback: boolean }>,
    fallbackShownCount: 0,
    recovered: 0,
    failures: [] as string[],
    ended: () => undefined,
    failed: (message: string) => {
      sink.failures.push(message);
    },
    resumed: () => undefined,
    evidence: () => undefined,
    websiteFailed: (reason: string, fallback: boolean) => {
      sink.websiteFailures.push({ reason, fallback });
    },
    websiteRecovered: () => {
      sink.recovered += 1;
    },
    fallbackShown: () => {
      sink.fallbackShownCount += 1;
    },
    zoneFailed: () => undefined,
    widgetEmpty: () => undefined,
  };
  const env: SurfaceEnvironment = { clock, sink, animationScale: 0 };
  const host = scriptedHost();
  host.state.createImpl = createImpl;
  const port = new RemoteWebPort(host.remoteWeb, clock);
  const surface = new HostRemoteWebSurface({
    spec: presentationSpec ?? spec(behavior, fallbackSrc),
    audioEnabled: false,
    port,
    env,
  });
  let settled = "pending";
  const preparePromise = surface.prepare();
  preparePromise.then(
    () => (settled = "resolved"),
    () => (settled = "rejected"),
  );
  return {
    surface,
    port,
    host,
    clock,
    sink,
    settled: () => settled,
    preparePromise,
  };
}

async function flush(times = 5): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve();
  }
}

function surfaceIdOf(h: Harness): string {
  const described = h.surface.describe();
  expect(typeof described.surfaceId).toBe("string");
  return described.surfaceId as string;
}

/** Drive a surface to loaded + first frame: the reference "has content". */
async function loadWithFrame(h: Harness): Promise<string> {
  await flush();
  const id = surfaceIdOf(h);
  h.port.receive({ surfaceId: id, kind: "loaded" });
  h.port.receive({ surfaceId: id, kind: "stream-ready" });
  const video = h.surface.element.querySelector("video");
  expect(video).not.toBeNull();
  video!.dispatchEvent(new Event("timeupdate"));
  await flush();
  expect(h.settled()).toBe("resolved");
  return id;
}

beforeEach(() => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe(): void {
        /* no-op */
      }
      unobserve(): void {
        /* no-op */
      }
      disconnect(): void {
        /* no-op */
      }
    },
  );
  if (!HTMLMediaElement.prototype.play) {
    Object.defineProperty(HTMLMediaElement.prototype, "play", {
      configurable: true,
      value: () => Promise.resolve(),
    });
  } else {
    vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(() =>
      Promise.resolve(),
    );
  }
  return () => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  };
});

describe("normalizeFailureBehavior", () => {
  it("keeps the four Studio policies and fails safe otherwise", () => {
    for (const known of [
      "skip",
      "fallback_image",
      "placeholder",
      "last_success",
    ]) {
      expect(normalizeFailureBehavior(known)).toBe(known);
    }
    for (const unknown of ["", "banana", null, undefined, 0, "SKIP"]) {
      expect(normalizeFailureBehavior(unknown)).toBe("placeholder");
    }
  });
});

describe("Website failure policies", () => {
  it("reloads a Canva descriptor and cancels timers while keeping the hidden surface warm", async () => {
    const canva = specFromWebDescriptor({
      mode: "remote",
      url: "https://www.canva.com/design/DAGabcdefgh/view?embed=",
      allowedHosts: ["www.canva.com"],
      lifecycle: "keep_warm",
      warmSeconds: 60,
      reload: { mode: "periodic", intervalSeconds: 60 },
      onlineOnly: true,
      fallbackBehavior: "placeholder",
    })!;
    const h = harness("placeholder", null, undefined, canva);
    const reload = vi.spyOn(h.host.remoteWeb, "reload");
    const id = await loadWithFrame(h);
    await h.surface.activate();
    for (let second = 0; second < 60; second++) {
      h.clock.advance(1_000);
      h.surface.element
        .querySelector("video")!
        .dispatchEvent(new Event("timeupdate"));
    }
    expect(reload).toHaveBeenCalledWith(id);
    h.surface.dispose();
    expect(h.port.describe().warm).toBe(1);
    h.clock.advance(60_000);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(h.host.state.destroyed).toContain(id);
  });
  it("skip clears the surface and finishes through the failure path", async () => {
    const h = harness("skip", null, () =>
      Promise.resolve({ ok: false, code: "helper_terminated" }),
    );
    await flush(10);
    expect(h.sink.websiteFailures).toEqual([
      { reason: "helper_terminated", fallback: false },
    ]);
    expect(h.surface.element.childElementCount).toBe(0);
    expect(h.settled()).toBe("rejected");
    await expect(h.preparePromise).rejects.toThrow("helper_terminated");
  });

  it("fallback_image shows the fallback and reports fallback evidence", async () => {
    const h = harness("fallback_image", FALLBACK, () =>
      Promise.resolve({ ok: false, code: "rejected_code" }),
    );
    await flush(10);
    const image = h.surface.element.querySelector("img.tc-website-fallback");
    expect(image).not.toBeNull();
    expect(image!.getAttribute("src")).toBe(FALLBACK);
    expect(h.sink.websiteFailures).toEqual([
      { reason: "rejected_code", fallback: true },
    ]);
    image!.dispatchEvent(new Event("load"));
    await flush();
    expect(h.sink.fallbackShownCount).toBe(1);
    expect(h.settled()).toBe("resolved");
    await h.preparePromise;
  });

  it("fallback_image with an unloadable image fails deterministically", async () => {
    const h = harness("fallback_image", FALLBACK, () =>
      Promise.resolve({ ok: false, code: "rejected_code" }),
    );
    await flush(10);
    const image = h.surface.element.querySelector("img.tc-website-fallback");
    expect(image).not.toBeNull();
    expect(h.sink.websiteFailures).toEqual([
      { reason: "rejected_code", fallback: true },
    ]);
    image!.dispatchEvent(new Event("error"));
    await flush();
    // No fallback evidence, no hang: prepare rejects into SURFACE_FAILED.
    expect(h.sink.fallbackShownCount).toBe(0);
    expect(h.settled()).toBe("rejected");
    await expect(h.preparePromise).rejects.toThrow("fallback_unavailable");
  });

  it("placeholder renders Website unavailable instead of skipping", async () => {
    const h = harness("placeholder", FALLBACK);
    await flush();
    const id = surfaceIdOf(h);
    h.port.receive({ surfaceId: id, kind: "failed", code: "load_failed" });
    await flush();
    // The configured fallback image is ignored: only fallback_image uses it.
    expect(
      h.surface.element.querySelector("img.tc-website-fallback"),
    ).toBeNull();
    const placeholder = h.surface.element.querySelector(
      ".tc-website-placeholder",
    );
    expect(placeholder).not.toBeNull();
    expect(placeholder!.textContent).toContain("Website unavailable");
    expect(h.sink.websiteFailures).toEqual([
      { reason: "load_failed", fallback: true },
    ]);
    await expect(h.preparePromise).resolves.toEqual({ websiteFallback: true });
  });

  it("last_success before the first frame shows a placeholder, not emptiness", async () => {
    const h = harness("last_success");
    await flush();
    const id = surfaceIdOf(h);
    h.port.receive({ surfaceId: id, kind: "failed", code: "load_failed" });
    await flush();
    expect(
      h.surface.element.querySelector(".tc-website-placeholder"),
    ).not.toBeNull();
    expect(h.sink.websiteFailures).toEqual([
      { reason: "load_failed", fallback: true },
    ]);
    await expect(h.preparePromise).resolves.toEqual({ websiteFallback: true });
  });

  it("last_success after a frame keeps the video and stays on the item", async () => {
    const h = harness("last_success");
    const id = await loadWithFrame(h);
    const oldVideo = h.surface.element.querySelector("video")!;
    h.port.receive({ surfaceId: id, kind: "failed", code: "http_error" });
    await flush();
    // The only retained image is not destroyed.
    expect(h.surface.element.querySelector("video")).not.toBeNull();
    expect(h.sink.websiteFailures).toEqual([
      { reason: "http_error", fallback: true },
    ]);
    expect(h.settled()).toBe("resolved");
    await h.preparePromise;
    await h.surface.activate();
    h.clock.advance(30_000);
    await flush();
    oldVideo.dispatchEvent(new Event("error"));
    const recoveredId = await loadWithFrame(h);
    expect(recoveredId).not.toBe(id);
    expect(h.sink.recovered).toBe(1);
    expect(h.surface.element.contains(oldVideo)).toBe(false);
    h.surface.dispose();
  });

  it("reload failure after a load follows the configured policy", async () => {
    const kept = harness("last_success");
    const keptId = await loadWithFrame(kept);
    kept.port.receive({
      surfaceId: keptId,
      kind: "failed",
      code: "reload_failed",
    });
    await flush();
    expect(kept.surface.element.querySelector("video")).not.toBeNull();
    expect(kept.sink.websiteFailures).toEqual([
      { reason: "reload_failed", fallback: true },
    ]);

    const skipped = harness("skip");
    const skippedId = await loadWithFrame(skipped);
    skipped.port.receive({
      surfaceId: skippedId,
      kind: "failed",
      code: "reload_failed",
    });
    await flush();
    expect(skipped.surface.element.querySelector("video")).toBeNull();
    expect(skipped.sink.websiteFailures).toEqual([
      { reason: "reload_failed", fallback: false },
    ]);
  });

  it("helper process termination fails live surfaces through the policy", async () => {
    const h = harness("placeholder");
    const id = await loadWithFrame(h);
    expect(id).toBeTruthy();
    h.port.receive({ surfaceId: null, kind: "process-terminated" });
    await flush();
    expect(
      h.surface.element.querySelector(".tc-website-placeholder"),
    ).not.toBeNull();
    expect(h.surface.element.querySelector("video")).toBeNull();
    expect(h.sink.websiteFailures).toEqual([
      { reason: "helper_terminated", fallback: false },
    ]);
  });

  it("placeholder after a load replaces the video", async () => {
    const h = harness("placeholder");
    const id = await loadWithFrame(h);
    h.port.receive({ surfaceId: id, kind: "failed", code: "renderer_crash" });
    await flush();
    expect(h.surface.element.querySelector("video")).toBeNull();
    expect(
      h.surface.element.querySelector(".tc-website-placeholder"),
    ).not.toBeNull();
    expect(h.sink.websiteFailures).toEqual([
      { reason: "renderer_crash", fallback: false },
    ]);
  });

  it("load timeout shows the fallback and gives each retry its own timeout", async () => {
    const h = harness("placeholder");
    await flush();
    surfaceIdOf(h);
    h.clock.advance(20_000);
    await flush(10);
    expect(h.sink.websiteFailures).toEqual([
      { reason: "load timeout", fallback: true },
    ]);
    expect(
      h.surface.element.querySelector(".tc-website-placeholder"),
    ).not.toBeNull();
    await expect(h.preparePromise).resolves.toEqual({ websiteFallback: true });
    await h.surface.activate();
    h.clock.advance(30_000);
    await flush();
    const retryId = surfaceIdOf(h);
    h.clock.advance(20_000);
    expect(h.host.state.destroyed).toContain(retryId);
    h.clock.advance(30_000);
    await flush();
    expect(surfaceIdOf(h)).not.toBe(retryId);
    expect(h.sink.websiteFailures).toHaveLength(1);
    h.surface.dispose();
  });

  it("reopens an inaccessible Android page until it loads and stops retrying", async () => {
    const h = harness("placeholder", null, () =>
      Promise.resolve({ ok: true, target: { kind: "host-layer" } }),
    );
    const create = vi.spyOn(h.host.remoteWeb, "create");
    const visible = vi.spyOn(h.host.remoteWeb, "setVisible");
    await flush();
    const initialId = surfaceIdOf(h);
    h.port.receive({
      surfaceId: initialId,
      kind: "failed",
      code: "connection_failure",
    });
    // Android can finish dispatching callbacks from the released view.
    h.port.receive({ surfaceId: initialId, kind: "loaded" });
    await h.preparePromise;
    await h.surface.activate();
    h.clock.advance(29_999);
    expect(create).not.toHaveBeenCalled();
    h.clock.advance(1);
    await flush();
    const retryId = surfaceIdOf(h);
    expect(retryId).not.toBe(initialId);
    expect(create).toHaveBeenLastCalledWith(
      expect.objectContaining({ visible: false }),
    );
    h.port.receive({ surfaceId: retryId, kind: "failed", code: "dns_failure" });
    h.clock.advance(30_000);
    await flush();
    const recoveredId = surfaceIdOf(h);
    h.port.receive({ surfaceId: recoveredId, kind: "loaded" });
    expect(h.sink.recovered).toBe(0);
    h.port.receive({ surfaceId: recoveredId, kind: "stream-ready" });
    expect(h.sink.recovered).toBe(1);
    expect(h.sink.websiteFailures).toHaveLength(1);
    expect(
      h.surface.element.querySelector(".tc-website-placeholder"),
    ).toBeNull();
    expect(visible).toHaveBeenLastCalledWith(recoveredId, true);
    h.clock.advance(60_000);
    expect(create).toHaveBeenCalledTimes(2);
    h.surface.dispose();
  });

  it("uses the configured delay after a load failure", async () => {
    const site = spec("placeholder");
    site.presentation.retryIntervalSeconds = 7;
    const h = harness("placeholder", null, undefined, site);
    const create = vi.spyOn(h.host.remoteWeb, "create");
    await flush();
    h.port.receive({
      surfaceId: surfaceIdOf(h),
      kind: "failed",
      code: "connection_failure",
    });
    await h.surface.activate();
    h.clock.advance(6_999);
    expect(create).not.toHaveBeenCalled();
    h.clock.advance(1);
    await flush();
    expect(create).toHaveBeenCalledTimes(1);
    h.surface.dispose();
  });

  it("applies the configured fallback without scheduling a retry at zero", async () => {
    const site = spec("last_success");
    site.presentation.retryIntervalSeconds = 0;
    const h = harness("last_success", null, undefined, site);
    const create = vi.spyOn(h.host.remoteWeb, "create");
    const id = await loadWithFrame(h);
    await h.surface.activate();
    h.port.receive({
      surfaceId: id,
      kind: "failed",
      code: "connection_failure",
    });
    h.clock.advance(120_000);
    expect(create).not.toHaveBeenCalled();
    expect(h.surface.element.querySelector("video")).not.toBeNull();
    h.surface.dispose();
  });

  it("cancels pending retries while hidden and after disposal", async () => {
    const h = harness("placeholder");
    const create = vi.spyOn(h.host.remoteWeb, "create");
    await flush();
    h.port.receive({
      surfaceId: surfaceIdOf(h),
      kind: "failed",
      code: "dns_failure",
    });
    await h.surface.activate();
    h.surface.pause();
    h.clock.advance(60_000);
    expect(create).not.toHaveBeenCalled();
    await h.surface.activate();
    h.surface.dispose();
    h.clock.advance(60_000);
    expect(create).not.toHaveBeenCalled();
  });

  it("keeps skip and navigation blocks on the terminal failure path", async () => {
    for (const [behavior, kind, code] of [
      ["skip", "failed", "connection_failure"],
      ["placeholder", "navigation-blocked", "blocked_navigation"],
      ["placeholder", "failed", "tls_failure"],
    ] as const) {
      const h = harness(behavior);
      const create = vi.spyOn(h.host.remoteWeb, "create");
      await flush();
      h.port.receive({ surfaceId: surfaceIdOf(h), kind, code });
      await expect(h.preparePromise).rejects.toThrow(code);
      await h.surface.activate();
      h.clock.advance(60_000);
      expect(create).not.toHaveBeenCalled();
      h.surface.dispose();
    }
  });

  it("a successful load resolves prepare with the stream showing", async () => {
    const h = harness("placeholder");
    await loadWithFrame(h);
    expect(h.surface.element.querySelector("video")).not.toBeNull();
    expect(h.sink.websiteFailures).toEqual([]);
    await h.preparePromise;
  });
});
