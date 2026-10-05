use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
};

pub const SCHEMA_VERSION: &str = "beamscale.desktop-runtime/v1";
pub const RUNTIME_KIND: &str = "beamscale-single-beam";
pub const MAX_MANIFEST_BYTES: u64 = 64 * 1024;
const MAX_RELEASE_ID_BYTES: usize = 128;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RuntimeManifest {
    pub schema_version: String,
    pub runtime_kind: String,
    pub release_id: String,
    pub executable: PathBuf,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ValidatedRuntimeManifest {
    pub schema_version: String,
    pub runtime_kind: String,
    pub release_id: String,
    pub executable: PathBuf,
    pub sha256: String,
}

pub fn configured_manifest_path(data_root: &Path) -> Result<Option<PathBuf>> {
    if let Some(value) = std::env::var_os("BMSCL_DESKTOP_RUNTIME_MANIFEST") {
        if value.is_empty() {
            bail!("BMSCL_DESKTOP_RUNTIME_MANIFEST must not be empty");
        }
        return resolve_manifest_candidate(PathBuf::from(value), "configured").map(Some);
    }

    let default = data_root.join("runtime-manifest.json");
    match fs::symlink_metadata(&default) {
        Ok(_) => resolve_manifest_candidate(default, "default").map(Some),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error)
            .with_context(|| format!("inspect default runtime manifest {}", default.display())),
    }
}

fn validate_path_text(path: &Path, label: &str) -> Result<()> {
    let text = path
        .to_str()
        .with_context(|| format!("{label} path is not valid UTF-8: {}", path.display()))?;
    if text.chars().any(char::is_control) {
        bail!("{label} path contains control characters");
    }
    Ok(())
}

fn resolve_manifest_candidate(path: PathBuf, label: &str) -> Result<PathBuf> {
    validate_path_text(&path, &format!("{label} runtime manifest"))?;
    if !path.is_absolute() {
        bail!(
            "{label} runtime manifest path must be absolute: {}",
            path.display()
        );
    }

    let metadata = fs::symlink_metadata(&path)
        .with_context(|| format!("inspect {label} runtime manifest {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "{label} runtime manifest must be a regular non-symlink file: {}",
            path.display()
        );
    }

    Ok(path)
}

pub fn load_and_validate(path: &Path) -> Result<ValidatedRuntimeManifest> {
    validate_path_text(path, "runtime manifest")?;
    if !path.is_absolute() {
        bail!("runtime manifest path must be absolute: {}", path.display());
    }

    let bytes = read_manifest_bounded(path)?;
    let manifest: RuntimeManifest = serde_json::from_slice(&bytes)
        .with_context(|| format!("parse runtime manifest {}", path.display()))?;
    validate(manifest)
}

fn read_manifest_bounded(path: &Path) -> Result<Vec<u8>> {
    let path_before = fs::symlink_metadata(path)
        .with_context(|| format!("inspect runtime manifest {}", path.display()))?;
    validate_manifest_metadata(&path_before, path)?;

    let mut file = fs::File::open(path)
        .with_context(|| format!("open runtime manifest {}", path.display()))?;
    let opened_before = file
        .metadata()
        .with_context(|| format!("inspect opened runtime manifest {}", path.display()))?;
    validate_manifest_metadata(&opened_before, path)?;
    ensure_same_file_identity(
        &path_before,
        &opened_before,
        path,
        "runtime manifest path changed before open",
    )?;
    ensure_metadata_stable(
        &path_before,
        &opened_before,
        path,
        "runtime manifest metadata changed before open",
    )?;

    let mut bytes = Vec::new();
    file.by_ref()
        .take(MAX_MANIFEST_BYTES + 1)
        .read_to_end(&mut bytes)
        .with_context(|| format!("read runtime manifest {}", path.display()))?;
    if bytes.len() as u64 > MAX_MANIFEST_BYTES {
        bail!(
            "runtime manifest exceeded {} bytes while reading: {}",
            MAX_MANIFEST_BYTES,
            path.display()
        );
    }

    let opened_after = file
        .metadata()
        .with_context(|| format!("re-inspect opened runtime manifest {}", path.display()))?;
    validate_manifest_metadata(&opened_after, path)?;
    ensure_same_file_identity(
        &opened_before,
        &opened_after,
        path,
        "runtime manifest opened-file identity changed while reading",
    )?;
    ensure_metadata_stable(
        &opened_before,
        &opened_after,
        path,
        "runtime manifest metadata changed while reading",
    )?;
    if opened_after.len() != bytes.len() as u64 {
        bail!(
            "runtime manifest size changed while reading: {}",
            path.display()
        );
    }

    let path_after = fs::symlink_metadata(path)
        .with_context(|| format!("re-inspect runtime manifest path {}", path.display()))?;
    validate_manifest_metadata(&path_after, path)?;
    ensure_same_file_identity(
        &opened_after,
        &path_after,
        path,
        "runtime manifest path identity changed while reading",
    )?;
    ensure_metadata_stable(
        &opened_after,
        &path_after,
        path,
        "runtime manifest path metadata changed while reading",
    )?;

    Ok(bytes)
}

fn validate_manifest_metadata(metadata: &fs::Metadata, path: &Path) -> Result<()> {
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "runtime manifest must be a regular non-symlink file: {}",
            path.display()
        );
    }
    if metadata.len() > MAX_MANIFEST_BYTES {
        bail!(
            "runtime manifest exceeds {} bytes: {}",
            MAX_MANIFEST_BYTES,
            path.display()
        );
    }
    Ok(())
}

fn ensure_metadata_stable(
    before: &fs::Metadata,
    after: &fs::Metadata,
    path: &Path,
    message: &str,
) -> Result<()> {
    if before.len() != after.len() {
        bail!("{message}: {}", path.display());
    }
    let before_modified = before.modified().ok();
    let after_modified = after.modified().ok();
    if before_modified.is_some() && after_modified.is_some() && before_modified != after_modified {
        bail!("{message}: {}", path.display());
    }
    Ok(())
}

#[cfg(unix)]
fn ensure_same_file_identity(
    before: &fs::Metadata,
    after: &fs::Metadata,
    path: &Path,
    message: &str,
) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    if before.dev() != after.dev() || before.ino() != after.ino() {
        bail!("{message}: {}", path.display());
    }
    Ok(())
}

#[cfg(not(unix))]
fn ensure_same_file_identity(
    _before: &fs::Metadata,
    _after: &fs::Metadata,
    _path: &Path,
    _message: &str,
) -> Result<()> {
    // std does not expose a stable cross-platform file identity. The path is
    // still checked as a regular non-symlink before and after the opened-file
    // read, and opened-file size/mtime are required to remain stable.
    Ok(())
}

pub fn validate(manifest: RuntimeManifest) -> Result<ValidatedRuntimeManifest> {
    if manifest.schema_version != SCHEMA_VERSION {
        bail!(
            "unsupported runtime manifest schema_version {:?}; expected {SCHEMA_VERSION:?}",
            manifest.schema_version
        );
    }
    if manifest.runtime_kind != RUNTIME_KIND {
        bail!(
            "unsupported runtime_kind {:?}; expected {RUNTIME_KIND:?}",
            manifest.runtime_kind
        );
    }
    validate_release_id(&manifest.release_id)?;
    validate_sha256(&manifest.sha256)?;

    validate_path_text(&manifest.executable, "runtime manifest executable")?;
    if !manifest.executable.is_absolute() {
        bail!(
            "runtime manifest executable must be absolute: {}",
            manifest.executable.display()
        );
    }

    let metadata = fs::symlink_metadata(&manifest.executable).with_context(|| {
        format!(
            "inspect runtime manifest executable {}",
            manifest.executable.display()
        )
    })?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "runtime manifest executable must be a regular non-symlink file: {}",
            manifest.executable.display()
        );
    }
    if metadata.len() > crate::MAX_PINNED_TOOL_BYTES {
        bail!(
            "runtime manifest executable exceeds {} bytes: {}",
            crate::MAX_PINNED_TOOL_BYTES,
            manifest.executable.display()
        );
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            bail!(
                "runtime manifest executable is not executable: {}",
                manifest.executable.display()
            );
        }
    }

    let actual = sha256_file(&manifest.executable)?;
    if actual != manifest.sha256 {
        bail!(
            "runtime executable digest mismatch for {}: expected {}, actual {}",
            manifest.executable.display(),
            manifest.sha256,
            actual
        );
    }

    let executable = fs::canonicalize(&manifest.executable).with_context(|| {
        format!(
            "canonicalize validated runtime manifest executable {}",
            manifest.executable.display()
        )
    })?;

    Ok(ValidatedRuntimeManifest {
        schema_version: manifest.schema_version,
        runtime_kind: manifest.runtime_kind,
        release_id: manifest.release_id,
        executable,
        sha256: actual,
    })
}

fn validate_release_id(value: &str) -> Result<()> {
    if value.is_empty()
        || value.len() > MAX_RELEASE_ID_BYTES
        || value != value.trim()
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    {
        bail!(
            "release_id must be 1..={MAX_RELEASE_ID_BYTES} ASCII letters, digits, '.', '_' or '-' with no edge whitespace"
        );
    }
    Ok(())
}

fn validate_sha256(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        bail!("sha256 must be exactly 64 lowercase hexadecimal characters");
    }
    Ok(())
}

pub fn sha256_file(path: &Path) -> Result<String> {
    // The daemon owns one executable hashing authority. Reuse it here so
    // runtime-manifest pins, persistent service pins, and doctor checks all
    // share the same bounded identity/size/mtime race checks.
    crate::executable_sha256(path, "desktop runtime")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn executable_file(root: &Path, bytes: &[u8]) -> PathBuf {
        let path = root.join("runtime");
        fs::write(&path, bytes).expect("write runtime");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&path, fs::Permissions::from_mode(0o700))
                .expect("mark runtime executable");
        }
        path
    }

    fn manifest_for(executable: PathBuf, sha256: String) -> RuntimeManifest {
        RuntimeManifest {
            schema_version: SCHEMA_VERSION.into(),
            runtime_kind: RUNTIME_KIND.into(),
            release_id: "otp-29.1.1-beamscale-2026.09".into(),
            executable,
            sha256,
        }
    }

    #[test]
    fn manifest_candidate_requires_absolute_non_symlink_file() {
        assert!(
            resolve_manifest_candidate(PathBuf::from("runtime-manifest.json"), "configured")
                .is_err()
        );

        let root = tempfile::tempdir().expect("temp root");
        let manifest = root.path().join("runtime-manifest.json");
        fs::write(&manifest, b"{}").expect("write manifest");
        assert_eq!(
            resolve_manifest_candidate(manifest.clone(), "configured").expect("resolve"),
            manifest
        );

        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            let link = root.path().join("runtime-manifest-link.json");
            symlink(&manifest, &link).expect("symlink manifest");
            assert!(resolve_manifest_candidate(link, "configured").is_err());
        }
    }

    #[cfg(unix)]
    #[test]
    fn load_rejects_symlink_manifest_path() {
        use std::os::unix::fs::symlink;

        let root = tempfile::tempdir().expect("temp root");
        let executable = executable_file(root.path(), b"hello");
        let manifest = manifest_for(
            executable,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
        );
        let manifest_path = root.path().join("runtime-manifest.json");
        fs::write(
            &manifest_path,
            serde_json::to_vec(&manifest).expect("manifest json"),
        )
        .expect("write manifest");
        let link = root.path().join("runtime-manifest-link.json");
        symlink(&manifest_path, &link).expect("symlink manifest");

        assert!(load_and_validate(&link).is_err());
    }

    #[test]
    fn rejects_control_characters_in_manifest_and_executable_paths() {
        let root = tempfile::tempdir().expect("temp root");
        let manifest_path = root.path().join("runtime\nmanifest.json");
        fs::write(&manifest_path, b"{}").expect("write manifest");
        assert!(
            resolve_manifest_candidate(manifest_path, "configured").is_err(),
            "manifest control text must be rejected"
        );

        let executable = executable_file(root.path(), b"hello");
        let mut manifest = manifest_for(
            executable,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
        );
        manifest.executable = root.path().join("runtime\nname");
        assert!(
            validate(manifest).is_err(),
            "executable control text must be rejected"
        );
    }

    #[test]
    fn validates_digest_pinned_single_beam_manifest() {
        let root = tempfile::tempdir().expect("temp root");
        let executable = executable_file(root.path(), b"hello");
        let manifest = manifest_for(
            executable.clone(),
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
        );
        let validated = validate(manifest).expect("manifest validates");
        assert_eq!(
            validated.executable,
            fs::canonicalize(executable).expect("canonical executable")
        );
        assert_eq!(validated.runtime_kind, RUNTIME_KIND);
    }

    #[test]
    fn rejects_wrong_digest_runtime_kind_and_unknown_fields() {
        let root = tempfile::tempdir().expect("temp root");
        let executable = executable_file(root.path(), b"hello");

        let mut wrong_digest = manifest_for(executable.clone(), "0".repeat(64));
        assert!(validate(wrong_digest.clone()).is_err());

        wrong_digest.sha256 =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into();
        wrong_digest.runtime_kind = "arbitrary-process".into();
        assert!(validate(wrong_digest).is_err());

        let text = format!(
            r#"{{"schema_version":"{SCHEMA_VERSION}","runtime_kind":"{RUNTIME_KIND}","release_id":"r1","executable":{},"sha256":"{}","shell":"rm -rf /"}}"#,
            serde_json::to_string(&executable).expect("path json"),
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
        assert!(serde_json::from_str::<RuntimeManifest>(&text).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlink_executable() {
        use std::os::unix::fs::symlink;

        let root = tempfile::tempdir().expect("temp root");
        let executable = executable_file(root.path(), b"hello");
        let link = root.path().join("runtime-link");
        symlink(&executable, &link).expect("symlink runtime");
        let manifest = manifest_for(
            link,
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824".into(),
        );
        assert!(validate(manifest).is_err());
    }

    #[test]
    fn json_schema_matches_rust_contract_identity_and_closed_shape() {
        let schema: serde_json::Value = serde_json::from_str(include_str!(
            "../contracts/desktop-runtime-manifest-v1.schema.json"
        ))
        .expect("schema JSON");

        assert_eq!(
            schema["properties"]["schema_version"]["const"],
            SCHEMA_VERSION
        );
        assert_eq!(schema["properties"]["runtime_kind"]["const"], RUNTIME_KIND);
        assert_eq!(schema["additionalProperties"], false);
        assert_eq!(schema["properties"]["sha256"]["pattern"], "^[0-9a-f]{64}$");

        let required = schema["required"].as_array().expect("required array");
        for field in [
            "schema_version",
            "runtime_kind",
            "release_id",
            "executable",
            "sha256",
        ] {
            assert!(
                required.iter().any(|value| value.as_str() == Some(field)),
                "schema must require {field}"
            );
        }
    }

    #[test]
    fn sha256_known_vector_matches() {
        let root = tempfile::tempdir().expect("temp root");
        let executable = executable_file(root.path(), b"hello");
        assert_eq!(
            sha256_file(&executable).expect("hash"),
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
    }
}
