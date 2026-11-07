-- +goose Up

-- Keep the installed test package identity, but admit it only for Dev builds.
-- Signature, certificate, artifact metadata and version ordering are still
-- verified by the update service and the installed Player.
ALTER TABLE player_releases DROP CONSTRAINT player_releases_family_shape_check;
ALTER TABLE player_releases ADD CONSTRAINT player_releases_family_shape_check CHECK (
    (player_family = 'android'
        AND platform = 'android'
        AND architecture = ''
        AND application_id IS NOT NULL
        AND (application_id = 'org.tilecast.player'
            OR (application_id = 'org.tilecast.player.test'
                AND channel = 'beta'
                AND version_name ~ '^[0-9]+\.[0-9]+\.[0-9]+-dev\.([1-9]|[1-8][0-9]|9[0-8])$'))
        AND minimum_sdk >= 23
        AND apk_name = 'tilecast-player.apk')
    OR
    (player_family = 'electron-linux'
        AND platform = 'linux'
        AND architecture = ''
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-player.AppImage')
    OR
    (player_family = 'edge'
        AND platform = 'linux'
        AND architecture IN ('x86_64', 'aarch64')
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-edge-' || version_name || '-' || architecture || '.tar.zst'
        AND manifest_bytes IS NOT NULL
        AND state_schema_version > 0)
    OR
    (player_family = 'windows'
        AND platform = 'windows'
        AND architecture IN ('x86_64', 'aarch64')
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-windows-' || version_name || '-' || architecture || '.msix'
        AND manifest_bytes IS NOT NULL
        AND state_schema_version IS NULL)
);

-- +goose Down

-- Refuse a downgrade while test releases exist rather than deleting them.
ALTER TABLE player_releases DROP CONSTRAINT player_releases_family_shape_check;
ALTER TABLE player_releases ADD CONSTRAINT player_releases_family_shape_check CHECK (
    (player_family = 'android'
        AND platform = 'android'
        AND architecture = ''
        AND application_id = 'org.tilecast.player'
        AND minimum_sdk >= 23
        AND apk_name = 'tilecast-player.apk')
    OR
    (player_family = 'electron-linux'
        AND platform = 'linux'
        AND architecture = ''
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-player.AppImage')
    OR
    (player_family = 'edge'
        AND platform = 'linux'
        AND architecture IN ('x86_64', 'aarch64')
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-edge-' || version_name || '-' || architecture || '.tar.zst'
        AND manifest_bytes IS NOT NULL
        AND state_schema_version > 0)
    OR
    (player_family = 'windows'
        AND platform = 'windows'
        AND architecture IN ('x86_64', 'aarch64')
        AND application_id IS NULL
        AND minimum_sdk IS NULL
        AND apk_name = 'tilecast-windows-' || version_name || '-' || architecture || '.msix'
        AND manifest_bytes IS NOT NULL
        AND state_schema_version IS NULL)
);
