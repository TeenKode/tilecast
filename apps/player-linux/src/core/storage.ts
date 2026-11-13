/**
 * Durable player state on disk.
 *
 * Every state file is small JSON written atomically (temp file + fsync +
 * rename) so a power cut mid-write can never leave a half-written credential,
 * manifest, or escalation record. Files live under an XDG-style data
 * directory and are created with owner-only permissions because the
 * credential file is a bearer secret.
 */

import { promises as fs } from "fs";
import * as fsSync from "fs";
import * as path from "path";
import * as os from "os";
import { randomUUID } from "crypto";

export function defaultDataDir(): string {
  const xdg = process.env.XDG_DATA_HOME;
  const base =
    xdg && xdg.length > 0 ? xdg : path.join(os.homedir(), ".local", "share");
  return path.join(base, "tilecast-player");
}

export class StateStore {
  constructor(readonly dataDir: string) {}

  async init(): Promise<void> {
    await fs.mkdir(this.dataDir, { recursive: true, mode: 0o700 });
    await fs.mkdir(path.join(this.dataDir, "cache"), {
      recursive: true,
      mode: 0o700,
    });
    await fs.mkdir(path.join(this.dataDir, "cache", "media"), {
      recursive: true,
      mode: 0o700,
    });
  }

  filePath(name: string): string {
    return path.join(this.dataDir, name);
  }

  mediaDir(): string {
    return path.join(this.dataDir, "cache", "media");
  }

  async readJson<T>(name: string): Promise<T | null> {
    let raw: string;
    try {
      raw = await fs.readFile(this.filePath(name), "utf8");
    } catch (err: unknown) {
      if ((err as NodeJS.ErrnoException).code === "ENOENT") {
        return null;
      }
      // Permissions, I/O faults and transient filesystem errors do not mean
      // the JSON is corrupt. Surface them rather than hiding or moving state.
      throw err;
    }

    try {
      return JSON.parse(raw) as T;
    } catch (err: unknown) {
      if (!(err instanceof SyntaxError)) throw err;
      // A malformed JSON snapshot (for example from an older torn write)
      // must not brick startup. Quarantine only confirmed parse failures.
      try {
        await fs.rename(this.filePath(name), this.filePath(name + ".corrupt"));
      } catch {
        // Best effort: the invalid snapshot is still treated as missing.
      }
      return null;
    }
  }

  async writeJson(name: string, value: unknown): Promise<void> {
    const target = this.filePath(name);
    // A unique temporary name keeps two independent writers from ever
    // truncating each other's in-flight snapshot. Callers still serialize
    // logical updates, but this is an additional crash/concurrency guard for
    // recovery paths and future state files.
    const temp = `${target}.tmp-${randomUUID()}`;
    const data = JSON.stringify(value, null, 2);
    const handle = await fs.open(temp, "w", 0o600);
    try {
      await handle.writeFile(data, "utf8");
      await handle.sync();
    } finally {
      await handle.close();
    }
    await fs.rename(temp, target);
    // Sync the directory so the rename itself survives power loss.
    try {
      const dir = fsSync.openSync(this.dataDir, "r");
      fsSync.fsyncSync(dir);
      fsSync.closeSync(dir);
    } catch {
      // Directory fsync is best-effort (not supported on all filesystems).
    }
  }

  async delete(name: string): Promise<void> {
    try {
      await fs.unlink(this.filePath(name));
    } catch (err: unknown) {
      if ((err as NodeJS.ErrnoException).code !== "ENOENT") {
        throw err;
      }
    }
  }

  async clearMedia(): Promise<void> {
    let entries: string[];
    try {
      entries = await fs.readdir(this.mediaDir());
    } catch (err: unknown) {
      if ((err as NodeJS.ErrnoException).code === "ENOENT") return;
      throw err;
    }
    await Promise.all(
      entries.map((entry) =>
        fs.rm(path.join(this.mediaDir(), entry), { force: true }),
      ),
    );
  }
}
