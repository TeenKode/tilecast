import { useTranslation } from "react-i18next";
import { TilecastLogo } from "./TilecastLogo";
import { TilecastStudioLogo } from "./TilecastStudioLogo";

export function Brand({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation("common");
  return (
    <div className="brand flex items-center gap-2">
      {compact ? (
        <TilecastStudioLogo className="brand__studio-logo text-foreground" />
      ) : (
        <TilecastLogo className="brand__logo" />
      )}
      <span className="rounded bg-amber-400 px-1.5 py-0.5 text-xs font-bold tracking-wide text-black">
        {t("testEnvironment")}
      </span>
    </div>
  );
}
