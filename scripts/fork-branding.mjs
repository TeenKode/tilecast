// Outlined TEST lettering stays legible without relying on installed fonts.
const letters = {
  T: ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
  E: ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
  S: ["11111", "10000", "10000", "11111", "00001", "00001", "11111"],
};
export function testPath(x, y, scale) {
  return [..."TEST"]
    .flatMap((letter, index) =>
      letters[letter].flatMap((row, j) =>
        [...row].flatMap((pixel, i) => {
          if (pixel !== "1") return [];
          const left = x + (index * 6 + i) * scale;
          const top = y + j * scale;
          return [
            `M${left},${top}L${left + scale},${top}L${left + scale},${top + scale}L${left},${top + scale}Z`,
          ];
        }),
      ),
    )
    .join("");
}
export function testBadge(x, y, width) {
  const scale = width / 29;
  return `<g><rect x="${x}" y="${y}" width="${width}" height="${13 * scale}" rx="${2 * scale}" fill="#fbbf24"/><path fill="#111827" d="${testPath(x + 3 * scale, y + 3 * scale, scale)}"/></g>`;
}
