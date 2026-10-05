#![cfg(all(unix, feature = "launcher"))]

use serde_json::Value;
use std::ffi::OsString;
use std::fs;
use std::io::{Read, Write};
use std::os::fd::OwnedFd;
use std::os::unix::ffi::{OsStrExt, OsStringExt};
use std::os::unix::fs::{symlink, PermissionsExt};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::process::{Child, Command, Output, Stdio};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

fn launcher() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_ores-launcher"));
    command.env_remove("ORES_LAUNCHER_TEST_MODE");
    command.env_remove("ORES_LAUNCHER_TEST_EXIT");
    command.env("OTEL_SERVICE_NAME", "launcher-adversarial-test");
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    command
}

fn probe() -> &'static PathBuf {
    static PROBE: OnceLock<PathBuf> = OnceLock::new();
    PROBE.get_or_init(|| {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../tmp")
            .join(format!(
                "launcher-adversarial-{}-{nonce}",
                std::process::id()
            ));
        fs::create_dir_all(&directory).unwrap();
        let source = directory.join("probe.rs");
        let binary = directory.join("probe");
        fs::write(&source, include_str!("fixtures/launcher_probe.rs")).unwrap();
        let status = Command::new(std::env::var_os("RUSTC").unwrap_or_else(|| "rustc".into()))
            .arg("--edition=2021")
            .arg(source)
            .arg("-o")
            .arg(&binary)
            .status()
            .unwrap();
        assert!(status.success(), "compile the native Rust fixture");
        binary
    })
}

fn drain(reader: Option<impl Read + Send + 'static>) -> thread::JoinHandle<Vec<u8>> {
    thread::spawn(move || {
        let mut output = Vec::new();
        if let Some(mut reader) = reader {
            reader.read_to_end(&mut output).unwrap();
        }
        output
    })
}

// Drain both pipes while polling. Waiting before draining deadlocks when the
// native argv fixture writes more than a pipe buffer, hiding launcher defects.
fn finish(mut child: Child) -> Output {
    let stdout = drain(child.stdout.take());
    let stderr = drain(child.stderr.take());
    let deadline = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = child.try_wait().unwrap() {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            panic!("launcher exceeded the bounded adversarial test deadline");
        }
        thread::sleep(Duration::from_millis(10));
    };
    Output {
        status,
        stdout: stdout.join().unwrap(),
        stderr: stderr.join().unwrap(),
    }
}

fn run(command: &mut Command) -> Output {
    finish(command.spawn().unwrap())
}

fn records(output: &Output) -> Vec<Value> {
    output
        .stderr
        .split(|byte| *byte == b'\n')
        .filter(|line| !line.is_empty())
        .map(|line| serde_json::from_slice(line).expect("one ores-otel JSON record per line"))
        .collect()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn executable(name: &str, contents: &[u8]) -> PathBuf {
    let path = probe().with_file_name(name);
    fs::write(&path, contents).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    path
}

fn broken_stderr() -> Stdio {
    let (writer, reader) = UnixStream::pair().unwrap();
    drop(reader);
    Stdio::from(OwnedFd::from(writer))
}

#[test]
fn attached_short_values_cannot_be_misclassified_as_sensitive_option_names() {
    for alias in ["-p", "-P", "-k", "-u", "-H"] {
        for value in [
            "synthetic-token-needle",
            "synthetic-password-needle",
            "synthetic-secret-needle",
            "synthetic-plain-needle",
        ] {
            let argument = format!("{alias}{value}");
            let output = run(launcher().arg(probe()).arg(&argument));
            assert!(output.status.success());
            assert!(
                !String::from_utf8_lossy(&output.stderr).contains(value),
                "attached short-option value leaked for alias {alias}"
            );
            assert!(String::from_utf8_lossy(&output.stdout).contains(&hex(argument.as_bytes())));
        }
    }
}

#[test]
fn non_utf8_option_masks_its_following_value_without_mutating_either() {
    let option = OsString::from_vec(b"--token\xff".to_vec());
    let value = "synthetic-opaque-needle";
    let output = run(launcher().arg(probe()).arg(&option).arg(value));
    assert!(output.status.success());
    assert!(!String::from_utf8_lossy(&output.stderr).contains(value));
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains(&hex(option.as_bytes())));
    assert!(stdout.contains(&hex(value.as_bytes())));
}

#[test]
fn ambiguous_sensitive_keys_are_not_echoed_as_safe_option_names() {
    for argument in [
        "--token:synthetic-needle",
        "--passwordsynthetic-needle",
        "--token-synthetic-needle=value",
    ] {
        let output = run(launcher().arg(probe()).arg(argument));
        assert!(output.status.success());
        assert!(!String::from_utf8_lossy(&output.stderr).contains("synthetic-needle"));
        assert!(String::from_utf8_lossy(&output.stdout).contains(&hex(argument.as_bytes())));
    }
}

#[test]
fn log_bounds_do_not_truncate_the_executed_argument_vector() {
    let arguments: Vec<_> = (0..80)
        .map(|index| format!("{index}:{}", "\u{1f642}".repeat(256)))
        .collect();
    let output = run(launcher().arg(probe()).args(&arguments));
    assert!(output.status.success());
    let events = records(&output);
    assert_eq!(events.len(), 1);
    let fields = &events[0]["fields"];
    assert_eq!(fields["process.command_args_count"], 81);
    assert_eq!(fields["process.command_args"].as_array().unwrap().len(), 33);
    assert_eq!(fields["process.command_args"][32], "[49 arguments omitted]");
    assert!(output.stderr.len() < 32 * 1024, "startup log stays bounded");
    let stdout = String::from_utf8_lossy(&output.stdout);
    for (index, argument) in arguments.iter().enumerate() {
        assert!(stdout.contains(&format!("arg{index}={}\n", hex(argument.as_bytes()))));
    }
}

#[test]
fn stdin_and_stdout_are_forwarded_byte_for_byte() {
    let input = b"stdin\0\xff\n$(not-a-shell)\n";
    let mut child = launcher()
        .arg("/bin/cat")
        .stdin(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    let output = finish(child);
    assert!(output.status.success());
    assert_eq!(output.stdout, input);
    assert_eq!(records(&output).len(), 1);
}

#[test]
fn native_environment_is_inherited_but_not_dumped_into_logs() {
    let value = OsString::from_vec(b"synthetic-env-needle\xff".to_vec());
    let output = run(launcher()
        .arg(probe())
        .env("ORES_LAUNCHER_TEST_VALUE", &value)
        .env("OTEL_SERVICE_NAME", OsString::from_vec(vec![0xff])));
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains(&hex(value.as_bytes())));
    assert!(!String::from_utf8_lossy(&output.stderr).contains("synthetic-env-needle"));
    assert_eq!(records(&output)[0]["appName"], "ores-launcher");
}

#[test]
fn non_utf8_executable_paths_work_and_are_masked() {
    let path = probe().with_file_name(OsString::from_vec(b"native path\xff".to_vec()));
    fs::copy(probe(), &path).unwrap();
    let child = launcher().arg(&path).spawn().unwrap();
    let pid = child.id();
    let output = finish(child);
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains(&format!("pid={pid}\n")));
    assert_eq!(
        records(&output)[0]["fields"]["process.command_args"][0],
        "[NON_UTF8]"
    );
}

#[test]
fn basename_lookup_uses_the_inherited_path() {
    let output = run(launcher()
        .arg(probe().file_name().unwrap())
        .env("PATH", probe().parent().unwrap()));
    assert!(output.status.success());
    assert_eq!(records(&output).len(), 1);
}

#[test]
fn failed_exec_emits_attempt_then_failure_without_raw_credentials() {
    let output = run(launcher()
        .arg(probe().with_file_name("absent-executable"))
        .arg("--password=synthetic-error-needle"));
    assert_eq!(output.status.code(), Some(127));
    assert!(output.stdout.is_empty());
    let events = records(&output);
    assert_eq!(events.len(), 2);
    assert_eq!(events[0]["fields"]["event.name"], "process.exec.attempt");
    assert_eq!(events[1]["fields"]["event.name"], "process.exec.failed");
    assert_eq!(events[1]["fields"]["error.kind"], "NotFound");
    assert!(!String::from_utf8_lossy(&output.stderr).contains("synthetic-error-needle"));
}

#[test]
fn missing_shebang_interpreter_is_an_exec_failure() {
    let path = executable(
        "missing-interpreter",
        b"#!/ores-launcher-test-absent-interpreter\nexit 23\n",
    );
    let output = run(launcher().arg(path));
    assert_eq!(output.status.code(), Some(127));
    assert_eq!(records(&output).len(), 2);
}

#[test]
fn executable_text_without_a_shebang_never_gets_an_implicit_shell() {
    let path = executable("no-shebang", b"exit 23\n");
    let output = run(launcher().arg(path));
    assert_eq!(output.status.code(), Some(126));
    assert_eq!(records(&output).len(), 2);
}

#[test]
fn directories_and_symlink_loops_fail_without_starting_a_child() {
    let loop_path = probe().with_file_name("self-loop");
    symlink(&loop_path, &loop_path).unwrap();
    for path in [probe().parent().unwrap().to_path_buf(), loop_path] {
        let output = run(launcher().arg(path));
        assert_eq!(output.status.code(), Some(126));
        assert!(output.stdout.is_empty());
        assert_eq!(records(&output).len(), 2);
    }
}

#[test]
fn broken_stderr_does_not_prevent_successful_exec() {
    let output = run(launcher()
        .arg(probe())
        .env("ORES_LAUNCHER_TEST_EXIT", "42")
        .stderr(broken_stderr()));
    assert_eq!(output.status.code(), Some(42));
    assert!(String::from_utf8_lossy(&output.stdout).contains("ready\n"));
}

#[test]
fn broken_stderr_cannot_replace_the_failed_exec_exit_code_with_sigpipe() {
    let output = run(launcher()
        .arg(probe().with_file_name("absent-with-broken-stderr"))
        .stderr(broken_stderr()));
    assert_eq!(output.status.code(), Some(127));
}

#[test]
fn service_name_and_control_characters_cannot_forge_extra_log_records() {
    let service = format!("{}\nforged-service", "x".repeat(200));
    let output = run(launcher()
        .arg(probe())
        .arg("\n\r\t\u{1b}[31m\"forged event\"")
        .env("OTEL_SERVICE_NAME", service));
    assert!(output.status.success());
    let events = records(&output);
    assert_eq!(events.len(), 1);
    assert_eq!(events[0]["schema"], "next-loggers/v1");
    assert_eq!(
        events[0]["appName"],
        format!("{}...[truncated]", "x".repeat(128))
    );
}
