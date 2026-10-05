#![cfg(all(unix, feature = "launcher"))]

use serde_json::Value;
use std::fs;
use std::os::unix::fs::{symlink, PermissionsExt};
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;
use std::time::{Duration, Instant};

fn directory() -> PathBuf {
    static NEXT: AtomicUsize = AtomicUsize::new(0);
    let name = format!(
        "search-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    );
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../tmp")
        .join(name);
    fs::create_dir_all(&path).unwrap();
    path
}

fn launcher() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_ores-launcher"));
    command.env("OTEL_SERVICE_NAME", "launcher-search-test");
    command.stdout(Stdio::null()).stderr(Stdio::piped());
    command
}

fn run(command: &mut Command) -> Output {
    use std::io::Read;
    let mut child = command.spawn().unwrap();
    let mut stderr = child.stderr.take().unwrap();
    let reader = thread::spawn(move || {
        let mut bytes = Vec::new();
        stderr.read_to_end(&mut bytes).unwrap();
        bytes
    });
    let deadline = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = child.try_wait().unwrap() {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            panic!("PATH/signal test exceeded its deadline");
        }
        thread::sleep(Duration::from_millis(10));
    };
    Output {
        status,
        stdout: Vec::new(),
        stderr: reader.join().unwrap(),
    }
}

fn events(output: &Output) -> Vec<Value> {
    String::from_utf8_lossy(&output.stderr)
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

fn script(path: &Path, contents: &str, executable: bool) {
    fs::write(path, contents).unwrap();
    let mode = if executable { 0o700 } else { 0o600 };
    fs::set_permissions(path, fs::Permissions::from_mode(mode)).unwrap();
}

#[test]
fn path_search_continues_after_missing_not_directory_and_denied() {
    let root = directory();
    let denied = root.join("denied");
    let valid = root.join("valid");
    fs::create_dir_all(&denied).unwrap();
    fs::create_dir_all(&valid).unwrap();
    script(&denied.join("app"), "exit 93\n", false);
    script(&root.join("file"), "not a directory", false);
    symlink("/bin/true", valid.join("app")).unwrap();
    let path =
        std::env::join_paths([root.join("absent"), root.join("file"), denied, valid]).unwrap();
    let output = run(launcher().arg("app").env("PATH", path));
    assert!(output.status.success());
    assert_eq!(events(&output).len(), 1);
}

#[test]
fn exhausted_path_preserves_permission_denied_instead_of_not_found() {
    let root = directory();
    script(&root.join("app"), "exit 93\n", false);
    let path = std::env::join_paths([root.clone(), root.join("absent")]).unwrap();
    let output = run(launcher().arg("app").env("PATH", path));
    assert_eq!(output.status.code(), Some(126));
    assert_eq!(events(&output)[1]["fields"]["error.os_code"], libc::EACCES);
}

#[test]
fn path_search_stops_at_executable_text_without_falling_back_to_shell_or_next_entry() {
    let root = directory();
    let valid = root.join("valid");
    fs::create_dir_all(&valid).unwrap();
    script(&root.join("app"), "exit 93\n", true);
    symlink("/bin/true", valid.join("app")).unwrap();
    let path = std::env::join_paths([root, valid]).unwrap();
    let output = run(launcher().arg("app").env("PATH", path));
    assert_eq!(output.status.code(), Some(126));
    assert_eq!(events(&output)[1]["fields"]["error.os_code"], libc::ENOEXEC);
}

#[test]
fn explicit_empty_path_searches_cwd_but_unset_path_does_not() {
    let root = directory();
    symlink("/bin/true", root.join("only-here")).unwrap();
    let output = run(launcher()
        .arg("only-here")
        .current_dir(&root)
        .env("PATH", ""));
    assert!(output.status.success());
    let output = run(launcher()
        .arg("only-here")
        .current_dir(&root)
        .env_remove("PATH"));
    assert_eq!(output.status.code(), Some(127));
    let output = run(launcher().arg("true").env_remove("PATH"));
    assert!(output.status.success());
}

#[test]
fn explicit_relative_paths_bypass_path_search() {
    let root = directory();
    symlink("/bin/true", root.join("app")).unwrap();
    let output = run(launcher()
        .arg("./app")
        .current_dir(root)
        .env("PATH", "/absent"));
    assert!(output.status.success());
}

#[test]
fn explicit_shebang_is_supported_when_its_interpreter_exists() {
    let path = directory().join("script");
    script(&path, "#!/bin/sh\nexit 23\n", true);
    let output = run(launcher().arg(path));
    assert_eq!(output.status.code(), Some(23));
    assert_eq!(events(&output).len(), 1);
}

#[test]
fn successful_exec_restores_default_sigpipe_for_the_target_program() {
    // Explicit shell fixture, not implicit ENOEXEC fallback. Unlike a Rust main,
    // this fixture does not reset SIGPIPE to ignored before the assertion.
    let output = run(launcher().args(["/bin/sh", "-c", "kill -PIPE $$; exit 93"]));
    assert_eq!(output.status.signal(), Some(libc::SIGPIPE));
    assert_eq!(events(&output).len(), 1);
}
