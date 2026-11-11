import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { StateStore } from "./storage";

const roots: string[] = [];

async function store(): Promise<StateStore> {
  const root = await mkdtemp(path.join(tmpdir(), "tilecast-state-read-"));
  roots.push(root);
  return new StateStore(root);
}

afterEach(async () => {
  await Promise.all(
    roots.splice(0).map((root) => rm(root, { recursive: true, force: true })),
  );
});

describe("StateStore.readJson", () => {
  it("returns null for missing state without creating a quarantine file", async () => {
    const state = await store();
    await expect(state.readJson("missing.json")).resolves.toBeNull();
    await expect(readFile(state.filePath("missing.json.corrupt"))).rejects.toMatchObject({
      code: "ENOENT",
    });
  });

  it("quarantines malformed JSON and returns null", async () => {
    const state = await store();
    await writeFile(state.filePath("state.json"), "{broken");
    await expect(state.readJson("state.json")).resolves.toBeNull();
    await expect(readFile(state.filePath("state.json.corrupt"), "utf8")).resolves.toBe(
      "{broken",
    );
    await expect(readFile(state.filePath("state.json"))).rejects.toMatchObject({
      code: "ENOENT",
    });
  });

  it("preserves state on filesystem read failures rather than quarantining it", async () => {
    const state = await store();
    // Reading a directory as a file fails with EISDIR on Linux. It must not
    // be renamed as corrupt state, which would mutate unrelated disk data.
    await mkdir(state.filePath("state.json"));
    await expect(state.readJson("state.json")).rejects.toMatchObject({
      code: "EISDIR",
    });
    await expect(readFile(state.filePath("state.json.corrupt"))).rejects.toMatchObject({
      code: "ENOENT",
    });
  });

  it("reads valid snapshots unchanged", async () => {
    const state = await store();
    await writeFile(state.filePath("state.json"), '{"healthy":true}');
    await expect(state.readJson<{ healthy: boolean }>("state.json")).resolves.toEqual({
      healthy: true,
    });
  });
});
