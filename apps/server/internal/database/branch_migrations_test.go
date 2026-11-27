package database

import (
	"database/sql"
	"fmt"
	"io/fs"
	"testing"

	"github.com/pressly/goose/v3"
	"github.com/tilecast/tilecast/apps/server/internal/testdb"
)

func TestForkSchema136UpgradePreservesData(t *testing.T) {
	url := testdb.New(t)
	ctx := t.Context()
	if err := MigrateTo(ctx, url, 136); err != nil {
		t.Fatal(err)
	}
	db := openMigrationTestDB(t, url)
	fixtureExec(t, db, `
		INSERT INTO organization_settings (organization_name) VALUES ('Migration test');
		INSERT INTO playlists (id, organization_id, name)
			SELECT '00000000-0000-4000-8000-000000000001', id, 'Alert playlist' FROM organization_settings;
		INSERT INTO takeovers (id, organization_id, name, playlist_id, status, expires_at)
			SELECT '00000000-0000-4000-8000-000000000002', id, 'Existing NWS warning',
				'00000000-0000-4000-8000-000000000001', 'active', now() + interval '1 hour'
			FROM organization_settings;
		INSERT INTO alert_rules (id, organization_id, name)
			SELECT '00000000-0000-4000-8000-000000000003', id, 'NWS' FROM organization_settings;
		INSERT INTO alert_activations (alert_id, rule_id, event, severity, urgency, takeover_id)
			VALUES ('migration-test', '00000000-0000-4000-8000-000000000003', 'Tornado Warning',
				'Severe', 'Immediate', '00000000-0000-4000-8000-000000000002');
		INSERT INTO assets (id, organization_id, name, type, original_filename, detected_mime_type,
			sha256, original_size, processing_status, origin)
			SELECT '00000000-0000-4000-8000-000000000004', id, 'Dynamic Website', 'image', '',
				'image/png', decode(repeat('00', 32), 'hex'), 0, 'ready', 'library'
			FROM organization_settings;
		INSERT INTO widgets (asset_id, provider, configuration) VALUES
			('00000000-0000-4000-8000-000000000004', 'website',
			'{"url":"https://example.org/display?s={screen.id}","retryIntervalSeconds":0}');
	`)
	insertTestAPK(t, db)

	for range 2 {
		if err := Migrate(ctx, url); err != nil {
			t.Fatal(err)
		}
	}

	var class string
	if err := db.QueryRowContext(ctx, `SELECT class FROM takeovers WHERE name = 'Existing NWS warning'`).Scan(&class); err != nil || class != "emergency" {
		t.Fatalf("NWS backfill: class=%q, error=%v", class, err)
	}
	var enabled bool
	var retry int
	if err := db.QueryRowContext(ctx, `
		SELECT (configuration->>'urlTemplatesEnabled')::boolean, (configuration->>'retryIntervalSeconds')::int
		FROM widgets WHERE provider='website'
	`).Scan(&enabled, &retry); err != nil || !enabled || retry != 0 {
		t.Fatalf("Website settings after upgrade: urlTemplatesEnabled=%v, retry=%d, error=%v", enabled, retry, err)
	}
	var count int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM player_releases WHERE application_id='org.tilecast.player.test'`).Scan(&count); err != nil || count != 1 {
		t.Fatalf("existing TEST APK count=%d, error=%v", count, err)
	}
	assertBranchMigrationHistory(t, db)
}

func TestUpstreamMigrationHistoryAdoptsForkSequence(t *testing.T) {
	fsys, err := migrationFS()
	if err != nil {
		t.Fatal(err)
	}
	files := fsys.(memoryFS)
	files["00135_widget_font_references.sql"] = files["00136_widget_font_references.sql"]
	files["00136_takeover_classes.sql"] = files["00137_takeover_classes.sql"]
	files["00137_nws_takeover_class.sql"] = files["00138_nws_takeover_class.sql"]
	delete(files, "00135_fork_test_android_releases.sql")
	delete(files, "00136_widget_font_references.sql")
	delete(files, "00137_takeover_classes.sql")
	delete(files, "00138_nws_takeover_class.sql")
	root, err := fs.Sub(files, migrationDir)
	if err != nil {
		t.Fatal(err)
	}

	for _, version := range []int64{135, 136, 137} {
		t.Run(fmt.Sprint(version), func(t *testing.T) {
			url := testdb.New(t)
			db := openMigrationTestDB(t, url)
			original, err := goose.NewProvider(goose.DialectPostgres, db, root)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := original.UpTo(t.Context(), version); err != nil {
				t.Fatal(err)
			}
			if err := Migrate(t.Context(), url); err != nil {
				t.Fatal(err)
			}
			insertTestAPK(t, db)
			assertBranchMigrationHistory(t, db)
		})
	}
}

func openMigrationTestDB(t *testing.T, url string) *sql.DB {
	t.Helper()
	db, err := sql.Open("pgx", url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func fixtureExec(t *testing.T, db *sql.DB, query string) {
	t.Helper()
	if _, err := db.ExecContext(t.Context(), query); err != nil {
		t.Fatal(err)
	}
}

func insertTestAPK(t *testing.T, db *sql.DB) {
	t.Helper()
	fixtureExec(t, db, `
		INSERT INTO player_releases (id, source, platform, player_family, channel, version_code,
			version_name, application_id, minimum_sdk, published_at, apk_name, apk_size,
			apk_sha256, signing_certificate_sha256, manifest, manifest_signature, verification_status)
		VALUES (gen_random_uuid(), 'upload', 'android', 'android', 'beta', 100100001, '1.1.0-dev.1',
			'org.tilecast.player.test', 23, now(), 'tilecast-player.apk', 1,
			repeat('a', 64), repeat('b', 64), '{}', 'test-signature', 'verified_manifest');
	`)
}

func assertBranchMigrationHistory(t *testing.T, db *sql.DB) {
	t.Helper()
	var versions string
	var hasWidgetFonts, hasTakeoverClasses bool
	if err := db.QueryRowContext(t.Context(), `
		SELECT string_agg(version_id::text, ',' ORDER BY id),
			to_regclass('public.widget_font_references') IS NOT NULL,
			EXISTS (SELECT 1 FROM pg_attribute
				WHERE attrelid = 'public.takeovers'::regclass AND attname = 'class' AND NOT attisdropped)
		FROM goose_db_version WHERE is_applied AND version_id BETWEEN 135 AND 138
	`).Scan(&versions, &hasWidgetFonts, &hasTakeoverClasses); err != nil {
		t.Fatal(err)
	}
	if versions != "135,136,137,138" || !hasWidgetFonts || !hasTakeoverClasses {
		t.Fatalf("combined migration history = %q, widget fonts = %v, Takeover classes = %v", versions, hasWidgetFonts, hasTakeoverClasses)
	}
}
