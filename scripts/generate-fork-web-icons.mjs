import { readFileSync } from "node:fs";
import sharp from "sharp";
import { testBadge } from "./fork-branding.mjs";

const mark = Buffer.from(
  readFileSync(
    "apps/player-android/branding/source/tilecast-mark.svg",
    "utf8",
  ).replace("<svg ", '<svg fill="#ffffff" '),
);
for (const [name, size] of [
  ["favicon.png", 64],
  ["apple-touch-icon.png", 180],
]) {
  await sharp(mark)
    .resize(size, size, { fit: "contain", background: "#18201d" })
    .flatten({ background: "#18201d" })
    .composite([
      {
        input: Buffer.from(
          `<svg width="${size}" height="${size}">${testBadge(size * 0.08, size * 0.6, size * 0.84)}</svg>`,
        ),
      },
    ])
    .png()
    .toFile(`apps/dashboard/public/${name}`);
}
