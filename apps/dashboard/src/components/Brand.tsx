import { TilecastLogo } from "./TilecastLogo";
import { TilecastStudioLogo } from "./TilecastStudioLogo";

export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="brand flex items-center gap-2">
      {compact ? (
        <TilecastStudioLogo className="brand__studio-logo text-foreground" />
      ) : (
        <TilecastLogo className="brand__logo" />
      )}
      {/* i18n-ignore: TEST is the fork's product badge in every locale. */}
      <span className="rounded bg-amber-400 px-1.5 py-0.5 text-xs font-bold tracking-wide text-black">
        TEST
      </span>
    </div>
  );
}
