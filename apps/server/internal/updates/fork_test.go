package updates

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

func TestForkAndroidReleaseImportsIntoDatabase(t *testing.T) {
	pool := updatesTestPool(t)
	f := newReleaseFixture(t)
	apk := []byte("test fork APK")
	f.addArtifact(AndroidArtifactName, apk)
	f.signed(androidManifestName, Manifest{
		SchemaVersion: 1, Product: "tilecast-player", ApplicationID: ApplicationID + ".test",
		VersionCode: 100000101, VersionName: "1.0.1-dev.1", Channel: "beta", MinimumSDK: SupportedMinSDK,
		APKAssetName: AndroidArtifactName, APKSizeBytes: int64(len(apk)), APKSHA256: digest(apk),
		SigningCertificateSHA256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
	})
	service := newUpdatesService(t, pool, f, f.provider(f.release(9101, "v1.0.1-dev.1", true)))
	ctx := context.Background()
	if err := service.Check(ctx); err != nil {
		t.Fatal(err)
	}
	stored := storedReleases(t, pool)
	if len(stored) != 1 || stored[0].Name != "1.0.1-dev.1" || stored[0].Family != FamilyAndroid {
		t.Fatalf("expected one imported Android Dev release: %+v", stored)
	}
	for _, tc := range []struct {
		name, application, version, channel string
	}{
		{"other package", "other.app", "1.0.1-dev.1", "beta"},
		{"stable test package", ApplicationID + ".test", "1.0.1", "stable"},
		{"wrong channel", ApplicationID + ".test", "1.0.1-dev.1", "stable"},
		{"zero slot", ApplicationID + ".test", "1.0.1-dev.0", "beta"},
		{"stable slot", ApplicationID + ".test", "1.0.1-dev.99", "beta"},
		{"leading zero", ApplicationID + ".test", "1.0.1-dev.01", "beta"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := pool.Exec(ctx, `UPDATE player_releases SET application_id=$1,version_name=$2,channel=$3 WHERE id=$4`, tc.application, tc.version, tc.channel, stored[0].ID)
			var pgErr *pgconn.PgError
			if !errors.As(err, &pgErr) || pgErr.Code != "23514" || pgErr.ConstraintName != "player_releases_family_shape_check" {
				t.Fatalf("expected family shape rejection, got %v", err)
			}
		})
	}
}

func TestForkAndroidApplication(t *testing.T) {
	for _, test := range []struct {
		application, version, channel string
		want                          bool
	}{
		{ApplicationID, "1.0.1", "stable", true},
		{ApplicationID + ".test", "1.0.1-dev.1", "beta", true},
		{ApplicationID + ".test", "1.0.1-dev.1", "stable", false},
		{ApplicationID + ".test", "1.0.1", "stable", false},
		{"other.app", "1.0.1-dev.1", "beta", false},
	} {
		if got := validAndroidApplication(Manifest{ApplicationID: test.application, VersionName: test.version, Channel: test.channel}); got != test.want {
			t.Errorf("%s %s %s: got %t, want %t", test.application, test.version, test.channel, got, test.want)
		}
	}
}
