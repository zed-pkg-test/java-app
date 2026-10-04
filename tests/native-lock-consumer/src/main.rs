use zed_interfaces::{
    ArtifactFormat, Lockfile, LockfileError, NativeArtifact, NativeDependencyLock, NativeRegistry,
    NativeVersionCandidate,
};

fn artifact(digit: char) -> NativeArtifact {
    NativeArtifact {
        sha256: std::iter::repeat(digit).take(64).collect(),
        size: 1024,
        format: ArtifactFormat::TarGz,
    }
}

fn exact_lock(
    registry: NativeRegistry,
    package: &str,
    declared: &str,
    version: &str,
    digit: char,
) -> NativeDependencyLock {
    NativeDependencyLock::resolve(
        registry,
        package,
        declared,
        &[NativeVersionCandidate {
            version: version.to_string(),
            artifact: artifact(digit),
        }],
    )
    .expect("fixture must resolve")
}

fn legacy_documents_remain_compatible() {
    let legacy = r#"
version = 1

[[package]]
org = "zedtest"
name = "core"
version = "1.0.0"
sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
size = 128
format = "tar.gz"
vcs_tag = "v1.0.0"
vcs_commit = "0123456789abcdef0123456789abcdef01234567"
source = "file:///tmp/registry"
"#;

    let lockfile = Lockfile::parse(legacy).expect("legacy lockfile must parse");
    assert!(lockfile.native_dependencies.is_empty());
    assert!(!lockfile.to_toml_string().unwrap().contains("native-dependency"));
}

fn ordering_is_deterministic_and_sources_coexist() {
    let npm = exact_lock(NativeRegistry::Npm, "core", "^1.2.3", "1.9.0", 'a');
    let cargo = exact_lock(NativeRegistry::Cargo, "core", "1.2.3", "1.9.0", 'b');

    let mut forward = Lockfile::default();
    forward.upsert_native_dependency(cargo.clone()).unwrap();
    forward.upsert_native_dependency(npm.clone()).unwrap();

    let mut reverse = Lockfile::default();
    reverse.upsert_native_dependency(npm).unwrap();
    reverse.upsert_native_dependency(cargo).unwrap();

    let forward_toml = forward.to_toml_string().unwrap();
    assert_eq!(forward_toml, reverse.to_toml_string().unwrap());
    assert_eq!(forward_toml.matches("[[native-dependency]]").count(), 2);

    let parsed = Lockfile::parse(&forward_toml).unwrap();
    assert_eq!(parsed, forward);
    assert_eq!(
        parsed
            .find_native_dependency(NativeRegistry::Npm, "core")
            .unwrap()
            .artifact
            .sha256,
        "a".repeat(64)
    );
    assert_eq!(
        parsed
            .find_native_dependency(NativeRegistry::Cargo, "core")
            .unwrap()
            .artifact
            .sha256,
        "b".repeat(64)
    );
}

fn upsert_replaces_one_source_identity() {
    let old = exact_lock(NativeRegistry::Npm, "core", "1.2.3", "1.2.3", 'a');
    let new = exact_lock(NativeRegistry::Npm, "core", "1.2.4", "1.2.4", 'b');

    let mut lockfile = Lockfile::default();
    lockfile.upsert_native_dependency(old).unwrap();
    lockfile.upsert_native_dependency(new).unwrap();

    assert_eq!(lockfile.native_dependencies.len(), 1);
    assert_eq!(
        lockfile
            .find_native_dependency(NativeRegistry::Npm, "core")
            .unwrap()
            .package
            .version,
        "1.2.4"
    );
}

fn invalid_provenance_fails_closed() {
    let first = exact_lock(NativeRegistry::Npm, "core", "1.2.3", "1.2.3", 'a');
    let second = exact_lock(NativeRegistry::Npm, "core", "1.2.4", "1.2.4", 'b');
    let duplicate = Lockfile {
        version: Lockfile::CURRENT_VERSION,
        packages: Vec::new(),
        native_dependencies: vec![first, second],
        nix_adapters: Vec::new(),
    };
    assert!(matches!(
        duplicate.to_toml_string(),
        Err(LockfileError::DuplicateNativeDependency(_))
    ));

    let mut drift = exact_lock(NativeRegistry::Npm, "core", "^1.2.3", "1.9.0", 'c');
    drift.requirement.canonical = "^1.3.0".to_string();
    let mut lockfile = Lockfile::default();
    assert!(matches!(
        lockfile.upsert_native_dependency(drift),
        Err(LockfileError::InvalidNativeDependency(_))
    ));

    let mut invalid_artifact =
        exact_lock(NativeRegistry::Cargo, "core", "1.2.3", "1.9.0", 'd');
    invalid_artifact.artifact.sha256 = "0".repeat(64);
    lockfile.native_dependencies.push(invalid_artifact);
    assert!(matches!(
        lockfile.to_toml_string(),
        Err(LockfileError::InvalidNativeDependency(_))
    ));
}

fn main() {
    legacy_documents_remain_compatible();
    ordering_is_deterministic_and_sources_coexist();
    upsert_replaces_one_source_identity();
    invalid_provenance_fails_closed();
}
