#![cfg(all(unix, feature = "launcher"))]

use serde_json::Value;
use std::ffi::OsString;
use std::fs;
use std::io::{BufRead, BufReader};
use std::os::unix::ffi::{OsStrExt, OsStringExt};
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::process::{Child, Command, Output, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

fn launcher() -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_ores-launcher"));
    command.env_remove("ORES_LAUNCHER_TEST_MODE");
    command.env_remove("ORES_LAUNCHER_TEST_EXIT");
    command.env("OTEL_SERVICE_NAME", "launcher-contract-test");
    command
}

fn probe() -> &'static PathBuf {
    static PROBE: OnceLock<PathBuf> = OnceLock::new();
    PROBE.get_or_init(|| {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory =
            std::env::temp_dir().join(format!("ores-launcher-{}-{nonce}", std::process::id()));
        fs::create_dir(&directory).unwrap();
        let source = directory.join("probe.rs");
        let binary = directory.join("probe");
        fs::write(&source, include_str!("fixtures/launcher_probe.rs")).unwrap();
        let status = Command::new(std::env::var_os("RUSTC").unwrap_or_else(|| "rustc".into()))
            .arg("--edition=2021")
            .arg(&source)
            .arg("-o")
            .arg(&binary)
            .status()
            .unwrap();
        assert!(status.success(), "compile the real Rust test executable");
        binary
    })
}

fn finish(mut child: Child) -> Output {
    let deadline = Instant::now() + Duration::from_secs(10);
    while child.try_wait().unwrap().is_none() {
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            panic!("launcher/probe exceeded its test deadline");
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    child.wait_with_output().unwrap()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn first_record(output: &Output) -> Value {
    serde_json::from_slice(output.stderr.split(|byte| *byte == b'\n').next().unwrap()).unwrap()
}

#[test]
fn preserves_pid_native_arguments_environment_cwd_and_stdout() {
    let arguments = vec![
        OsString::from("two words"),
        OsString::from(""),
        OsString::from("*"),
        OsString::from("$(not-a-shell)"),
        OsString::from("quote\"\nline"),
        OsString::from_vec(vec![0xff, b'x']),
    ];
    let child = launcher()
        .arg(probe())
        .args(&arguments)
        .env("ORES_LAUNCHER_TEST_VALUE", "inherited")
        .current_dir(probe().parent().unwrap())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let expected_pid = child.id();
    let output = finish(child);
    assert!(output.status.success());
    let stdout = String::from_utf8(output.stdout.clone()).unwrap();
    assert!(stdout
        .lines()
        .any(|line| line == format!("pid={expected_pid}")));
    assert!(stdout.contains("env=696e68657269746564\n"));
    assert!(stdout.contains(&format!(
        "cwd={}\n",
        hex(probe().parent().unwrap().as_os_str().as_bytes())
    )));
    for (index, argument) in arguments.iter().enumerate() {
        assert!(stdout.contains(&format!("arg{index}={}\n", hex(argument.as_bytes()))));
    }
    assert!(!stdout.contains("next-loggers"));
    let record = first_record(&output);
    assert_eq!(record["schema"], "next-loggers/v1");
    assert_eq!(record["appName"], "launcher-contract-test");
    assert_eq!(record["fields"]["process.pid"], expected_pid);
    assert_eq!(record["fields"]["event.name"], "process.exec.attempt");
    assert_eq!(record["fields"]["process.command_args"][6], "[NON_UTF8]");
    assert_eq!(
        output.stderr.iter().filter(|byte| **byte == b'\n').count(),
        1
    );
}

#[test]
fn redacts_only_the_log_not_the_childs_arguments() {
    let output = launcher()
        .arg(probe())
        .args([
            "--token",
            "fixture-never-a-real-secret",
            "--password=fixture-never-a-real-secret",
        ])
        .output()
        .unwrap();
    assert!(output.status.success());
    assert!(!String::from_utf8_lossy(&output.stderr).contains("fixture-never-a-real-secret"));
    assert!(String::from_utf8_lossy(&output.stdout).contains(&hex(b"fixture-never-a-real-secret")));
}

#[test]
fn missing_and_empty_commands_are_usage_errors() {
    for command in [None, Some("")] {
        let mut invocation = launcher();
        if let Some(command) = command {
            invocation.arg(command);
        }
        let output = invocation.output().unwrap();
        assert_eq!(output.status.code(), Some(64));
        assert_eq!(
            first_record(&output)["fields"]["event.name"],
            "process.exec.invalid_command"
        );
    }
}

#[test]
fn not_found_and_not_executable_are_distinct_failures() {
    let output = launcher()
        .arg(probe().with_file_name("absent"))
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(127));
    let file = probe().with_file_name("not-executable");
    fs::write(&file, "test-only fixture").unwrap();
    fs::set_permissions(&file, fs::Permissions::from_mode(0o600)).unwrap();
    let output = launcher().arg(file).output().unwrap();
    assert_eq!(output.status.code(), Some(126));
}

#[test]
fn returns_the_application_exit_status() {
    let output = launcher()
        .arg(probe())
        .env("ORES_LAUNCHER_TEST_EXIT", "42")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(42));
}

#[cfg(target_os = "linux")]
#[test]
fn failing_stderr_does_not_prevent_exec() {
    let output = launcher()
        .arg(probe())
        .stderr(Stdio::from(
            fs::OpenOptions::new()
                .write(true)
                .open("/dev/full")
                .unwrap(),
        ))
        .output()
        .unwrap();
    assert!(output.status.success());
    assert!(String::from_utf8_lossy(&output.stdout).contains("ready"));
}

#[test]
fn application_owns_sigterm_after_exec() {
    unsafe extern "C" {
        fn kill(pid: i32, signal: i32) -> i32;
    }
    let mut child = launcher()
        .arg(probe())
        .env("ORES_LAUNCHER_TEST_MODE", "wait")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    let mut stdout = BufReader::new(child.stdout.take().unwrap());
    let mut line = String::new();
    loop {
        line.clear();
        assert!(
            stdout.read_line(&mut line).unwrap() > 0,
            "probe must become ready"
        );
        if line.trim_end() == "ready" {
            break;
        }
    }
    assert_eq!(unsafe { kill(child.id() as i32, 15) }, 0);
    assert_eq!(finish(child).status.code(), Some(42));
}
