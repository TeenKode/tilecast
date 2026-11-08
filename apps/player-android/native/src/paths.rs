//! Android storage locations for Player Core state.
//!
//! The SQLite database and installed configuration remain on internal app
//! storage. Verified CAS objects and partial downloads may share a directory
//! on an Android-provided app-specific removable volume. Keeping both on
//! the same filesystem preserves the CAS atomic-rename contract.

use std::path::{Path, PathBuf};

/// Core-owned state directory below internal app-private files.
pub const CORE_DIR_NAME: &str = "player-core";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CorePaths {
    pub root: PathBuf,
    pub state_db: PathBuf,
    pub cas_dir: PathBuf,
    pub partial_dir: PathBuf,
    pub installed_config: PathBuf,
}

/// Default layout, retained for existing hosts and tests.
pub fn core_paths(files_dir: &Path) -> CorePaths {
    core_paths_with_media(files_dir, None)
}

/// Optional media_root only moves CAS and partials, never secrets or SQLite.
pub fn core_paths_with_media(files_dir: &Path, media_root: Option<&Path>) -> CorePaths {
    let root = files_dir.join(CORE_DIR_NAME);
    let media = media_root.unwrap_or(&root);
    CorePaths {
        state_db: root.join("state.db"),
        cas_dir: media.join("cas"),
        partial_dir: media.join("partial"),
        installed_config: root.join(crate::config_host::INSTALLED_CONFIG_NAME),
        root,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_is_stable() {
        let paths = core_paths(Path::new("/data/user/0/org.tilecast.player/files"));
        assert_eq!(paths.root, PathBuf::from("/data/user/0/org.tilecast.player/files/player-core"));
        assert_eq!(paths.state_db, paths.root.join("state.db"));
        assert_eq!(paths.cas_dir, paths.root.join("cas"));
        assert_eq!(paths.partial_dir, paths.root.join("partial"));
        assert_eq!(paths.installed_config, paths.root.join("installed-config.json"));
    }

    #[test]
    fn external_media_only_moves_cas_and_partials() {
        let internal = core_paths(Path::new("/data/user/0/org.tilecast.player/files"));
        let usb = Path::new("/storage/ABCD/Android/data/org.tilecast.player/files/tilecast-player-media");
        let external = core_paths_with_media(Path::new("/data/user/0/org.tilecast.player/files"), Some(usb));
        assert_eq!(external.root, internal.root);
        assert_eq!(external.state_db, internal.state_db);
        assert_eq!(external.installed_config, internal.installed_config);
        assert_eq!(external.cas_dir, usb.join("cas"));
        assert_eq!(external.partial_dir, usb.join("partial"));
    }

    #[test]
    fn layout_never_points_at_room_files() {
        let paths = core_paths(Path::new("/files"));
        for path in [&paths.state_db, &paths.cas_dir, &paths.partial_dir] {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            assert!(!name.ends_with(".db-shm") && !name.ends_with(".db-wal"));
            assert!(path.starts_with(&paths.root));
        }
        assert_ne!(paths.state_db.extension().and_then(|e| e.to_str()), Some("room"));
    }
}
