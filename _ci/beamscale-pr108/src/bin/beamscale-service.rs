use ring::digest::{Context as DigestContext, SHA256};
use std::{
    env,
    ffi::OsString,
    fs, io,
    io::Read,
    path::{Path, PathBuf},
    process::{Child, Command, ExitStatus, Output, Stdio},
    thread,
    time::{Duration, Instant},
};

#[cfg(target_os = "macos")]
const LABEL: &str = "com.beamscale.desktop-daemon";
#[cfg(target_os = "windows")]
const TASK_NAME: &str = "BeamScale Desktop Daemon";
#[cfg(any(target_os = "linux", test))]
const LINUX_TEMPLATE: &str =
    include_str!("../../packaging/linux/beamscale-desktop-daemon.service.in");
#[cfg(any(target_os = "macos", test))]
const MACOS_TEMPLATE: &str =
    include_str!("../../packaging/macos/com.beamscale.desktop-daemon.plist.in");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Action {
    Install,
    Uninstall,
    Status,
}

#[derive(Debug, Clone)]
struct Options {
    action: Action,
    binary: Option<PathBuf>,
    data_root: Option<PathBuf>,
    supervisor_root: Option<PathBuf>,
    json: bool,
    start_now: bool,
}

#[derive(Debug, Clone, serde::Serialize)]
struct ServiceStatus {
    installed: bool,
    running: Option<bool>,
    manager: &'static str,
    definition_path: String,
}

const MAX_SERVICE_SNAPSHOT_BYTES: u64 = 1024 * 1024;
#[cfg(any(target_os = "linux", target_os = "macos"))]
const MAX_SERVICE_STATUS_BYTES: usize = 16 * 1024;
const SERVICE_COMMAND_TIMEOUT: Duration = Duration::from_secs(30);
const SERVICE_STATUS_COMMAND_TIMEOUT: Duration = Duration::from_secs(7);
const SERVICE_TREE_CLEANUP_TIMEOUT: Duration = Duration::from_secs(2);
const MAX_PINNED_TOOL_BYTES: u64 = 512 * 1024 * 1024;
const MAX_SUPERVISOR_EBIN_FILES: usize = 4096;
const MAX_SUPERVISOR_EBIN_FILE_BYTES: u64 = 32 * 1024 * 1024;
const MAX_SUPERVISOR_EBIN_TOTAL_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Debug)]
struct FileSnapshot {
    path: PathBuf,
    bytes: Option<Vec<u8>>,
}

#[derive(Debug)]
struct InstallSnapshot {
    #[cfg(target_os = "windows")]
    root: PathBuf,
    tools: FileSnapshot,
    #[cfg(any(target_os = "linux", target_os = "macos"))]
    definition: FileSnapshot,
    #[cfg(target_os = "linux")]
    linux_enabled: bool,
    #[cfg(target_os = "linux")]
    linux_running: bool,
    #[cfg(target_os = "macos")]
    macos_loaded: bool,
    #[cfg(target_os = "macos")]
    macos_running: bool,
    #[cfg(target_os = "windows")]
    windows_task_xml: Option<Vec<u8>>,
}

fn main() {
    if let Err(error) = run(env::args_os().skip(1).collect()) {
        eprintln!("beamscale-service: {error}");
        std::process::exit(2);
    }
}

fn run(args: Vec<OsString>) -> Result<(), String> {
    let options = parse_args(args)?;
    match options.action {
        Action::Install => install(options),
        Action::Uninstall => uninstall(options),
        Action::Status => status(options.json),
    }
}

fn parse_args(args: Vec<OsString>) -> Result<Options, String> {
    let mut iter = args.into_iter();
    let action = match iter
        .next()
        .and_then(|value| value.into_string().ok())
        .as_deref()
    {
        Some("install") => Action::Install,
        Some("uninstall") => Action::Uninstall,
        Some("status") => Action::Status,
        _ => return Err(usage()),
    };

    let mut binary = None;
    let mut data_root = None;
    let mut supervisor_root = None;
    let mut json = false;
    let mut start_now = true;
    while let Some(flag) = iter.next() {
        let flag = flag
            .into_string()
            .map_err(|_| "arguments must be valid UTF-8".to_owned())?;
        match flag.as_str() {
            "--json" => {
                if json {
                    return Err("--json may be specified only once".to_owned());
                }
                json = true;
            }
            "--no-start" => {
                if action != Action::Install {
                    return Err("--no-start is an install-only option".to_owned());
                }
                if !start_now {
                    return Err("--no-start may be specified only once".to_owned());
                }
                start_now = false;
            }
            "--binary" | "--data-root" | "--supervisor-root" => {
                let value = iter.next().ok_or_else(usage)?;
                if flag == "--binary" {
                    if binary.replace(PathBuf::from(value)).is_some() {
                        return Err("--binary may be specified only once".to_owned());
                    }
                } else if flag == "--data-root" {
                    if data_root.replace(PathBuf::from(value)).is_some() {
                        return Err("--data-root may be specified only once".to_owned());
                    }
                } else if supervisor_root.replace(PathBuf::from(value)).is_some() {
                    return Err("--supervisor-root may be specified only once".to_owned());
                }
            }
            _ => return Err(format!("unknown argument {flag:?}\n{}", usage())),
        }
    }

    if action != Action::Install
        && (binary.is_some() || data_root.is_some() || supervisor_root.is_some())
    {
        return Err(
            "--binary, --data-root and --supervisor-root are install-only options".to_owned(),
        );
    }
    if action != Action::Status && json {
        return Err("--json is a status-only option".to_owned());
    }
    Ok(Options {
        action,
        binary,
        data_root,
        supervisor_root,
        json,
        start_now,
    })
}

fn usage() -> String {
    "usage: beamscale-service install [--binary PATH] [--data-root PATH] [--supervisor-root PATH] [--no-start] | uninstall | status [--json]"
        .to_owned()
}

fn install(options: Options) -> Result<(), String> {
    let binary = resolve_binary(options.binary)?;
    let data_root = resolve_data_root(options.data_root)?;
    let supervisor_root = resolve_supervisor_root(options.supervisor_root)?;
    secure_state_root(&data_root)?;

    let snapshot = snapshot_install_state(&data_root)?;
    if let Err(error) = persist_service_tools(&data_root, &binary, supervisor_root.as_deref()) {
        let rollback = restore_file_snapshot(&snapshot.tools);
        return Err(combine_install_rollback_error(
            format!("persist service tools: {error}"),
            rollback,
        ));
    }

    let install_result = install_platform(&binary, &data_root, options.start_now);
    if let Err(error) = install_result {
        return Err(combine_install_rollback_error(
            format!("service installation failed: {error}"),
            rollback_install_state(&snapshot),
        ));
    }

    Ok(())
}

fn install_platform(binary: &Path, root: &Path, start_now: bool) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        return install_macos(binary, root, start_now);
    }
    #[cfg(target_os = "linux")]
    {
        return install_linux(binary, root, start_now);
    }
    #[cfg(target_os = "windows")]
    {
        return install_windows(binary, root, start_now);
    }
    #[allow(unreachable_code)]
    Err("service installation is unsupported on this operating system".to_owned())
}

fn combine_install_rollback_error(install_error: String, rollback: Result<(), String>) -> String {
    match rollback {
        Ok(()) => install_error,
        Err(rollback_error) => {
            format!("{install_error}; rollback also failed: {rollback_error}")
        }
    }
}

fn uninstall(_options: Options) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        return uninstall_macos();
    }
    #[cfg(target_os = "linux")]
    {
        return uninstall_linux();
    }
    #[cfg(target_os = "windows")]
    {
        return uninstall_windows();
    }
    #[allow(unreachable_code)]
    Err("service removal is unsupported on this operating system".to_owned())
}

fn status(json: bool) -> Result<(), String> {
    if json {
        let status = service_status()?;
        let text = serde_json::to_string(&status)
            .map_err(|error| format!("encode service status: {error}"))?;
        println!("{text}");
        return Ok(());
    }
    status_human()
}

fn status_human() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let domain = format!("gui/{}", current_uid()?);
        let target = format!("{domain}/{LABEL}");
        return checked_with_timeout(
            launchctl_command()?.args(["print", &target]),
            SERVICE_STATUS_COMMAND_TIMEOUT,
        )
        .map(|_| ());
    }
    #[cfg(target_os = "linux")]
    {
        return checked_with_timeout(
            systemctl_command()?.args([
                "--user",
                "status",
                "beamscale-desktop-daemon.service",
                "--no-pager",
            ]),
            SERVICE_STATUS_COMMAND_TIMEOUT,
        )
        .map(|_| ());
    }
    #[cfg(target_os = "windows")]
    {
        return checked_with_timeout(
            schtasks_command()?.args(["/Query", "/TN", TASK_NAME]),
            SERVICE_STATUS_COMMAND_TIMEOUT,
        )
        .map(|_| ());
    }
    #[allow(unreachable_code)]
    Err("service status is unsupported on this operating system".to_owned())
}

#[cfg(any(target_os = "linux", target_os = "macos", test))]
fn regular_definition_file(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
                return Err(format!(
                    "service definition path exists but is not a regular non-symlink file: {}",
                    path.display()
                ));
            }
            Ok(true)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(format!(
            "inspect service definition {}: {error}",
            path.display()
        )),
    }
}

#[cfg(target_os = "linux")]
fn service_status() -> Result<ServiceStatus, String> {
    let home = env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?;
    let config_home = env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(home).join(".config"));
    let definition = config_home
        .join("systemd")
        .join("user")
        .join("beamscale-desktop-daemon.service");

    let definition_exists = regular_definition_file(&definition)?;
    if !definition_exists {
        return Ok(ServiceStatus {
            installed: false,
            running: None,
            manager: "systemd-user",
            definition_path: definition.to_string_lossy().to_string(),
        });
    }

    let output = run_output_bounded(
        systemctl_command()?.args([
            "--user",
            "show",
            "beamscale-desktop-daemon.service",
            "--property=LoadState",
            "--property=ActiveState",
        ]),
        SERVICE_STATUS_COMMAND_TIMEOUT,
        MAX_SERVICE_STATUS_BYTES,
    )
    .map_err(|error| format!("query systemd service state: {error}"))?;
    if output.stdout.len() > 16 * 1024 || output.stderr.len() > 16 * 1024 {
        return Err("systemctl status output exceeded 16384 bytes".to_owned());
    }
    if !output.status.success() {
        return Err(format!(
            "systemctl show failed with {}: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }

    let stdout = String::from_utf8(output.stdout)
        .map_err(|_| "systemctl show returned non-UTF8 output".to_owned())?;
    let (load_state, active_state) = parse_systemd_show(&stdout)?;
    let installed = load_state != "not-found";
    let running = installed.then_some(active_state == "active");
    Ok(ServiceStatus {
        installed,
        running,
        manager: "systemd-user",
        definition_path: definition.to_string_lossy().to_string(),
    })
}

#[cfg(any(target_os = "linux", test))]
fn parse_systemd_show(stdout: &str) -> Result<(String, String), String> {
    let mut load_state = None;
    let mut active_state = None;
    for line in stdout.lines() {
        if let Some(value) = line.strip_prefix("LoadState=") {
            if load_state.replace(value.to_owned()).is_some() {
                return Err("systemctl show returned duplicate LoadState".to_owned());
            }
        } else if let Some(value) = line.strip_prefix("ActiveState=") {
            if active_state.replace(value.to_owned()).is_some() {
                return Err("systemctl show returned duplicate ActiveState".to_owned());
            }
        } else if !line.trim().is_empty() {
            return Err(format!("systemctl show returned unexpected field: {line}"));
        }
    }
    Ok((
        load_state.ok_or_else(|| "systemctl show omitted LoadState".to_owned())?,
        active_state.ok_or_else(|| "systemctl show omitted ActiveState".to_owned())?,
    ))
}

#[cfg(target_os = "macos")]
fn service_status() -> Result<ServiceStatus, String> {
    let home = env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?;
    let definition = PathBuf::from(home)
        .join("Library")
        .join("LaunchAgents")
        .join(format!("{LABEL}.plist"));
    let installed = regular_definition_file(&definition)?;
    let running = if installed {
        let domain = format!("gui/{}", current_uid()?);
        let target = format!("{domain}/{LABEL}");
        Some(launchd_running_state(&target)?.unwrap_or(false))
    } else {
        None
    };
    Ok(ServiceStatus {
        installed,
        running,
        manager: "launchd-user",
        definition_path: definition.to_string_lossy().to_string(),
    })
}

#[cfg(any(target_os = "macos", test))]
fn parse_launchd_running(stdout: &str) -> Result<bool, String> {
    let mut state = None;
    for line in stdout.lines() {
        let line = line.trim();
        if let Some(value) = line.strip_prefix("state = ")
            && state.replace(value.to_owned()).is_some()
        {
            return Err("launchctl print returned duplicate state".to_owned());
        }
    }
    Ok(state.ok_or_else(|| "launchctl print omitted state".to_owned())? == "running")
}

#[cfg(target_os = "macos")]
fn launchd_running_state(target: &str) -> Result<Option<bool>, String> {
    let output = run_output_bounded(
        launchctl_command()?.args(["print", target]),
        SERVICE_STATUS_COMMAND_TIMEOUT,
        MAX_SERVICE_STATUS_BYTES,
    )
    .map_err(|error| format!("query launchd service state: {error}"))?;
    if output.stdout.len() > MAX_SERVICE_STATUS_BYTES
        || output.stderr.len() > MAX_SERVICE_STATUS_BYTES
    {
        return Err(format!(
            "launchctl status output exceeded {MAX_SERVICE_STATUS_BYTES} bytes"
        ));
    }
    if !output.status.success() {
        return Ok(None);
    }
    let stdout = String::from_utf8(output.stdout)
        .map_err(|_| "launchctl print returned non-UTF8 output".to_owned())?;
    Ok(Some(parse_launchd_running(&stdout)?))
}

#[cfg(target_os = "windows")]
fn windows_task_definition_path() -> PathBuf {
    PathBuf::from(r"C:\Windows\System32\Tasks").join(TASK_NAME)
}

#[cfg(target_os = "windows")]
fn windows_task_installed() -> Result<bool, String> {
    let path = windows_task_definition_path();
    match fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
                return Err(format!(
                    "Scheduled Task definition exists but is not a regular non-symlink file: {}",
                    path.display()
                ));
            }
            Ok(true)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(format!(
            "inspect Scheduled Task definition {}: {error}",
            path.display()
        )),
    }
}

#[cfg(target_os = "windows")]
fn service_status() -> Result<ServiceStatus, String> {
    let installed = windows_task_installed()?;
    if installed {
        let result = run_status_bounded(
            schtasks_command()?
                .args(["/Query", "/TN", TASK_NAME])
                .stdout(Stdio::null())
                .stderr(Stdio::null()),
            SERVICE_STATUS_COMMAND_TIMEOUT,
        )
        .map_err(|error| format!("query Scheduled Task state: {error}"))?;
        if !result.success() {
            return Err(format!(
                "Scheduled Task definition exists but schtasks query failed with {result}"
            ));
        }
    }

    Ok(ServiceStatus {
        installed,
        running: None,
        manager: "scheduled-task",
        definition_path: TASK_NAME.to_owned(),
    })
}

fn snapshot_regular_file(path: PathBuf, label: &str) -> Result<FileSnapshot, String> {
    match fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
                return Err(format!(
                    "{label} must be a regular non-symlink file: {}",
                    path.display()
                ));
            }
            if metadata.len() > MAX_SERVICE_SNAPSHOT_BYTES {
                return Err(format!(
                    "{label} exceeds {} bytes: {}",
                    MAX_SERVICE_SNAPSHOT_BYTES,
                    path.display()
                ));
            }
            let bytes = fs::read(&path)
                .map_err(|error| format!("snapshot {label} {}: {error}", path.display()))?;
            Ok(FileSnapshot {
                path,
                bytes: Some(bytes),
            })
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            Ok(FileSnapshot { path, bytes: None })
        }
        Err(error) => Err(format!("inspect {label} {}: {error}", path.display())),
    }
}

fn restore_file_snapshot(snapshot: &FileSnapshot) -> Result<(), String> {
    match &snapshot.bytes {
        Some(bytes) => write_service_state_file(&snapshot.path, bytes),
        None => match fs::symlink_metadata(&snapshot.path) {
            Ok(metadata) => {
                if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
                    return Err(format!(
                        "refusing to remove unexpected rollback path: {}",
                        snapshot.path.display()
                    ));
                }
                fs::remove_file(&snapshot.path)
                    .map_err(|error| format!("remove {}: {error}", snapshot.path.display()))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(format!(
                "inspect rollback path {}: {error}",
                snapshot.path.display()
            )),
        },
    }
}

#[cfg(target_os = "linux")]
fn linux_definition_path() -> Result<PathBuf, String> {
    let home = env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?;
    let config_home = env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(home).join(".config"));
    Ok(config_home.join("systemd/user/beamscale-desktop-daemon.service"))
}

#[cfg(target_os = "macos")]
fn macos_definition_path() -> Result<PathBuf, String> {
    let home = PathBuf::from(env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?);
    Ok(home
        .join("Library")
        .join("LaunchAgents")
        .join(format!("{LABEL}.plist")))
}

#[cfg(any(target_os = "linux", test))]
fn parse_systemd_state_output(stdout: &[u8], stderr: &[u8], label: &str) -> Result<String, String> {
    let stdout =
        std::str::from_utf8(stdout).map_err(|_| format!("systemd {label} state was not UTF-8"))?;
    let stderr =
        std::str::from_utf8(stderr).map_err(|_| format!("systemd {label} stderr was not UTF-8"))?;
    let mut lines = stdout.lines().filter(|line| !line.trim().is_empty());
    let state = lines
        .next()
        .ok_or_else(|| {
            format!(
                "systemd {label} state query produced no state: {}",
                stderr.trim()
            )
        })?
        .trim()
        .to_owned();
    if lines.next().is_some() {
        return Err(format!(
            "systemd {label} state query returned multiple state lines"
        ));
    }
    Ok(state)
}

#[cfg(target_os = "linux")]
fn query_systemd_state(args: &[&str], label: &str) -> Result<String, String> {
    let output = run_output_bounded(
        systemctl_command()?.args(args),
        SERVICE_COMMAND_TIMEOUT,
        MAX_SERVICE_STATUS_BYTES,
    )
    .map_err(|error| format!("query prior systemd {label} state: {error}"))?;

    if !output.status.success() && output.stdout.is_empty() {
        return Err(format!(
            "systemd {label} state query failed with {}: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }

    parse_systemd_state_output(&output.stdout, &output.stderr, label)
}

fn snapshot_install_state(root: &Path) -> Result<InstallSnapshot, String> {
    let tools = snapshot_regular_file(root.join("service-tools.json"), "service tool state")?;

    #[cfg(target_os = "linux")]
    {
        let definition =
            snapshot_regular_file(linux_definition_path()?, "systemd user service definition")?;
        let linux_enabled = if definition.bytes.is_some() {
            match query_systemd_state(
                &["--user", "is-enabled", "beamscale-desktop-daemon.service"],
                "enable",
            )?
            .as_str()
            {
                "enabled" => true,
                "disabled" => false,
                other => {
                    return Err(format!(
                        "prior systemd enable state is not safely restorable: {other}"
                    ));
                }
            }
        } else {
            false
        };
        let linux_running = if definition.bytes.is_some() {
            match query_systemd_state(
                &["--user", "is-active", "beamscale-desktop-daemon.service"],
                "active",
            )?
            .as_str()
            {
                "active" => true,
                "inactive" => false,
                other => {
                    return Err(format!(
                        "prior systemd active state is not safely restorable: {other}"
                    ));
                }
            }
        } else {
            false
        };
        return Ok(InstallSnapshot {
            tools,
            definition,
            linux_enabled,
            linux_running,
        });
    }

    #[cfg(target_os = "macos")]
    {
        let definition =
            snapshot_regular_file(macos_definition_path()?, "launchd user service definition")?;
        let (macos_loaded, macos_running) = if definition.bytes.is_some() {
            let domain = format!("gui/{}", current_uid()?);
            let target = format!("{domain}/{LABEL}");
            match launchd_running_state(&target)? {
                Some(running) => (true, running),
                None => (false, false),
            }
        } else {
            (false, false)
        };
        return Ok(InstallSnapshot {
            tools,
            definition,
            macos_loaded,
            macos_running,
        });
    }

    #[cfg(target_os = "windows")]
    {
        let windows_task_xml = if windows_task_installed()? {
            let output = run_output_bounded(
                schtasks_command()?.args(["/Query", "/TN", TASK_NAME, "/XML", "ONE"]),
                SERVICE_COMMAND_TIMEOUT,
                MAX_SERVICE_SNAPSHOT_BYTES as usize,
            )
            .map_err(|error| format!("snapshot Scheduled Task: {error}"))?;
            if !output.status.success() {
                return Err(format!(
                    "Scheduled Task definition exists but XML snapshot failed with {}: {}",
                    output.status,
                    String::from_utf8_lossy(&output.stderr).trim()
                ));
            }
            if output.stdout.len() as u64 > MAX_SERVICE_SNAPSHOT_BYTES {
                return Err("Scheduled Task XML exceeds 1048576 bytes".to_owned());
            }
            if output.stdout.is_empty() {
                return Err("Scheduled Task XML snapshot was empty".to_owned());
            }
            Some(output.stdout)
        } else {
            None
        };
        return Ok(InstallSnapshot {
            root: root.to_path_buf(),
            tools,
            windows_task_xml,
        });
    }

    #[allow(unreachable_code)]
    Err("service installation snapshots are unsupported on this operating system".to_owned())
}

#[cfg(any(target_os = "linux", test))]
fn linux_pre_restore_actions(prior_definition: bool) -> Vec<Vec<&'static str>> {
    if prior_definition {
        return Vec::new();
    }
    vec![
        vec!["--user", "stop", "beamscale-desktop-daemon.service"],
        vec!["--user", "disable", "beamscale-desktop-daemon.service"],
    ]
}

#[cfg(any(target_os = "linux", test))]
fn linux_post_restore_actions(
    prior_definition: bool,
    prior_enabled: bool,
    prior_running: bool,
) -> Vec<Vec<&'static str>> {
    if !prior_definition {
        return vec![vec![
            "--user",
            "reset-failed",
            "beamscale-desktop-daemon.service",
        ]];
    }

    let enable = if prior_enabled { "enable" } else { "disable" };
    let active = if prior_running { "start" } else { "stop" };
    vec![
        vec!["--user", enable, "beamscale-desktop-daemon.service"],
        vec!["--user", active, "beamscale-desktop-daemon.service"],
    ]
}

fn rollback_install_state(snapshot: &InstallSnapshot) -> Result<(), String> {
    let mut errors = Vec::new();

    if let Err(error) = restore_file_snapshot(&snapshot.tools) {
        errors.push(format!("restore service tools: {error}"));
    }

    #[cfg(target_os = "linux")]
    {
        let prior_definition = snapshot.definition.bytes.is_some();

        // A first-time install may have partially started/enabled the new unit
        // before returning failure. Stop and disable it while the just-written
        // unit definition still exists; removing/reloading first can make
        // cleanup dependent on systemd retaining the transient loaded unit.
        for action in linux_pre_restore_actions(prior_definition) {
            let status =
                run_status_bounded(systemctl_command()?.args(&action), SERVICE_COMMAND_TIMEOUT);
            match status {
                Ok(status) if status.success() => {}
                Ok(status) => errors.push(format!(
                    "unwind partial systemd state {:?} exited with {status}",
                    action
                )),
                Err(error) => errors.push(format!(
                    "unwind partial systemd state {:?}: {error}",
                    action
                )),
            }
        }

        if let Err(error) = restore_file_snapshot(&snapshot.definition) {
            errors.push(format!("restore systemd definition: {error}"));
        }
        if let Err(error) = checked(systemctl_command()?.args(["--user", "daemon-reload"])) {
            errors.push(format!("reload restored systemd state: {error}"));
        }

        for action in linux_post_restore_actions(
            prior_definition,
            snapshot.linux_enabled,
            snapshot.linux_running,
        ) {
            let status =
                run_status_bounded(systemctl_command()?.args(&action), SERVICE_COMMAND_TIMEOUT);
            match status {
                Ok(status) if status.success() => {}
                Ok(status) => errors.push(format!(
                    "restore systemd state {:?} exited with {status}",
                    action
                )),
                Err(error) => errors.push(format!("restore systemd state {:?}: {error}", action)),
            }
        }
    }

    #[cfg(target_os = "macos")]
    {
        let domain = match current_uid() {
            Ok(uid) => format!("gui/{uid}"),
            Err(error) => {
                errors.push(format!("resolve launchd domain during rollback: {error}"));
                String::new()
            }
        };
        if !domain.is_empty() {
            let target = format!("{domain}/{LABEL}");
            let _ = run_status_bounded(
                launchctl_command()?.args(["bootout", &target]),
                SERVICE_COMMAND_TIMEOUT,
            );
        }
        if let Err(error) = restore_file_snapshot(&snapshot.definition) {
            errors.push(format!("restore launchd definition: {error}"));
        }
        if snapshot.macos_loaded && snapshot.definition.bytes.is_some() && !domain.is_empty() {
            let target = format!("{domain}/{LABEL}");
            if let Err(error) = checked(launchctl_command()?.args([
                "bootstrap",
                &domain,
                snapshot.definition.path.to_string_lossy().as_ref(),
            ])) {
                errors.push(format!("restore launchd registration: {error}"));
            } else {
                if let Err(error) = checked(launchctl_command()?.args(["enable", &target])) {
                    errors.push(format!("restore launchd enable state: {error}"));
                }
                if snapshot.macos_running {
                    if let Err(error) =
                        checked(launchctl_command()?.args(["kickstart", "-k", &target]))
                    {
                        errors.push(format!("restore launchd running state: {error}"));
                    }
                }
            }
        }
    }

    #[cfg(target_os = "windows")]
    {
        match &snapshot.windows_task_xml {
            Some(xml) => {
                let rollback_xml = snapshot.root.join(format!(
                    ".service-task-rollback.{}.{}.xml",
                    std::process::id(),
                    rand::random::<u64>()
                ));
                if let Err(error) = write_service_state_file(&rollback_xml, xml) {
                    errors.push(format!("write Scheduled Task rollback XML: {error}"));
                } else {
                    let create = run_status_bounded(
                        schtasks_command()?.args([
                            "/Create",
                            "/F",
                            "/TN",
                            TASK_NAME,
                            "/XML",
                            rollback_xml.to_string_lossy().as_ref(),
                        ]),
                        SERVICE_COMMAND_TIMEOUT,
                    );
                    match create {
                        Ok(status) if status.success() => {}
                        Ok(status) => {
                            errors.push(format!("restore Scheduled Task exited with {status}"))
                        }
                        Err(error) => {
                            errors.push(format!("restore Scheduled Task: {error}"));
                        }
                    }
                    let _ = fs::remove_file(&rollback_xml);
                }
            }
            None => {
                let _ = run_status_bounded(
                    schtasks_command()?.args(["/Delete", "/F", "/TN", TASK_NAME]),
                    SERVICE_COMMAND_TIMEOUT,
                );
            }
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors.join("; "))
    }
}

fn trusted_system_tool(candidates: &[&str], label: &str) -> Result<PathBuf, String> {
    for candidate in candidates {
        let path = PathBuf::from(candidate);
        match fs::metadata(&path) {
            Ok(metadata) if metadata.is_file() => {
                return pin_executable_path(&path, label);
            }
            Ok(_) => continue,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(format!(
                    "inspect trusted {label} candidate {}: {error}",
                    path.display()
                ));
            }
        }
    }
    Err(format!(
        "trusted {label} was not found in the fixed operating-system locations: {}",
        candidates.join(", ")
    ))
}

#[cfg(target_os = "linux")]
fn systemctl_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[
            "/usr/bin/systemctl",
            "/bin/systemctl",
            "/run/current-system/sw/bin/systemctl",
        ],
        "systemctl",
    )?))
}

#[cfg(target_os = "linux")]
fn systemd_analyze_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[
            "/usr/bin/systemd-analyze",
            "/bin/systemd-analyze",
            "/run/current-system/sw/bin/systemd-analyze",
        ],
        "systemd-analyze",
    )?))
}

#[cfg(target_os = "macos")]
fn launchctl_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &["/bin/launchctl"],
        "launchctl",
    )?))
}

#[cfg(target_os = "macos")]
fn plutil_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &["/usr/bin/plutil"],
        "plutil",
    )?))
}

#[cfg(target_os = "macos")]
fn id_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(&["/usr/bin/id"], "id")?))
}

#[cfg(target_os = "windows")]
fn schtasks_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[r"C:\Windows\System32\schtasks.exe"],
        "schtasks.exe",
    )?))
}

#[cfg(target_os = "windows")]
fn icacls_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[r"C:\Windows\System32\icacls.exe"],
        "icacls.exe",
    )?))
}

#[cfg(target_os = "windows")]
fn whoami_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[r"C:\Windows\System32\whoami.exe"],
        "whoami.exe",
    )?))
}

#[cfg(target_os = "windows")]
fn taskkill_command() -> Result<Command, String> {
    Ok(Command::new(trusted_system_tool(
        &[r"C:\Windows\System32\taskkill.exe"],
        "taskkill.exe",
    )?))
}

fn resolve_binary(requested: Option<PathBuf>) -> Result<PathBuf, String> {
    let path = match requested {
        Some(path) => absolute_path(path)?,
        None => find_on_path(if cfg!(windows) {
            "beamscale-desktop-daemon.exe"
        } else {
            "beamscale-desktop-daemon"
        })?,
    };

    pin_executable_path(&path, "daemon binary")
}

fn validate_executable_path(path: &Path, label: &str) -> Result<(), String> {
    reject_control_text(path)?;
    let metadata = fs::metadata(path)
        .map_err(|error| format!("inspect {label} {}: {error}", path.display()))?;
    if !metadata.is_file() {
        return Err(format!(
            "{label} must resolve to a regular file: {}",
            path.display()
        ));
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return Err(format!("{label} is not executable: {}", path.display()));
        }
    }

    Ok(())
}

fn pin_executable_path(path: &Path, label: &str) -> Result<PathBuf, String> {
    validate_executable_path(path, label)?;
    let canonical = fs::canonicalize(path)
        .map_err(|error| format!("canonicalize {label} {}: {error}", path.display()))?;
    validate_executable_path(&canonical, label)?;

    let metadata = fs::symlink_metadata(&canonical)
        .map_err(|error| format!("inspect pinned {label} {}: {error}", canonical.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        return Err(format!(
            "pinned {label} must be a regular non-symlink file: {}",
            canonical.display()
        ));
    }

    Ok(canonical)
}

fn absolute_path(path: PathBuf) -> Result<PathBuf, String> {
    if path.is_absolute() {
        return Ok(path);
    }
    env::current_dir()
        .map(|cwd| cwd.join(path))
        .map_err(|error| format!("resolve current directory: {error}"))
}

fn find_on_path(name: &str) -> Result<PathBuf, String> {
    find_optional_on_path(name)?.ok_or_else(|| format!("pass --binary or put {name} on PATH"))
}

fn find_optional_on_path(name: &str) -> Result<Option<PathBuf>, String> {
    let Some(path) = env::var_os("PATH") else {
        return Ok(None);
    };
    for directory in env::split_paths(&path) {
        let candidate = absolute_path(directory.join(name))?;
        match fs::metadata(&candidate) {
            Ok(metadata) if metadata.is_file() => {
                return pin_executable_path(&candidate, name).map(Some);
            }
            Ok(_) => continue,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(format!(
                    "inspect PATH candidate {}: {error}",
                    candidate.display()
                ));
            }
        }
    }
    Ok(None)
}

fn service_tool_name(name: &str) -> &str {
    if cfg!(windows) {
        match name {
            "bmscl" => "bmscl.exe",
            "cloudflared" => "cloudflared.exe",
            "zed" => "zed.exe",
            other => other,
        }
    } else {
        name
    }
}

fn persist_service_tools(
    root: &Path,
    daemon_binary: &Path,
    supervisor_root: Option<&Path>,
) -> Result<(), String> {
    let bmscl = find_on_path(service_tool_name("bmscl")).map_err(|error| {
        format!("cannot install persistent BeamScale service without bmscl: {error}")
    })?;
    let cloudflared = find_optional_on_path(service_tool_name("cloudflared"))?;
    let zed = find_optional_on_path(service_tool_name("zed"))?;

    let daemon_sha256 = executable_sha256(daemon_binary, "beamscale-desktop-daemon")?;
    let bmscl_sha256 = executable_sha256(&bmscl, "bmscl")?;
    let cloudflared_sha256 = cloudflared
        .as_deref()
        .map(|path| executable_sha256(path, "cloudflared"))
        .transpose()?;
    let zed_sha256 = zed
        .as_deref()
        .map(|path| executable_sha256(path, "zed"))
        .transpose()?;
    let supervisor_ebin_sha256 = supervisor_root.map(supervisor_ebin_sha256).transpose()?;

    let document = serde_json::json!({
        "daemon_binary": daemon_binary,
        "daemon_sha256": daemon_sha256,
        "bmscl_binary": bmscl,
        "bmscl_sha256": bmscl_sha256,
        "cloudflared_binary": cloudflared,
        "cloudflared_sha256": cloudflared_sha256,
        "zed_binary": zed,
        "zed_sha256": zed_sha256,
        "supervisor_root": supervisor_root,
        "supervisor_ebin_sha256": supervisor_ebin_sha256,
    });
    let bytes = serde_json::to_vec_pretty(&document)
        .map_err(|error| format!("encode service tool paths: {error}"))?;
    write_service_state_file(&root.join("service-tools.json"), &bytes)
}

fn executable_sha256(path: &Path, label: &str) -> Result<String, String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("inspect pinned {label} {}: {error}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        return Err(format!(
            "pinned {label} must be a regular non-symlink file: {}",
            path.display()
        ));
    }
    if metadata.len() > MAX_PINNED_TOOL_BYTES {
        return Err(format!(
            "pinned {label} exceeds {MAX_PINNED_TOOL_BYTES} bytes: {}",
            path.display()
        ));
    }

    let mut file = fs::File::open(path)
        .map_err(|error| format!("open pinned {label} {}: {error}", path.display()))?;
    let before = file
        .metadata()
        .map_err(|error| format!("inspect opened {label} {}: {error}", path.display()))?;
    let mut digest = DigestContext::new(&SHA256);
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|error| format!("hash pinned {label} {}: {error}", path.display()))?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(read as u64)
            .ok_or_else(|| format!("{label} byte count overflow"))?;
        if total > MAX_PINNED_TOOL_BYTES {
            return Err(format!(
                "pinned {label} exceeded {MAX_PINNED_TOOL_BYTES} bytes while hashing"
            ));
        }
        digest.update(&buffer[..read]);
    }

    let after = file
        .metadata()
        .map_err(|error| format!("re-inspect pinned {label} {}: {error}", path.display()))?;
    if before.len() != total || after.len() != total {
        return Err(format!(
            "pinned {label} changed size while hashing: {}",
            path.display()
        ));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev() || before.ino() != after.ino() {
            return Err(format!(
                "pinned {label} identity changed while hashing: {}",
                path.display()
            ));
        }
    }
    let before_modified = before.modified().ok();
    let after_modified = after.modified().ok();
    if before_modified.is_some() && after_modified.is_some() && before_modified != after_modified {
        return Err(format!(
            "pinned {label} modification time changed while hashing: {}",
            path.display()
        ));
    }

    Ok(digest
        .finish()
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn write_service_state_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    #[cfg(unix)]
    {
        return write_private(path, bytes);
    }
    #[cfg(windows)]
    {
        match fs::symlink_metadata(path) {
            Ok(metadata)
                if metadata.file_type().is_symlink() || !metadata.file_type().is_file() =>
            {
                return Err(format!(
                    "service state file must be a regular non-symlink file: {}",
                    path.display()
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(error) => return Err(format!("inspect {}: {error}", path.display())),
        }
        fs::write(path, bytes).map_err(|error| format!("write {}: {error}", path.display()))?;
        return Ok(());
    }
    #[allow(unreachable_code)]
    Err("service tool state is unsupported on this operating system".to_owned())
}

fn resolve_supervisor_root(requested: Option<PathBuf>) -> Result<Option<PathBuf>, String> {
    let requested = requested.or_else(|| env::var_os("BMSCL_SUPERVISOR_ROOT").map(PathBuf::from));
    let Some(root) = requested else {
        return Ok(None);
    };
    reject_control_text(&root)?;

    let metadata = fs::symlink_metadata(&root)
        .map_err(|error| format!("inspect supervisor root {}: {error}", root.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
        return Err(format!(
            "supervisor root must be a real directory, not a symlink: {}",
            root.display()
        ));
    }

    let root = fs::canonicalize(&root)
        .map_err(|error| format!("canonicalize supervisor root {}: {error}", root.display()))?;
    let ebin = root.join("_build/default/lib/bmscl_supervisor/ebin");
    let ebin_metadata = fs::symlink_metadata(&ebin)
        .map_err(|error| format!("inspect supervisor ebin {}: {error}", ebin.display()))?;
    if ebin_metadata.file_type().is_symlink() || !ebin_metadata.file_type().is_dir() {
        return Err(format!(
            "supervisor ebin must be a real directory, not a symlink: {}",
            ebin.display()
        ));
    }

    for module in [
        "bmscl_deployment_manager.beam",
        "bmscl_route_table.beam",
        "bmscl_runtime.beam",
    ] {
        let path = ebin.join(module);
        let metadata = fs::symlink_metadata(&path)
            .map_err(|error| format!("inspect supervisor module {}: {error}", path.display()))?;
        if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
            return Err(format!(
                "supervisor module must be a regular non-symlink file: {}",
                path.display()
            ));
        }
    }

    Ok(Some(root))
}

fn valid_supervisor_digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn supervisor_ebin_sha256(root: &Path) -> Result<String, String> {
    let ebin = root.join("_build/default/lib/bmscl_supervisor/ebin");
    let metadata = fs::symlink_metadata(&ebin)
        .map_err(|error| format!("inspect supervisor ebin {}: {error}", ebin.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
        return Err(format!(
            "supervisor ebin must be a real directory, not a symlink: {}",
            ebin.display()
        ));
    }

    let mut files = Vec::new();
    for entry in fs::read_dir(&ebin)
        .map_err(|error| format!("read supervisor ebin {}: {error}", ebin.display()))?
    {
        let entry = entry.map_err(|error| format!("read supervisor ebin entry: {error}"))?;
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| "supervisor ebin filenames must be UTF-8".to_owned())?;
        if name.is_empty() || name.chars().any(char::is_control) {
            return Err("supervisor ebin filename contains invalid control text".to_owned());
        }
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path).map_err(|error| {
            format!("inspect supervisor ebin entry {}: {error}", path.display())
        })?;
        if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
            return Err(format!(
                "supervisor ebin may contain only regular non-symlink files: {}",
                path.display()
            ));
        }
        if metadata.len() > MAX_SUPERVISOR_EBIN_FILE_BYTES {
            return Err(format!(
                "supervisor ebin file exceeds {} bytes: {}",
                MAX_SUPERVISOR_EBIN_FILE_BYTES,
                path.display()
            ));
        }
        files.push((name, path, metadata.len()));
        if files.len() > MAX_SUPERVISOR_EBIN_FILES {
            return Err(format!(
                "supervisor ebin contains more than {MAX_SUPERVISOR_EBIN_FILES} files"
            ));
        }
    }
    files.sort_by(|left, right| left.0.as_bytes().cmp(right.0.as_bytes()));

    let mut total = 0_u64;
    let mut digest = DigestContext::new(&SHA256);
    digest.update(b"beamscale-supervisor-ebin/v1\0");
    digest.update(&(files.len() as u64).to_be_bytes());

    for (name, path, expected_len) in files {
        let name_bytes = name.as_bytes();
        digest.update(&(name_bytes.len() as u64).to_be_bytes());
        digest.update(name_bytes);
        digest.update(&expected_len.to_be_bytes());

        let mut file = fs::File::open(&path)
            .map_err(|error| format!("open supervisor ebin file {}: {error}", path.display()))?;
        let before = file.metadata().map_err(|error| {
            format!("inspect opened supervisor file {}: {error}", path.display())
        })?;
        if before.len() != expected_len {
            return Err(format!(
                "supervisor ebin file changed before hashing: {}",
                path.display()
            ));
        }

        let mut buffer = [0_u8; 64 * 1024];
        let mut read_total = 0_u64;
        loop {
            let read = file
                .read(&mut buffer)
                .map_err(|error| format!("hash supervisor file {}: {error}", path.display()))?;
            if read == 0 {
                break;
            }
            read_total = read_total
                .checked_add(read as u64)
                .ok_or_else(|| "supervisor ebin byte count overflow".to_owned())?;
            total = total
                .checked_add(read as u64)
                .ok_or_else(|| "supervisor ebin total byte count overflow".to_owned())?;
            if read_total > MAX_SUPERVISOR_EBIN_FILE_BYTES
                || total > MAX_SUPERVISOR_EBIN_TOTAL_BYTES
            {
                return Err(
                    "supervisor ebin exceeded configured byte limits while hashing".to_owned(),
                );
            }
            digest.update(&buffer[..read]);
        }

        let after = file
            .metadata()
            .map_err(|error| format!("re-inspect supervisor file {}: {error}", path.display()))?;
        if after.len() != before.len() || after.len() != read_total {
            return Err(format!(
                "supervisor ebin file changed size while hashing: {}",
                path.display()
            ));
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if after.dev() != before.dev() || after.ino() != before.ino() {
                return Err(format!(
                    "supervisor ebin file identity changed while hashing: {}",
                    path.display()
                ));
            }
        }
        if before.modified().ok().is_some()
            && after.modified().ok().is_some()
            && before.modified().ok() != after.modified().ok()
        {
            return Err(format!(
                "supervisor ebin file modification time changed while hashing: {}",
                path.display()
            ));
        }
    }

    let value = digest
        .finish()
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    debug_assert!(valid_supervisor_digest(&value));
    Ok(value)
}

fn resolve_data_root(requested: Option<PathBuf>) -> Result<PathBuf, String> {
    let root = match requested {
        Some(path) => path,
        None => default_data_root()?,
    };
    reject_control_text(&root)?;

    match fs::symlink_metadata(&root) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
                return Err(format!(
                    "state root must be a real directory, not a symlink: {}",
                    root.display()
                ));
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir_all(&root)
                .map_err(|error| format!("create state root {}: {error}", root.display()))?;
        }
        Err(error) => return Err(format!("inspect state root {}: {error}", root.display())),
    }

    let logs = root.join("logs");
    match fs::symlink_metadata(&logs) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
                return Err(format!(
                    "log root must be a real directory, not a symlink: {}",
                    logs.display()
                ));
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(&logs)
                .map_err(|error| format!("create log root {}: {error}", logs.display()))?;
        }
        Err(error) => return Err(format!("inspect log root {}: {error}", logs.display())),
    }

    fs::canonicalize(&root).map_err(|error| {
        format!(
            "canonicalize service state directory {}: {error}",
            root.display()
        )
    })
}

fn default_data_root() -> Result<PathBuf, String> {
    if let Some(home) = env::var_os("HOME") {
        return Ok(PathBuf::from(home)
            .join(".beamscale")
            .join("desktop-daemon"));
    }
    if let Some(home) = env::var_os("USERPROFILE") {
        return Ok(PathBuf::from(home)
            .join(".beamscale")
            .join("desktop-daemon"));
    }
    Err("cannot locate home directory; pass --data-root or set HOME/USERPROFILE".to_owned())
}

fn reject_control_text(path: &Path) -> Result<(), String> {
    let text = path.to_string_lossy();
    if text
        .chars()
        .any(|ch| ch == '\n' || ch == '\r' || ch == '\0')
    {
        return Err(format!(
            "path contains unsupported control characters: {}",
            path.display()
        ));
    }
    Ok(())
}

fn secure_state_root(root: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(root, fs::Permissions::from_mode(0o700))
            .map_err(|error| format!("protect state directory {}: {error}", root.display()))?;
        fs::set_permissions(root.join("logs"), fs::Permissions::from_mode(0o700))
            .map_err(|error| format!("protect log directory: {error}"))?;
    }
    #[cfg(windows)]
    {
        let root_text = root.to_string_lossy().to_string();
        let identity = current_windows_identity()?;
        let user_grant = format!("{identity}:(OI)(CI)F");
        checked(icacls_command()?.args([&root_text, "/inheritance:r", "/T", "/C"]))?;
        for sid in ["*S-1-1-0", "*S-1-5-11", "*S-1-5-32-545", "*S-1-5-32-546"] {
            checked(icacls_command()?.args([&root_text, "/remove:g", sid, "/T", "/C"]))?;
            checked(icacls_command()?.args([&root_text, "/remove:d", sid, "/T", "/C"]))?;
        }
        checked(icacls_command()?.args([
            &root_text,
            "/grant:r",
            &user_grant,
            "*S-1-5-18:(OI)(CI)F",
            "/T",
            "/C",
        ]))?;
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn install_linux(binary: &Path, root: &Path, start_now: bool) -> Result<(), String> {
    let home = env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?;
    let config_home = env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(home).join(".config"));
    let unit_dir = config_home.join("systemd").join("user");
    fs::create_dir_all(&unit_dir)
        .map_err(|error| format!("create {}: {error}", unit_dir.display()))?;
    let unit = unit_dir.join("beamscale-desktop-daemon.service");
    let rendered = render_linux(binary, root)?;
    write_private(&unit, rendered.as_bytes())?;

    checked(systemd_analyze_command()?.args([
        "--user",
        "verify",
        unit.to_string_lossy().as_ref(),
    ]))?;
    checked(systemctl_command()?.args(["--user", "daemon-reload"]))?;
    if start_now {
        checked(systemctl_command()?.args([
            "--user",
            "enable",
            "--now",
            "beamscale-desktop-daemon.service",
        ]))?;
    } else {
        checked(systemctl_command()?.args([
            "--user",
            "enable",
            "beamscale-desktop-daemon.service",
        ]))?;
    }
    println!("installed {}", unit.display());
    Ok(())
}

#[cfg(target_os = "linux")]
fn uninstall_linux() -> Result<(), String> {
    let unit = linux_definition_path()?;
    if regular_definition_file(&unit)? {
        // Remove future persistence without stopping the daemon that may be
        // invoking this helper through operator RPC.
        checked(systemctl_command()?.args([
            "--user",
            "disable",
            "beamscale-desktop-daemon.service",
        ]))?;
        fs::remove_file(&unit).map_err(|error| format!("remove {}: {error}", unit.display()))?;
    }

    checked(systemctl_command()?.args(["--user", "daemon-reload"]))?;
    let reset = run_status_bounded(
        systemctl_command()?.args(["--user", "reset-failed", "beamscale-desktop-daemon.service"]),
        SERVICE_COMMAND_TIMEOUT,
    )
    .map_err(|error| format!("reset removed systemd service state: {error}"))?;
    if !reset.success() {
        // reset-failed can legitimately report a missing unit after a complete
        // uninstall, so it is advisory only.
        eprintln!("warning: systemctl reset-failed exited with {reset}");
    }

    println!(
        "removed service registration; current daemon remains running; daemon state was preserved"
    );
    Ok(())
}

#[cfg(target_os = "macos")]
fn install_macos(binary: &Path, root: &Path, start_now: bool) -> Result<(), String> {
    let home = PathBuf::from(env::var_os("HOME").ok_or_else(|| "HOME is not set".to_owned())?);
    let launch_agents = home.join("Library").join("LaunchAgents");
    fs::create_dir_all(&launch_agents)
        .map_err(|error| format!("create {}: {error}", launch_agents.display()))?;
    let plist = launch_agents.join(format!("{LABEL}.plist"));
    let rendered = render_macos(binary, root)?;
    write_private(&plist, rendered.as_bytes())?;
    checked(plutil_command()?.args(["-lint", plist.to_string_lossy().as_ref()]))?;

    let domain = format!("gui/{}", current_uid()?);
    let target = format!("{domain}/{LABEL}");
    if start_now {
        let _ = run_status_bounded(
            launchctl_command()?.args(["bootout", &target]),
            SERVICE_COMMAND_TIMEOUT,
        );
        checked(launchctl_command()?.args([
            "bootstrap",
            &domain,
            plist.to_string_lossy().as_ref(),
        ]))?;
        checked(launchctl_command()?.args(["enable", &target]))?;
        checked(launchctl_command()?.args(["kickstart", "-k", &target]))?;
    } else {
        // Register persistence for the next login without replacing the
        // daemon that may have invoked this helper through operator RPC.
        checked(launchctl_command()?.args(["enable", &target]))?;
    }
    println!("installed {}", plist.display());
    Ok(())
}

#[cfg(target_os = "macos")]
fn uninstall_macos() -> Result<(), String> {
    let plist = macos_definition_path()?;
    let domain = format!("gui/{}", current_uid()?);
    let target = format!("{domain}/{LABEL}");

    // Disable future launchd starts first, but deliberately do not boot out
    // the currently running job. If this helper was invoked by that daemon,
    // bootout would kill both processes before plist removal completes.
    let disable = run_status_bounded(
        launchctl_command()?.args(["disable", &target]),
        SERVICE_COMMAND_TIMEOUT,
    )?;
    if !disable.success() && regular_definition_file(&plist)? {
        return Err(format!("launchctl disable exited with {disable}"));
    }

    match fs::remove_file(&plist) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(format!("remove {}: {error}", plist.display())),
    }
    println!(
        "removed service registration; current daemon remains running; daemon state was preserved"
    );
    Ok(())
}

#[cfg(target_os = "macos")]
fn current_uid() -> Result<String, String> {
    let output = run_output_bounded(
        id_command()?.arg("-u"),
        SERVICE_STATUS_COMMAND_TIMEOUT,
        1024,
    )
    .map_err(|error| format!("run id -u: {error}"))?;
    if !output.status.success() {
        return Err("id -u failed".to_owned());
    }
    let uid = String::from_utf8(output.stdout).map_err(|_| "id -u returned non-UTF8".to_owned())?;
    let uid = uid.trim();
    if uid.is_empty() || !uid.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("id -u returned an invalid uid".to_owned());
    }
    Ok(uid.to_owned())
}

#[cfg(target_os = "windows")]
fn current_windows_identity() -> Result<String, String> {
    let output = run_output_bounded(&mut whoami_command()?, SERVICE_COMMAND_TIMEOUT, 4096)
        .map_err(|error| format!("run whoami: {error}"))?;
    if !output.status.success() {
        return Err("whoami failed".to_owned());
    }
    let identity =
        String::from_utf8(output.stdout).map_err(|_| "whoami returned non-UTF8".to_owned())?;
    let identity = identity.trim();
    if identity.is_empty()
        || identity
            .chars()
            .any(|ch| ch == '\n' || ch == '\r' || ch == '\0')
    {
        return Err("whoami returned an invalid account identity".to_owned());
    }
    Ok(identity.to_owned())
}

#[cfg(target_os = "windows")]
fn install_windows(binary: &Path, root: &Path, start_now: bool) -> Result<(), String> {
    let default_root = default_data_root()?;
    let default = fs::canonicalize(&default_root).map_err(|error| {
        format!(
            "canonicalize default state root {}: {error}",
            default_root.display()
        )
    })?;
    if root != default {
        return Err("Windows Scheduled Task installation currently requires the default per-user state root; custom BMSCL_DESKTOP_HOME is intentionally rejected rather than encoded through a shell wrapper".to_owned());
    }
    let binary = binary.to_string_lossy().to_string();
    if binary.contains('"') {
        return Err("daemon binary path contains an unsupported quote character".to_owned());
    }
    let action = format!("\"{binary}\"");
    checked(schtasks_command()?.args([
        "/Create", "/F", "/SC", "ONLOGON", "/RL", "LIMITED", "/TN", TASK_NAME, "/TR", &action,
    ]))?;
    if start_now {
        checked(schtasks_command()?.args(["/Run", "/TN", TASK_NAME]))?;
    }
    println!("installed current-user scheduled task: {TASK_NAME}");
    Ok(())
}

#[cfg(target_os = "windows")]
fn uninstall_windows() -> Result<(), String> {
    if windows_task_installed()? {
        checked(schtasks_command()?.args(["/Delete", "/F", "/TN", TASK_NAME]))?;

        if windows_task_installed()? {
            return Err(
                "Scheduled Task definition still exists after successful schtasks deletion"
                    .to_owned(),
            );
        }
    }

    println!(
        "removed service registration; current daemon remains running; daemon state was preserved"
    );
    Ok(())
}

#[cfg(any(target_os = "linux", test))]
fn render_linux(binary: &Path, root: &Path) -> Result<String, String> {
    let binary = systemd_escape(binary)?;
    let root = systemd_escape(root)?;
    Ok(LINUX_TEMPLATE
        .replace("@BINARY@", &binary)
        .replace("@STATE_DIR@", &root))
}

#[cfg(any(target_os = "linux", test))]
fn systemd_escape(path: &Path) -> Result<String, String> {
    reject_control_text(path)?;
    Ok(path
        .to_string_lossy()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('%', "%%"))
}

#[cfg(any(target_os = "macos", test))]
fn render_macos(binary: &Path, root: &Path) -> Result<String, String> {
    let logs_path = root.join("logs");
    let binary = xml_escape(binary)?;
    let root = xml_escape(root)?;
    let logs = xml_escape(&logs_path)?;
    Ok(MACOS_TEMPLATE
        .replace("@BINARY@", &binary)
        .replace("@STATE_DIR@", &root)
        .replace("@LOG_DIR@", &logs))
}

#[cfg(any(target_os = "macos", test))]
fn xml_escape(path: &Path) -> Result<String, String> {
    reject_control_text(path)?;
    Ok(path
        .to_string_lossy()
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;"))
}

#[cfg(any(target_os = "linux", target_os = "macos"))]
fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};

    let parent = path
        .parent()
        .ok_or_else(|| format!("{} has no parent", path.display()))?;
    fs::create_dir_all(parent).map_err(|error| format!("create {}: {error}", parent.display()))?;
    let file_name = path
        .file_name()
        .ok_or_else(|| format!("{} has no file name", path.display()))?
        .to_string_lossy();
    let temporary = parent.join(format!(
        ".{file_name}.{}.{}.tmp",
        std::process::id(),
        rand::random::<u64>()
    ));
    let result = (|| {
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(|error| {
                format!("create private temporary {}: {error}", temporary.display())
            })?;
        file.write_all(bytes)
            .map_err(|error| format!("write {}: {error}", temporary.display()))?;
        file.sync_all()
            .map_err(|error| format!("sync {}: {error}", temporary.display()))?;
        drop(file);
        fs::rename(&temporary, path)
            .map_err(|error| format!("replace {}: {error}", path.display()))?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .map_err(|error| format!("protect {}: {error}", path.display()))?;
        fs::File::open(parent)
            .map_err(|error| format!("open service directory {}: {error}", parent.display()))?
            .sync_all()
            .map_err(|error| format!("sync service directory {}: {error}", parent.display()))?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(unix)]
fn configure_subprocess_tree(command: &mut Command) {
    use std::os::unix::process::CommandExt;
    command.process_group(0);
}

#[cfg(not(unix))]
fn configure_subprocess_tree(_command: &mut Command) {}

#[cfg(unix)]
fn terminate_subprocess_tree(child: &mut Child) -> Result<(), String> {
    use nix::{
        errno::Errno,
        sys::signal::{Signal, killpg},
        unistd::Pid,
    };

    let process_group = i32::try_from(child.id())
        .map_err(|_| "service command process id exceeds i32".to_owned())?;
    let pid = Pid::from_raw(process_group);

    if let Err(error) = killpg(pid, Signal::SIGTERM) {
        if error != Errno::ESRCH {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("send SIGTERM to service command group: {error}"));
        }
    }

    // Give the whole group a short grace period, then send SIGKILL even if
    // the direct child exited. A descendant may ignore SIGTERM while keeping
    // inherited pipes or service-manager state alive.
    for _ in 0..20 {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => thread::sleep(Duration::from_millis(10)),
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("poll timed-out service command: {error}"));
            }
        }
    }

    if let Err(error) = killpg(pid, Signal::SIGKILL) {
        if error != Errno::ESRCH {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("send SIGKILL to service command group: {error}"));
        }
    }
    let _ = child.wait();
    Ok(())
}

#[cfg(target_os = "windows")]
fn terminate_subprocess_tree(child: &mut Child) -> Result<(), String> {
    let pid = child.id().to_string();
    let mut killer = taskkill_command()?;
    killer
        .args(["/PID", &pid, "/T", "/F"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    let mut killer_child = killer
        .spawn()
        .map_err(|error| format!("launch taskkill for service command tree: {error}"))?;
    let deadline = Instant::now() + SERVICE_TREE_CLEANUP_TIMEOUT;
    loop {
        match killer_child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() && child.try_wait().ok().flatten().is_none() {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err(format!(
                        "taskkill for service command tree exited with {status}"
                    ));
                }
                break;
            }
            Ok(None) if Instant::now() >= deadline => {
                let _ = killer_child.kill();
                let _ = killer_child.wait();
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!(
                    "taskkill for service command tree timed out after {} seconds",
                    SERVICE_TREE_CLEANUP_TIMEOUT.as_secs()
                ));
            }
            Ok(None) => thread::sleep(Duration::from_millis(25)),
            Err(error) => {
                let _ = killer_child.kill();
                let _ = killer_child.wait();
                let _ = child.kill();
                let _ = child.wait();
                return Err(format!("poll taskkill for service command tree: {error}"));
            }
        }
    }

    let _ = child.wait();
    Ok(())
}

#[cfg(not(any(unix, target_os = "windows")))]
fn terminate_subprocess_tree(child: &mut Child) -> Result<(), String> {
    child
        .kill()
        .map_err(|error| format!("kill service command: {error}"))?;
    let _ = child.wait();
    Ok(())
}

fn scrub_service_subprocess_environment(command: &mut Command) {
    for key in [
        "BMSCL_DAEMON_TOKEN",
        "BMSCL_DAEMON_OPERATOR_TOKEN",
        "BMSCL_LOCAL_INGRESS_TOKEN",
        "BMSCL_ADMIN_TOKEN",
        "CLOUDFLARE_API_TOKEN",
        "CLOUDFLARE_API_KEY",
        "CF_API_TOKEN",
        "CF_API_KEY",
    ] {
        command.env_remove(key);
    }
}

fn run_status_bounded(command: &mut Command, timeout: Duration) -> Result<ExitStatus, String> {
    scrub_service_subprocess_environment(command);
    let display = format!("{command:?}");
    command.stdin(Stdio::null());
    configure_subprocess_tree(command);
    let mut child = command
        .spawn()
        .map_err(|error| format!("launch {display}: {error}"))?;
    let deadline = Instant::now() + timeout;

    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Ok(status),
            Ok(None) if Instant::now() >= deadline => {
                let cleanup = terminate_subprocess_tree(&mut child);
                return match cleanup {
                    Ok(()) => Err(format!(
                        "{display} timed out after {} seconds",
                        timeout.as_secs()
                    )),
                    Err(error) => Err(format!(
                        "{display} timed out after {} seconds; process-tree cleanup failed: {error}",
                        timeout.as_secs()
                    )),
                };
            }
            Ok(None) => thread::sleep(Duration::from_millis(25)),
            Err(error) => {
                let cleanup = terminate_subprocess_tree(&mut child);
                return match cleanup {
                    Ok(()) => Err(format!("poll {display}: {error}")),
                    Err(cleanup_error) => Err(format!(
                        "poll {display}: {error}; process-tree cleanup failed: {cleanup_error}"
                    )),
                };
            }
        }
    }
}

fn read_bounded<R: Read>(
    mut reader: R,
    maximum: usize,
    label: &'static str,
) -> Result<Vec<u8>, String> {
    let mut output = Vec::new();
    let mut limited = reader.by_ref().take((maximum + 1) as u64);
    limited
        .read_to_end(&mut output)
        .map_err(|error| format!("read {label}: {error}"))?;
    if output.len() > maximum {
        return Err(format!("{label} exceeded {maximum} bytes"));
    }
    Ok(output)
}

fn run_output_bounded(
    command: &mut Command,
    timeout: Duration,
    maximum: usize,
) -> Result<Output, String> {
    use std::sync::mpsc;

    scrub_service_subprocess_environment(command);
    let display = format!("{command:?}");
    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    configure_subprocess_tree(command);
    let mut child = command
        .spawn()
        .map_err(|error| format!("launch {display}: {error}"))?;

    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => format!("{display} stdout pipe unavailable"),
                Err(error) => format!("{display} stdout pipe unavailable; cleanup failed: {error}"),
            });
        }
    };
    let stderr = match child.stderr.take() {
        Some(stderr) => stderr,
        None => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => format!("{display} stderr pipe unavailable"),
                Err(error) => format!("{display} stderr pipe unavailable; cleanup failed: {error}"),
            });
        }
    };

    let (stdout_tx, stdout_rx) = mpsc::sync_channel(1);
    let (stderr_tx, stderr_rx) = mpsc::sync_channel(1);
    thread::spawn(move || {
        let _ = stdout_tx.send(read_bounded(stdout, maximum, "service command stdout"));
    });
    thread::spawn(move || {
        let _ = stderr_tx.send(read_bounded(stderr, maximum, "service command stderr"));
    });

    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => {
                let cleanup = terminate_subprocess_tree(&mut child);
                return Err(match cleanup {
                    Ok(()) => format!("{display} timed out after {} seconds", timeout.as_secs()),
                    Err(error) => format!(
                        "{display} timed out after {} seconds; process-tree cleanup failed: {error}",
                        timeout.as_secs()
                    ),
                });
            }
            Ok(None) => thread::sleep(Duration::from_millis(25)),
            Err(error) => {
                let cleanup = terminate_subprocess_tree(&mut child);
                return Err(match cleanup {
                    Ok(()) => format!("poll {display}: {error}"),
                    Err(cleanup_error) => format!(
                        "poll {display}: {error}; process-tree cleanup failed: {cleanup_error}"
                    ),
                });
            }
        }
    };

    let remaining = || deadline.saturating_duration_since(Instant::now());
    let stdout_result = match stdout_rx.recv_timeout(remaining()) {
        Ok(result) => result,
        Err(_) => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => format!("{display} stdout did not close before command deadline"),
                Err(error) => format!(
                    "{display} stdout did not close before command deadline; process-tree cleanup failed: {error}"
                ),
            });
        }
    };
    let stderr_result = match stderr_rx.recv_timeout(remaining()) {
        Ok(result) => result,
        Err(_) => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => format!("{display} stderr did not close before command deadline"),
                Err(error) => format!(
                    "{display} stderr did not close before command deadline; process-tree cleanup failed: {error}"
                ),
            });
        }
    };

    let stdout = match stdout_result {
        Ok(stdout) => stdout,
        Err(error) => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => error,
                Err(cleanup_error) => {
                    format!("{error}; process-tree cleanup failed: {cleanup_error}")
                }
            });
        }
    };
    let stderr = match stderr_result {
        Ok(stderr) => stderr,
        Err(error) => {
            let cleanup = terminate_subprocess_tree(&mut child);
            return Err(match cleanup {
                Ok(()) => error,
                Err(cleanup_error) => {
                    format!("{error}; process-tree cleanup failed: {cleanup_error}")
                }
            });
        }
    };

    Ok(Output {
        status,
        stdout,
        stderr,
    })
}

fn checked_with_timeout(command: &mut Command, timeout: Duration) -> Result<ExitStatus, String> {
    let display = format!("{command:?}");
    let status = run_status_bounded(command, timeout)?;
    if status.success() {
        Ok(status)
    } else {
        Err(format!("{display} exited with {status}"))
    }
}

fn checked(command: &mut Command) -> Result<ExitStatus, String> {
    checked_with_timeout(command, SERVICE_COMMAND_TIMEOUT)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uninstall_source_never_stops_invoking_daemon() {
        let source = include_str!("beamscale-service.rs");

        let linux_start = source
            .find("fn uninstall_linux()")
            .expect("linux uninstall");
        let linux_end = source[linux_start..]
            .find("fn install_macos(")
            .map(|offset| linux_start + offset)
            .expect("linux uninstall end");
        let linux = &source[linux_start..linux_end];
        assert!(!linux.contains("\"--now\""));
        assert!(!linux.contains("\"stop\""));
        assert!(linux.contains("current daemon remains running"));

        let mac_start = source.find("fn uninstall_macos()").expect("mac uninstall");
        let mac_end = source[mac_start..]
            .find("fn current_uid(")
            .map(|offset| mac_start + offset)
            .expect("mac uninstall end");
        let mac = &source[mac_start..mac_end];
        assert!(!mac.contains("\"bootout\""));
        assert!(mac.contains("\"disable\""));
        assert!(mac.contains("current daemon remains running"));

        let windows_start = source
            .find("fn uninstall_windows()")
            .expect("windows uninstall");
        let windows_end = source[windows_start..]
            .find("fn render_linux(")
            .map(|offset| windows_start + offset)
            .expect("windows uninstall end");
        let windows = &source[windows_start..windows_end];
        assert!(!windows.contains("\"/Run\""));
        assert!(windows.contains("current daemon remains running"));
    }

    #[test]
    fn install_no_start_is_typed_and_install_only() {
        let options = parse_args(vec![
            OsString::from("install"),
            OsString::from("--no-start"),
        ])
        .expect("parse registration-only install");
        assert_eq!(options.action, Action::Install);
        assert!(!options.start_now);

        assert!(parse_args(vec![OsString::from("status"), OsString::from("--no-start"),]).is_err());
        assert!(
            parse_args(vec![
                OsString::from("uninstall"),
                OsString::from("--no-start"),
            ])
            .is_err()
        );
    }

    #[test]
    fn registration_only_install_avoids_immediate_start_primitives() {
        let source = include_str!("beamscale-service.rs");
        assert!(source.contains("if start_now"));
        assert!(source.contains("Register persistence for the next login"));
        assert!(source.contains("install --no-start") || source.contains("--no-start"));
    }

    #[test]
    fn os_service_subprocesses_do_not_inherit_control_credentials() {
        let mut command = Command::new("ignored");
        for key in [
            "BMSCL_DAEMON_TOKEN",
            "BMSCL_DAEMON_OPERATOR_TOKEN",
            "BMSCL_LOCAL_INGRESS_TOKEN",
            "BMSCL_ADMIN_TOKEN",
            "CLOUDFLARE_API_TOKEN",
            "CLOUDFLARE_API_KEY",
            "CF_API_TOKEN",
            "CF_API_KEY",
        ] {
            command.env(key, "secret");
        }

        scrub_service_subprocess_environment(&mut command);
        let envs = command
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().to_string(),
                    value.map(|value| value.to_string_lossy().to_string()),
                )
            })
            .collect::<std::collections::HashMap<_, _>>();

        for key in [
            "BMSCL_DAEMON_TOKEN",
            "BMSCL_DAEMON_OPERATOR_TOKEN",
            "BMSCL_LOCAL_INGRESS_TOKEN",
            "BMSCL_ADMIN_TOKEN",
            "CLOUDFLARE_API_TOKEN",
            "CLOUDFLARE_API_KEY",
            "CF_API_TOKEN",
            "CF_API_KEY",
        ] {
            assert_eq!(envs.get(key), Some(&None), "{key}");
        }
    }

    #[test]
    fn status_command_timeout_stays_inside_client_budget() {
        assert!(
            SERVICE_STATUS_COMMAND_TIMEOUT + SERVICE_TREE_CLEANUP_TIMEOUT < Duration::from_secs(10)
        );
        assert!(SERVICE_STATUS_COMMAND_TIMEOUT < SERVICE_COMMAND_TIMEOUT);
    }

    #[test]
    fn systemd_state_parser_requires_one_explicit_state() {
        assert_eq!(
            parse_systemd_state_output(b"enabled\n", b"", "enable").expect("enabled"),
            "enabled"
        );
        assert_eq!(
            parse_systemd_state_output(b"inactive\n", b"", "active").expect("inactive"),
            "inactive"
        );
        assert!(parse_systemd_state_output(b"", b"Failed to connect to bus", "active").is_err());
        assert!(parse_systemd_state_output(b"active\nfailed\n", b"", "active").is_err());
        assert!(parse_systemd_state_output(&[0xff], b"", "active").is_err());
        assert!(parse_systemd_state_output(b"active\n", &[0xff], "active").is_err());
    }

    #[test]
    fn first_install_rollback_stops_and_disables_partial_systemd_service() {
        assert_eq!(
            linux_pre_restore_actions(false),
            vec![
                vec!["--user", "stop", "beamscale-desktop-daemon.service"],
                vec!["--user", "disable", "beamscale-desktop-daemon.service"],
            ]
        );
        assert_eq!(
            linux_post_restore_actions(false, false, false),
            vec![vec![
                "--user",
                "reset-failed",
                "beamscale-desktop-daemon.service",
            ]]
        );
    }

    #[test]
    fn upgrade_rollback_restores_prior_systemd_enable_and_active_state() {
        assert_eq!(
            linux_post_restore_actions(true, true, false),
            vec![
                vec!["--user", "enable", "beamscale-desktop-daemon.service"],
                vec!["--user", "stop", "beamscale-desktop-daemon.service"],
            ]
        );
        assert_eq!(
            linux_post_restore_actions(true, false, true),
            vec![
                vec!["--user", "disable", "beamscale-desktop-daemon.service"],
                vec!["--user", "start", "beamscale-desktop-daemon.service"],
            ]
        );
    }

    #[cfg(unix)]
    #[test]
    fn bounded_output_does_not_wait_for_inherited_descendant_pipe() {
        let mut command = Command::new("sh");
        command.args(["-c", "sleep 2 & printf done"]);
        let started = Instant::now();
        let error = run_output_bounded(&mut command, Duration::from_millis(100), 1024)
            .expect_err("inherited descendant pipe must not outlive deadline");
        assert!(error.contains("did not close before command deadline"));
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[cfg(unix)]
    #[test]
    fn bounded_service_command_times_out() {
        let mut command = Command::new("sh");
        command.args(["-c", "sleep 2"]);
        let started = Instant::now();
        let error = run_status_bounded(&mut command, Duration::from_millis(50))
            .expect_err("command must time out");
        assert!(error.contains("timed out"));
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[cfg(unix)]
    #[test]
    fn timed_out_service_command_does_not_orphan_descendant() {
        use nix::{errno::Errno, sys::signal::kill, unistd::Pid};

        let root = tempfile::tempdir().expect("temp root");
        let pid_file = root.path().join("descendant.pid");
        let script = format!(
            "sleep 30 & child=$!; printf '%s' \"$child\" > {}; wait \"$child\"",
            pid_file.display()
        );
        let mut command = Command::new("sh");
        command.args(["-c", &script]);

        let error = run_status_bounded(&mut command, Duration::from_millis(100))
            .expect_err("command must time out");
        assert!(error.contains("timed out"));

        let pid_text = fs::read_to_string(&pid_file).expect("read descendant pid");
        let pid = pid_text.parse::<i32>().expect("parse descendant pid");
        for _ in 0..50 {
            match kill(Pid::from_raw(pid), None) {
                Err(Errno::ESRCH) => return,
                Ok(()) | Err(_) => thread::sleep(Duration::from_millis(10)),
            }
        }
        panic!("timed-out service command left descendant {pid} alive");
    }

    #[test]
    fn file_snapshot_restores_previous_bytes_and_removes_new_files() {
        let root = tempfile::tempdir().expect("temp root");
        let existing = root.path().join("existing.json");
        write_service_state_file(&existing, b"old").expect("write old");
        let snapshot = snapshot_regular_file(existing.clone(), "existing").expect("snapshot");
        write_service_state_file(&existing, b"new").expect("write new");
        restore_file_snapshot(&snapshot).expect("restore old");
        assert_eq!(fs::read(&existing).expect("read restored"), b"old");

        let new_file = root.path().join("new.json");
        let missing = snapshot_regular_file(new_file.clone(), "new").expect("snapshot missing");
        write_service_state_file(&new_file, b"new").expect("write new file");
        restore_file_snapshot(&missing).expect("remove new file");
        assert!(!new_file.exists());
    }

    #[cfg(unix)]
    #[test]
    fn file_snapshot_rejects_symlink_inputs() {
        use std::os::unix::fs::symlink;

        let root = tempfile::tempdir().expect("temp root");
        let target = root.path().join("target");
        fs::write(&target, b"state").expect("write target");
        let link = root.path().join("state.json");
        symlink(&target, &link).expect("symlink");
        assert!(snapshot_regular_file(link, "state").is_err());
    }

    #[test]
    fn supervisor_ebin_digest_is_deterministic_and_content_sensitive() {
        let root = tempfile::tempdir().expect("temp root");
        let supervisor = root.path().join("supervisor");
        let ebin = supervisor.join("_build/default/lib/bmscl_supervisor/ebin");
        fs::create_dir_all(&ebin).expect("create ebin");

        fs::write(ebin.join("z.beam"), b"z").expect("write z");
        fs::write(ebin.join("a.beam"), b"a").expect("write a");
        let first = supervisor_ebin_sha256(&supervisor).expect("first digest");
        assert!(valid_supervisor_digest(&first));

        fs::remove_file(ebin.join("z.beam")).expect("remove z");
        fs::remove_file(ebin.join("a.beam")).expect("remove a");
        fs::write(ebin.join("a.beam"), b"a").expect("rewrite a");
        fs::write(ebin.join("z.beam"), b"z").expect("rewrite z");
        let reordered = supervisor_ebin_sha256(&supervisor).expect("reordered digest");
        assert_eq!(
            first, reordered,
            "directory enumeration order must not affect digest"
        );

        fs::write(ebin.join("a.beam"), b"changed").expect("mutate a");
        let changed = supervisor_ebin_sha256(&supervisor).expect("changed digest");
        assert_ne!(first, changed, "content mutation must change digest");

        fs::write(ebin.join("extra.app"), b"extra").expect("add extra");
        let added = supervisor_ebin_sha256(&supervisor).expect("added-file digest");
        assert_ne!(changed, added, "adding a file must change digest");
    }

    #[test]
    fn persistent_service_document_pins_daemon_bytes() {
        let root = tempfile::tempdir().expect("temp root");
        let daemon = root.path().join("beamscale-desktop-daemon");
        fs::write(&daemon, b"daemon-bytes").expect("write daemon");
        let digest = executable_sha256(&daemon, "beamscale-desktop-daemon").expect("hash daemon");
        assert_eq!(digest.len(), 64);
        assert!(digest.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }

    #[test]
    fn executable_digest_changes_when_tool_bytes_change() {
        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("bmscl");
        fs::write(&tool, b"first").expect("write first tool");
        let first = executable_sha256(&tool, "bmscl").expect("hash first tool");
        fs::write(&tool, b"second").expect("write second tool");
        let second = executable_sha256(&tool, "bmscl").expect("hash second tool");
        assert_ne!(first, second);
        assert_eq!(first.len(), 64);
        assert_eq!(second.len(), 64);
    }

    #[test]
    fn supervisor_root_requires_compiled_expected_modules() {
        let root = tempfile::tempdir().expect("temp root");
        let supervisor = root.path().join("supervisor");
        let ebin = supervisor.join("_build/default/lib/bmscl_supervisor/ebin");
        fs::create_dir_all(&ebin).expect("create ebin");

        for module in [
            "bmscl_deployment_manager.beam",
            "bmscl_route_table.beam",
            "bmscl_runtime.beam",
        ] {
            fs::write(ebin.join(module), b"beam").expect("write module");
        }

        let resolved = resolve_supervisor_root(Some(supervisor.clone()))
            .expect("resolve supervisor")
            .expect("supervisor root");
        assert_eq!(
            resolved,
            fs::canonicalize(&supervisor).expect("canonical root")
        );

        fs::remove_file(ebin.join("bmscl_runtime.beam")).expect("remove module");
        assert!(resolve_supervisor_root(Some(supervisor)).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn supervisor_root_rejects_symlink_root() {
        use std::os::unix::fs::symlink;

        let root = tempfile::tempdir().expect("temp root");
        let target = root.path().join("real-supervisor");
        fs::create_dir(&target).expect("create real supervisor");
        let link = root.path().join("supervisor");
        symlink(&target, &link).expect("create supervisor symlink");
        assert!(resolve_supervisor_root(Some(link)).is_err());
    }

    #[test]
    fn parses_install_without_shell_payloads() {
        let parsed = parse_args(vec![
            OsString::from("install"),
            OsString::from("--binary"),
            OsString::from("/tmp/beamscale-desktop-daemon"),
            OsString::from("--data-root"),
            OsString::from("/tmp/state"),
        ])
        .expect("parse");
        assert_eq!(parsed.action, Action::Install);
        assert_eq!(
            parsed.binary.unwrap(),
            PathBuf::from("/tmp/beamscale-desktop-daemon")
        );
    }

    #[test]
    fn parses_strict_json_status_option() {
        let parsed = parse_args(vec![OsString::from("status"), OsString::from("--json")])
            .expect("parse json status");
        assert_eq!(parsed.action, Action::Status);
        assert!(parsed.json);

        assert!(parse_args(vec![OsString::from("install"), OsString::from("--json"),]).is_err());
        assert!(
            parse_args(vec![
                OsString::from("status"),
                OsString::from("--json"),
                OsString::from("--json"),
            ])
            .is_err()
        );
    }

    #[test]
    fn parses_launchd_running_state_strictly() {
        assert!(
            parse_launchd_running("path = /tmp/x\nstate = running\npid = 123\n")
                .expect("running state")
        );
        assert!(
            !parse_launchd_running("state = exited\nlast exit code = 0\n").expect("exited state")
        );
        assert!(parse_launchd_running("path = /tmp/x\n").is_err());
        assert!(parse_launchd_running("state = running\nstate = exited\n").is_err());
    }

    #[test]
    fn service_definition_status_rejects_unexpected_filesystem_objects() {
        let root = tempfile::tempdir().expect("temp root");
        let missing = root.path().join("missing.service");
        assert!(!regular_definition_file(&missing).expect("missing is absent"));

        let file = root.path().join("regular.service");
        fs::write(&file, b"unit").expect("write regular definition");
        assert!(regular_definition_file(&file).expect("regular definition"));

        let directory = root.path().join("directory.service");
        fs::create_dir(&directory).expect("create directory definition");
        assert!(regular_definition_file(&directory).is_err());

        #[cfg(unix)]
        {
            use std::os::unix::fs::symlink;
            let link = root.path().join("link.service");
            symlink(&file, &link).expect("create definition symlink");
            assert!(regular_definition_file(&link).is_err());
        }
    }

    #[test]
    fn parses_systemd_show_strictly_without_order_dependency() {
        assert_eq!(
            parse_systemd_show("ActiveState=active\nLoadState=loaded\n").expect("valid output"),
            ("loaded".into(), "active".into())
        );
        assert!(parse_systemd_show("LoadState=loaded\n").is_err());
        assert!(
            parse_systemd_show("LoadState=loaded\nActiveState=active\nActiveState=inactive\n")
                .is_err()
        );
        assert!(
            parse_systemd_show("LoadState=loaded\nActiveState=active\nUnexpected=value\n").is_err()
        );
    }

    #[test]
    fn rejects_install_flags_on_status() {
        assert!(
            parse_args(vec![
                OsString::from("status"),
                OsString::from("--binary"),
                OsString::from("/tmp/daemon"),
            ])
            .is_err()
        );
    }

    #[test]
    fn linux_template_preserves_hardening_and_no_shell() {
        let rendered = render_linux(
            Path::new("/opt/Beam Scale/bin/beamscale-desktop-daemon"),
            Path::new("/home/user/.beamscale/desktop-daemon"),
        )
        .expect("render");
        assert!(rendered.contains("NoNewPrivileges=true"));
        assert!(rendered.contains("UMask=0077"));
        assert!(rendered.contains("ExecStart=\"/opt/Beam Scale/bin/beamscale-desktop-daemon\""));
        assert!(!rendered.contains("/bin/sh"));
    }

    #[test]
    fn macos_template_escapes_xml_metacharacters() {
        let rendered = render_macos(
            Path::new("/Applications/Beam&Scale/daemon"),
            Path::new("/Users/u/.beamscale/&quot;-desktop"),
        )
        .expect("render");
        assert!(rendered.contains("Beam&amp;Scale"));
        assert!(rendered.contains("&amp;quot;-desktop"));
        assert!(rendered.contains("&amp;quot;-desktop/logs"));
        assert!(rendered.contains("<key>KeepAlive</key>"));
        assert!(!rendered.contains("/bin/sh"));
    }

    #[cfg(unix)]
    #[test]
    fn package_manager_symlinks_are_resolved_to_a_pinned_executable_target() {
        use std::os::unix::fs::{PermissionsExt, symlink};

        let root = tempfile::tempdir().expect("temp root");
        let first = root.path().join("daemon-v1");
        fs::write(&first, b"#!/bin/sh\nexit 0\n").expect("write first target");
        fs::set_permissions(&first, fs::Permissions::from_mode(0o700)).expect("chmod first");

        let second = root.path().join("daemon-v2");
        fs::write(&second, b"#!/bin/sh\nexit 0\n").expect("write second target");
        fs::set_permissions(&second, fs::Permissions::from_mode(0o700)).expect("chmod second");

        let link = root.path().join("beamscale-desktop-daemon");
        symlink(&first, &link).expect("symlink");

        let pinned = resolve_binary(Some(link.clone())).expect("pin symlink target");
        assert_eq!(pinned, fs::canonicalize(&first).expect("canonical first"));
        assert_ne!(pinned, link);

        fs::remove_file(&link).expect("remove old symlink");
        symlink(&second, &link).expect("retarget symlink");
        assert_eq!(
            pinned,
            fs::canonicalize(&first).expect("first remains pinned")
        );

        fs::set_permissions(&first, fs::Permissions::from_mode(0o600)).expect("remove execute");
        assert!(pin_executable_path(&first, "daemon binary").is_err());
    }

    #[cfg(unix)]
    #[test]
    fn daemon_binary_rejects_service_definition_control_characters() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let binary = root.path().join("daemon\nInjected=1");
        fs::write(&binary, b"#!/bin/sh\nexit 0\n").expect("write binary");
        fs::set_permissions(&binary, fs::Permissions::from_mode(0o700)).expect("chmod binary");

        assert!(resolve_binary(Some(binary)).is_err());
    }

    #[test]
    fn service_templates_do_not_embed_credentials() {
        for template in [LINUX_TEMPLATE, MACOS_TEMPLATE] {
            assert!(!template.contains("BMSCL_DAEMON_TOKEN"));
            assert!(!template.contains("BMSCL_DAEMON_OPERATOR_TOKEN"));
            assert!(!template.contains("BMSCL_LOCAL_INGRESS_TOKEN"));
        }
    }
}
