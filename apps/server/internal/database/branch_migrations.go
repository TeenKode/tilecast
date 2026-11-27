package database

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

// Both histories shared versions through 134. The fork shipped the Android
// TEST constraint at 135 and widget fonts at 136. Upstream shipped widget
// fonts, takeover classes and the NWS backfill at 135, 136 and 137.
func prepareBranchMigrations(ctx context.Context, db *sql.DB) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin branch migration check: %w", err)
	}
	defer tx.Rollback()

	var historyExists bool
	if err := tx.QueryRowContext(ctx, `SELECT to_regclass('public.goose_db_version') IS NOT NULL`).Scan(&historyExists); err != nil {
		return fmt.Errorf("check migration history: %w", err)
	}
	if !historyExists {
		return nil
	}
	if _, err := tx.ExecContext(ctx, `LOCK TABLE public.goose_db_version IN SHARE ROW EXCLUSIVE MODE`); err != nil {
		return fmt.Errorf("lock migration history: %w", err)
	}

	var version int64
	if err := tx.QueryRowContext(ctx, `SELECT COALESCE(MAX(version_id), 0) FROM public.goose_db_version WHERE is_applied`).Scan(&version); err != nil {
		return fmt.Errorf("read migration history: %w", err)
	}
	if version < 135 || version > 137 {
		return nil
	}

	var fork, widgetFonts, takeoverClasses bool
	if err := tx.QueryRowContext(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM pg_constraint
			WHERE conrelid = to_regclass('public.player_releases')
			  AND conname = 'player_releases_family_shape_check'
			  AND strpos(pg_get_constraintdef(oid), 'org.tilecast.player.test') > 0
		), to_regclass('public.widget_font_references') IS NOT NULL, EXISTS (
			SELECT 1 FROM pg_attribute
			WHERE attrelid = to_regclass('public.takeovers')
			  AND attname = 'class' AND NOT attisdropped
		)
	`).Scan(&fork, &widgetFonts, &takeoverClasses); err != nil {
		return fmt.Errorf("identify historical migrations: %w", err)
	}

	if fork {
		if version <= 136 {
			// The fork resolved saved query variables before upstream added
			// the opt-in. Keep those Website Widgets working on upgrade.
			if _, err := tx.ExecContext(ctx, `
				UPDATE public.widgets
				SET configuration = jsonb_set(configuration, '{urlTemplatesEnabled}', 'true')
				WHERE provider = 'website' AND NOT configuration ? 'urlTemplatesEnabled'
				  AND substring(configuration->>'url' FROM '^[^?#]*[?]([^#]*)')
				      ~ '(^|&)[^&{}=#]+=[^&]*[{](screen[.]id|date|time|datetime|data[.][A-Za-z_][A-Za-z0-9_-]*(:[0-9]{1,3})?)[}]'
			`); err != nil {
				return fmt.Errorf("enable saved fork Website variables: %w", err)
			}
		}
		return tx.Commit()
	}

	if !widgetFonts || takeoverClasses != (version >= 136) {
		return fmt.Errorf("migration history at version %d does not match a known Tilecast schema", version)
	}

	// Install the missing fork change and record the now-equivalent sequence
	// in the same transaction. The numbered rows through the old head are
	// already present. Adding the next row leaves the complete contiguous
	// fork history for Goose without replaying any applied upstream SQL.
	migration, err := coreMigrations.ReadFile("migrations/00135_fork_test_android_releases.sql")
	if err != nil {
		return fmt.Errorf("read fork Android release migration: %w", err)
	}
	up, _, found := strings.Cut(string(migration), "-- +goose Down")
	if !found {
		return fmt.Errorf("fork Android release migration has no Down section")
	}
	if _, err := tx.ExecContext(ctx, up); err != nil {
		return fmt.Errorf("install fork Android release constraint: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO public.goose_db_version (version_id, is_applied) VALUES ($1, true)`, version+1); err != nil {
		return fmt.Errorf("record canonical migration history: %w", err)
	}
	return tx.Commit()
}
