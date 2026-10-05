mod runtime_manifest;

use anyhow::{Context, Result, bail};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Query, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post, put},
};
use rand::RngCore;
use ring::digest::{Context as DigestContext, SHA256};
use serde::{Deserialize, Serialize};
#[cfg(unix)]
use std::thread;
use std::{
    collections::{HashSet, VecDeque},
    env, fs,
    io::{Read, Write},
    net::SocketAddr,
    path::{Path, PathBuf},
    process::{Child, Command, ExitStatus, Stdio},
    sync::{Arc, Mutex, MutexGuard},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;

const DEFAULT_LISTEN: &str = "127.0.0.1:9587";
const API_VERSION: &str = "beamscale.desktop-daemon/v1";
const PROTOCOL_HEADER: &str = "x-ores-protocol-version";
const IDEMPOTENCY_HEADER: &str = "x-ores-idempotency-key";
const MAX_RECENT_IDEMPOTENCY_KEYS: usize = 2048;
const MAX_LIFECYCLE_EVENTS: usize = 512;
const MAX_EVENT_DETAIL_BYTES: usize = 512;
const MAX_DNS_ROUTES: usize = 2048;
const WATCHDOG_INTERVAL: Duration = Duration::from_secs(5);
const MIN_TOKEN_BYTES: usize = 24;
const MAX_TOKEN_BYTES: usize = 4096;
const MAX_STATE_FILE_BYTES: usize = 1024 * 1024;
const MAX_INGRESS_RESPONSE_BYTES: usize = 1024 * 1024;
const MAX_PROBE_OUTPUT_BYTES: usize = 64 * 1024;
const MAX_PINNED_TOOL_BYTES: u64 = 512 * 1024 * 1024;
const MAX_SUPERVISOR_EBIN_FILES: usize = 4096;
const MAX_SUPERVISOR_EBIN_FILE_BYTES: u64 = 32 * 1024 * 1024;
const MAX_SUPERVISOR_EBIN_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_SERVICE_HELPER_OUTPUT_BYTES: usize = 64 * 1024;
const SERVICE_HELPER_STATUS_TIMEOUT: Duration = Duration::from_secs(10);
const SERVICE_HELPER_MUTATION_TIMEOUT: Duration = Duration::from_secs(5 * 60);

type ApiError = (StatusCode, String);
type ApiResult<T> = std::result::Result<Json<T>, ApiError>;

#[derive(Clone)]
struct AppState {
    inner: Arc<Mutex<DaemonState>>,
    token: Arc<String>,
    operator_token: Arc<String>,
    data_root: Arc<PathBuf>,
    client: reqwest::Client,
    ingress_url: Arc<String>,
    runtime_manifest_path: Option<Arc<PathBuf>>,
}

struct MaintenanceLease {
    inner: Arc<Mutex<DaemonState>>,
    active: bool,
}

impl MaintenanceLease {
    fn new(state: &AppState) -> Self {
        Self {
            inner: state.inner.clone(),
            active: true,
        }
    }
}

impl Drop for MaintenanceLease {
    fn drop(&mut self) {
        if !self.active {
            return;
        }
        match self.inner.lock() {
            Ok(mut daemon) => {
                daemon.maintenance_in_progress = false;
            }
            Err(_) => {
                // A poisoned daemon-state mutex is already fatal to subsequent
                // control operations; avoid panicking again from Drop.
            }
        }
    }
}

struct DaemonState {
    settings: Settings,
    supervisor_root: Option<PathBuf>,
    supervisor_ebin_sha256: Option<String>,
    runtime: Option<ManagedChild>,
    tunnel: Option<ManagedChild>,
    keep_awake: Option<ManagedChild>,
    last_runtime: Option<RuntimeRequest>,
    last_tunnel: Option<TunnelRequest>,
    known_dns_routes: Vec<DnsRoute>,
    pending_dns_routes: Vec<DnsRoute>,
    recent_idempotency: HashSet<String>,
    idempotency_order: VecDeque<String>,
    maintenance_in_progress: bool,
    runtime_restart: RestartBackoff,
    tunnel_restart: RestartBackoff,
    lifecycle_events: VecDeque<LifecycleEvent>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct DesiredState {
    runtime: Option<RuntimeRequest>,
    tunnel: Option<TunnelRequest>,
    known_dns_routes: Vec<DnsRoute>,
    pending_dns_routes: Vec<DnsRoute>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct ReplayState {
    keys: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct ServiceToolOverrides {
    daemon_binary: Option<PathBuf>,
    daemon_sha256: Option<String>,
    bmscl_binary: Option<PathBuf>,
    bmscl_sha256: Option<String>,
    cloudflared_binary: Option<PathBuf>,
    cloudflared_sha256: Option<String>,
    zed_binary: Option<PathBuf>,
    zed_sha256: Option<String>,
    supervisor_root: Option<PathBuf>,
    supervisor_ebin_sha256: Option<String>,
}

struct ManagedChild {
    child: Child,
    command: Vec<String>,
    started_at_unix_ms: u128,
}

#[derive(Debug, Default)]
struct RestartBackoff {
    failures: u32,
    next_attempt: Option<Instant>,
}

#[derive(Debug, Serialize)]
struct RestartView {
    failures: u32,
    retry_in_ms: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct Settings {
    keep_alive_during_lock: bool,
    update_root: Option<PathBuf>,
    bmscl_binary: String,
    cloudflared_binary: String,
    zed_binary: String,
    #[serde(skip)]
    bmscl_sha256: Option<String>,
    #[serde(skip)]
    cloudflared_sha256: Option<String>,
    #[serde(skip)]
    zed_sha256: Option<String>,
}

impl Default for Settings {
    fn default() -> Self {
        return Self {
            keep_alive_during_lock: false,
            update_root: None,
            bmscl_binary: "bmscl".into(),
            cloudflared_binary: "cloudflared".into(),
            zed_binary: "zed".into(),
            bmscl_sha256: None,
            cloudflared_sha256: None,
            zed_sha256: None,
        };
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct RuntimeRequest {
    project_dir: PathBuf,
    #[serde(default = "default_poll_ms")]
    poll_ms: u64,
    #[serde(default)]
    module: Option<String>,
}

fn default_poll_ms() -> u64 {
    return 250;
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct TunnelRequest {
    name: String,
    #[serde(default)]
    hostname: Option<String>,
    #[serde(default)]
    config: Option<PathBuf>,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct DnsRoute {
    tunnel: String,
    hostname: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct SettingsPatch {
    keep_alive_during_lock: Option<bool>,
    update_root: Option<PathBuf>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct OperatorSettingsPatch {
    update_root: Option<PathBuf>,
    #[serde(default)]
    clear_update_root: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ServiceInstallRequest {
    #[serde(default)]
    supervisor_root: Option<PathBuf>,
}

#[derive(Clone, Debug)]
struct PinnedServiceHelper {
    path: PathBuf,
    sha256: String,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ServiceStatusResponse {
    installed: bool,
    running: Option<bool>,
    manager: String,
    definition_path: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct DnsRouteResolution {
    name: String,
    hostname: String,
    applied: bool,
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    ok: bool,
    api: &'static str,
}

#[derive(Debug, Serialize)]
struct ProcessView {
    running: bool,
    pid: Option<u32>,
    command: Option<Vec<String>>,
    started_at_unix_ms: Option<u128>,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
struct PublicSettingsView {
    keep_alive_during_lock: bool,
    update_root_configured: bool,
}

impl From<&Settings> for PublicSettingsView {
    fn from(settings: &Settings) -> Self {
        return Self {
            keep_alive_during_lock: settings.keep_alive_during_lock,
            update_root_configured: settings.update_root.is_some(),
        };
    }
}

#[derive(Debug, Serialize)]
struct StatusResponse {
    api: &'static str,
    daemon_version: &'static str,
    runtime: ProcessView,
    runtime_desired: bool,
    tunnel: ProcessView,
    tunnel_desired: bool,
    keep_awake: ProcessView,
    maintenance_in_progress: bool,
    runtime_restart: RestartView,
    tunnel_restart: RestartView,
    settings: PublicSettingsView,
    known_dns_routes: Vec<DnsRoute>,
    pending_dns_routes: Vec<DnsRoute>,
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
struct LifecycleEvent {
    sequence: u64,
    at_unix_ms: u128,
    component: &'static str,
    action: &'static str,
    outcome: &'static str,
    detail: String,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct LifecycleEventsQuery {
    since_sequence: Option<u64>,
    limit: Option<usize>,
}

#[derive(Debug, Serialize)]
struct LifecycleEventsResponse {
    api: &'static str,
    events: Vec<LifecycleEvent>,
    oldest_sequence: u64,
    latest_sequence: u64,
    gap_detected: bool,
}

#[derive(Debug, Serialize)]
struct ActionResponse {
    ok: bool,
    message: String,
}

#[derive(Debug, Serialize)]
struct DoctorCheck {
    name: &'static str,
    ok: bool,
    detail: String,
}

#[derive(Debug, Serialize)]
struct DoctorResponse {
    ok: bool,
    checks: Vec<DoctorCheck>,
}

#[derive(Debug, Serialize)]
struct OperatorAuthorityResponse {
    api: &'static str,
    authority: &'static str,
    agent_authentication: &'static str,
}

#[derive(Debug, Serialize)]
struct UpdateStatusResponse {
    bmscl: VersionProbe,
    cloudflared: VersionProbe,
    zed: VersionProbe,
    zed_self_update_check: VersionProbe,
}

#[derive(Debug, Serialize)]
struct VersionProbe {
    ok: bool,
    output: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let data_root = data_root()?;
    fs::create_dir_all(&data_root).with_context(|| format!("create {}", data_root.display()))?;
    secure_data_root(&data_root)?;
    let token = load_or_create_token(&data_root)?;
    let operator_token = load_or_create_operator_token(&data_root)?;
    if constant_time_eq(token.as_bytes(), operator_token.as_bytes()) {
        bail!("normal and operator daemon credentials must be distinct");
    }
    let mut settings = load_settings(&data_root)?;
    let runtime_manifest_path = runtime_manifest::configured_manifest_path(&data_root)?;
    if let Some(path) = runtime_manifest_path.as_deref() {
        let validated = runtime_manifest::load_and_validate(path)
            .with_context(|| format!("validate desktop runtime manifest {}", path.display()))?;
        info!(
            manifest = %path.display(),
            release_id = %validated.release_id,
            executable = %validated.executable.display(),
            sha256 = %validated.sha256,
            "validated digest-pinned desktop runtime manifest"
        );
    }
    let supervisor_pin = apply_service_tool_overrides(&data_root, &mut settings)?;
    let (supervisor_root, supervisor_ebin_sha256) = match supervisor_pin {
        Some((root, digest)) => (Some(root), Some(digest)),
        None => (None, None),
    };
    let desired = load_desired_state(&data_root)?;
    let replay = load_replay_state(&data_root)?;
    let (recent_idempotency, idempotency_order) = replay_collections(replay)?;
    let ingress_raw =
        env::var("BMSCL_LOCAL_INGRESS_URL").unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let ingress_url = validate_loopback_http_origin(&ingress_raw)?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .context("build local BeamScale ingress HTTP client")?;

    let state = AppState {
        inner: Arc::new(Mutex::new(DaemonState {
            settings,
            supervisor_root,
            supervisor_ebin_sha256,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: desired.runtime,
            last_tunnel: desired.tunnel,
            known_dns_routes: desired.known_dns_routes,
            pending_dns_routes: desired.pending_dns_routes,
            recent_idempotency,
            idempotency_order,
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        })),
        token: Arc::new(token),
        operator_token: Arc::new(operator_token),
        data_root: Arc::new(data_root.clone()),
        client,
        ingress_url: Arc::new(ingress_url),
        runtime_manifest_path: runtime_manifest_path.map(Arc::new),
    };

    {
        let mut daemon = lock_state(&state)?;
        if let Some(request) = daemon.last_runtime.clone() {
            if let Err(error) = start_runtime(&mut daemon, request) {
                warn!(%error, "failed to restore desired BeamScale runtime");
            }
        }
        let runtime_running = slot_running(&mut daemon.runtime);
        if let Some(request) = daemon.last_tunnel.clone() {
            if !runtime_running {
                warn!(
                    "skipping desired Cloudflare tunnel restore because BeamScale runtime is not running"
                );
            } else if pending_dns_route_for_request(&daemon, &request) {
                warn!(
                    "skipping desired Cloudflare tunnel restore because its DNS route requires reconciliation"
                );
            } else if let Err(error) =
                start_tunnel_process(&mut daemon, state.ingress_url.as_str(), request)
            {
                warn!(%error, "failed to restore desired Cloudflare tunnel");
            }
        }
        if let Err(error) = apply_keep_awake(&mut daemon) {
            warn!(%error, "failed to restore keep-awake policy");
        }
    }

    let watchdog = tokio::spawn(desired_state_watchdog(state.clone()));

    let app = Router::new()
        .route("/health", get(health))
        .route("/healthz", get(health))
        .route("/v1/status", get(status))
        .route("/v1/events", get(lifecycle_events))
        .route("/v1/settings", put(update_settings))
        .route("/v1/runtime/start", post(runtime_start))
        .route("/v1/runtime/stop", post(runtime_stop))
        .route("/v1/runtime/restart", post(runtime_restart))
        .route("/v1/tunnel/start", post(tunnel_start))
        .route("/v1/tunnel/stop", post(tunnel_stop))
        .route("/v1/tunnel/restart", post(tunnel_restart))
        .route("/v1/tunnel/dns/resolve", post(resolve_tunnel_dns))
        .route("/v1/update/status", get(update_status))
        .route("/v1/update/apply", post(update_apply))
        .route("/v1/doctor", get(doctor))
        .route("/v1/internal/authority", get(operator_authority))
        .route("/v1/internal/settings", put(update_operator_settings))
        .route("/v1/internal/service/status", get(service_status))
        .route("/v1/internal/service/install", post(service_install))
        .route("/v1/internal/service/uninstall", post(service_uninstall))
        .layer(DefaultBodyLimit::max(1024 * 1024))
        .with_state(state.clone());

    let listen_raw = env::var("BMSCL_DAEMON_LISTEN")
        .or_else(|_| env::var("BMSCL_DESKTOP_ADDR"))
        .unwrap_or_else(|_| DEFAULT_LISTEN.into());
    let listen = listen_raw
        .parse::<SocketAddr>()
        .with_context(|| format!("parse BMSCL_DAEMON_LISTEN={listen_raw}"))?;
    if !listen.ip().is_loopback() {
        bail!("BMSCL_DAEMON_LISTEN must bind to loopback; refusing {listen}");
    }

    let listener = tokio::net::TcpListener::bind(listen)
        .await
        .with_context(|| format!("bind BeamScale desktop daemon to {listen}"))?;
    info!(%listen, data_root = %data_root.display(), "BeamScale desktop daemon started");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("serve BeamScale desktop daemon")?;

    watchdog.abort();

    let mut daemon = lock_state(&state)?;
    stop_slot(&mut daemon.tunnel, "Cloudflare tunnel")?;
    stop_slot(&mut daemon.runtime, "BeamScale runtime")?;
    stop_slot(&mut daemon.keep_awake, "keep-awake inhibitor")?;
    return Ok(());
}

#[cfg(unix)]
async fn shutdown_signal() {
    use tokio::signal::unix::{SignalKind, signal};

    let mut terminate = match signal(SignalKind::terminate()) {
        Ok(signal) => signal,
        Err(error) => {
            error!(%error, "failed to install SIGTERM handler");
            if let Err(error) = tokio::signal::ctrl_c().await {
                error!(%error, "failed to install ctrl-c handler");
            }
            return;
        }
    };

    tokio::select! {
        result = tokio::signal::ctrl_c() => {
            if let Err(error) = result {
                error!(%error, "failed to receive ctrl-c");
            }
        }
        _ = terminate.recv() => {}
    }
}

#[cfg(not(unix))]
async fn shutdown_signal() {
    if let Err(error) = tokio::signal::ctrl_c().await {
        error!(%error, "failed to install ctrl-c handler");
    }
}

async fn health() -> impl IntoResponse {
    return Json(HealthResponse {
        ok: true,
        api: API_VERSION,
    });
}

async fn operator_authority(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<OperatorAuthorityResponse> {
    require_operator_auth(&headers, &state)?;
    return Ok(Json(OperatorAuthorityResponse {
        api: API_VERSION,
        authority: "operator",
        agent_authentication: "not-configured",
    }));
}

async fn update_operator_settings(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(patch): Json<OperatorSettingsPatch>,
) -> ApiResult<ActionResponse> {
    require_operator_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let update_root = resolve_operator_update_root(patch).map_err(bad_request)?;

    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    reserve_idempotency(&mut daemon, &state.data_root, idempotency).map_err(internal_error)?;

    let mut proposed_settings = daemon.settings.clone();
    proposed_settings.update_root = update_root;
    save_settings(&state.data_root, &proposed_settings).map_err(internal_error)?;
    daemon.settings = proposed_settings;

    return Ok(Json(ActionResponse {
        ok: true,
        message: "operator settings updated".into(),
    }));
}

async fn service_status(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ServiceStatusResponse> {
    require_operator_auth(&headers, &state)?;
    let helper = pin_service_helper().map_err(internal_error)?;
    let response = run_service_helper_async(
        helper,
        vec!["status".into(), "--json".into()],
        SERVICE_HELPER_STATUS_TIMEOUT,
    )
    .await
    .map_err(internal_error)?;
    if !response.0.success() {
        return Err(internal_error(format!(
            "beamscale-service status failed with {}: {}",
            response.0,
            String::from_utf8_lossy(&response.2).trim()
        )));
    }
    let status: ServiceStatusResponse = serde_json::from_slice(&response.1)
        .map_err(|error| internal_error(format!("parse beamscale-service status JSON: {error}")))?;
    validate_service_status_response(&status).map_err(internal_error)?;
    return Ok(Json(status));
}

async fn service_install(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<ServiceInstallRequest>,
) -> ApiResult<ActionResponse> {
    require_operator_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;

    let configured_supervisor_root = {
        let daemon = lock_state_api(&state)?;
        daemon.supervisor_root.clone()
    };
    let supervisor_root = match request.supervisor_root {
        Some(path) => Some(validate_service_supervisor_root(&path).map_err(bad_request)?),
        None => configured_supervisor_root,
    };

    // Complete deterministic validation before reserving the mutation key.
    // Once reserved, retries are intentionally at-most-once.
    let daemon_binary = current_daemon_executable().map_err(internal_error)?;
    let helper = pin_service_helper().map_err(internal_error)?;
    let mut args = vec![
        "install".to_string(),
        "--binary".to_string(),
        path_to_service_arg(&daemon_binary).map_err(internal_error)?,
        // The daemon already owns the loopback control port. Register future
        // persistence without starting a competing daemon in this session.
        "--no-start".to_string(),
    ];

    #[cfg(not(target_os = "windows"))]
    {
        args.push("--data-root".into());
        args.push(path_to_service_arg(state.data_root.as_path()).map_err(internal_error)?);
    }

    #[cfg(target_os = "windows")]
    {
        let default_root = default_data_root().map_err(internal_error)?;
        let configured_root = fs::canonicalize(state.data_root.as_path())
            .with_context(|| format!("canonicalize state root {}", state.data_root.display()))
            .map_err(internal_error)?;
        let default_root = fs::canonicalize(&default_root)
            .with_context(|| format!("canonicalize default state root {}", default_root.display()))
            .map_err(internal_error)?;
        if configured_root != default_root {
            return Err(bad_request(
                "persistent Windows service install requires the default BeamScale state root",
            ));
        }
    }

    if let Some(root) = supervisor_root {
        args.push("--supervisor-root".into());
        args.push(path_to_service_arg(&root).map_err(internal_error)?);
    }

    {
        let mut daemon = lock_state_api(&state)?;
        reject_maintenance(&daemon)?;
        reject_replayed_idempotency(&daemon, &idempotency)?;
        reserve_idempotency(&mut daemon, &state.data_root, idempotency).map_err(internal_error)?;
        daemon.maintenance_in_progress = true;
    }
    let maintenance = MaintenanceLease::new(&state);

    // Registration can outlive the client transport. The maintenance lease is
    // owned by the blocking mutation itself, not this request future, so an
    // aborted/disconnected request cannot permit a concurrent mutation.
    let (status, _stdout, stderr) =
        run_service_mutation_async(helper, args, SERVICE_HELPER_MUTATION_TIMEOUT, maintenance)
            .await
            .map_err(internal_error)?;
    if !status.success() {
        return Err(internal_error(format!(
            "beamscale-service install failed with {status}: {}",
            String::from_utf8_lossy(&stderr).trim()
        )));
    }

    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale desktop service registered; current daemon remains authoritative until it exits".into(),
    }));
}

async fn service_uninstall(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_operator_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;

    // Content-pin the packaged helper before consuming the replay key.
    let helper = pin_service_helper().map_err(internal_error)?;
    {
        let mut daemon = lock_state_api(&state)?;
        reject_maintenance(&daemon)?;
        reject_replayed_idempotency(&daemon, &idempotency)?;
        reserve_idempotency(&mut daemon, &state.data_root, idempotency).map_err(internal_error)?;
        daemon.maintenance_in_progress = true;
    }
    let maintenance = MaintenanceLease::new(&state);

    let (status, _stdout, stderr) = run_service_mutation_async(
        helper,
        vec!["uninstall".into()],
        SERVICE_HELPER_MUTATION_TIMEOUT,
        maintenance,
    )
    .await
    .map_err(internal_error)?;
    if !status.success() {
        return Err(internal_error(format!(
            "beamscale-service uninstall failed with {status}: {}",
            String::from_utf8_lossy(&stderr).trim()
        )));
    }

    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale desktop service uninstalled; daemon state preserved".into(),
    }));
}

fn validate_service_status_response(status: &ServiceStatusResponse) -> Result<()> {
    if !matches!(
        status.manager.as_str(),
        "systemd-user" | "launchd-user" | "scheduled-task"
    ) {
        bail!("beamscale-service returned an unknown service manager");
    }
    if status.definition_path.is_empty()
        || status.definition_path.len() > 4096
        || status
            .definition_path
            .chars()
            .any(|ch| matches!(ch, '\n' | '\r' | '\0'))
    {
        bail!("beamscale-service returned an invalid definition path");
    }
    if !status.installed && status.running.is_some() {
        bail!("beamscale-service returned running state for an uninstalled service");
    }
    Ok(())
}

fn current_daemon_executable() -> Result<PathBuf> {
    let path = env::current_exe().context("resolve current BeamScale desktop daemon executable")?;
    let metadata = fs::symlink_metadata(&path)
        .with_context(|| format!("inspect current daemon executable {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!("current BeamScale daemon executable must be a regular non-symlink file");
    }
    fs::canonicalize(&path)
        .with_context(|| format!("canonicalize current daemon {}", path.display()))
}

fn service_helper_executable() -> Result<PathBuf> {
    let daemon = current_daemon_executable()?;
    let parent = daemon
        .parent()
        .context("current daemon executable has no parent directory")?;
    let name = if cfg!(target_os = "windows") {
        "beamscale-service.exe"
    } else {
        "beamscale-service"
    };
    let candidate = parent.join(name);
    let metadata = fs::symlink_metadata(&candidate)
        .with_context(|| format!("inspect packaged service helper {}", candidate.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "packaged service helper must be a regular non-symlink file beside the daemon: {}",
            candidate.display()
        );
    }
    if metadata.len() > MAX_PINNED_TOOL_BYTES {
        bail!(
            "packaged service helper exceeds {} bytes: {}",
            MAX_PINNED_TOOL_BYTES,
            candidate.display()
        );
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            bail!(
                "packaged service helper is not executable: {}",
                candidate.display()
            );
        }
    }
    fs::canonicalize(&candidate).with_context(|| {
        format!(
            "canonicalize packaged service helper {}",
            candidate.display()
        )
    })
}

fn path_to_service_arg(path: &Path) -> Result<String> {
    let text = path
        .to_str()
        .context("service-manager paths must be valid UTF-8")?;
    if text.is_empty() || text.chars().any(|ch| matches!(ch, '\n' | '\r' | '\0')) {
        bail!("service-manager path contains unsupported control characters");
    }
    Ok(text.to_string())
}

#[cfg(target_os = "windows")]
fn default_data_root() -> Result<PathBuf> {
    if let Ok(home) = env::var("HOME") {
        if !home.trim().is_empty() {
            return Ok(PathBuf::from(home)
                .join(".beamscale")
                .join("desktop-daemon"));
        }
    }
    if let Ok(home) = env::var("USERPROFILE") {
        if !home.trim().is_empty() {
            return Ok(PathBuf::from(home)
                .join(".beamscale")
                .join("desktop-daemon"));
        }
    }
    bail!("cannot locate default home directory")
}

fn pin_service_helper() -> Result<PinnedServiceHelper> {
    let path = service_helper_executable()?;
    let sha256 = executable_sha256(&path, "beamscale-service")?;
    Ok(PinnedServiceHelper { path, sha256 })
}

async fn run_service_helper_async(
    helper: PinnedServiceHelper,
    args: Vec<String>,
    timeout: Duration,
) -> Result<(ExitStatus, Vec<u8>, Vec<u8>)> {
    tokio::task::spawn_blocking(move || run_service_helper(&helper, &args, timeout))
        .await
        .context("join packaged beamscale-service helper task")?
}

async fn run_service_mutation_async(
    helper: PinnedServiceHelper,
    args: Vec<String>,
    timeout: Duration,
    maintenance: MaintenanceLease,
) -> Result<(ExitStatus, Vec<u8>, Vec<u8>)> {
    // spawn_blocking tasks continue running even if the awaiting HTTP future
    // is canceled. Move the maintenance lease into the blocking closure so a
    // client disconnect cannot reopen the mutation gate while the OS service
    // manager is still changing persistent state.
    tokio::task::spawn_blocking(move || {
        let _maintenance = maintenance;
        run_service_helper(&helper, &args, timeout)
    })
    .await
    .context("join packaged beamscale-service mutation task")?
}

fn run_service_helper(
    helper: &PinnedServiceHelper,
    args: &[String],
    timeout: Duration,
) -> Result<(ExitStatus, Vec<u8>, Vec<u8>)> {
    let before = executable_sha256(&helper.path, "beamscale-service")?;
    if before != helper.sha256 {
        bail!(
            "packaged beamscale-service changed after validation; expected {}, got {}",
            helper.sha256,
            before
        );
    }

    let helper_text = path_to_service_arg(&helper.path)?;
    let arg_refs = args.iter().map(String::as_str).collect::<Vec<_>>();
    let result = run_probe_bounded_with_extra_scrub(
        &helper_text,
        &arg_refs,
        timeout,
        MAX_SERVICE_HELPER_OUTPUT_BYTES,
        &[
            "BMSCL_ADMIN_TOKEN",
            "CLOUDFLARE_API_TOKEN",
            "CLOUDFLARE_API_KEY",
            "CF_API_TOKEN",
            "CF_API_KEY",
        ],
    )
    .context("run packaged beamscale-service helper")?;

    let after = executable_sha256(&helper.path, "beamscale-service")?;
    if after != helper.sha256 {
        bail!(
            "packaged beamscale-service changed during execution; expected {}, got {}",
            helper.sha256,
            after
        );
    }

    Ok(result)
}

fn resolve_operator_update_root(patch: OperatorSettingsPatch) -> Result<Option<PathBuf>> {
    if patch.update_root.is_some() == patch.clear_update_root {
        bail!(
            "operator settings must specify exactly one of update_root or clear_update_root=true"
        );
    }

    if let Some(value) = patch.update_root {
        if !value.is_dir() {
            bail!(
                "update_root must be an existing directory: {}",
                value.display()
            );
        }
        let resolved = value
            .canonicalize()
            .with_context(|| format!("resolve update_root {}", value.display()))?;
        return Ok(Some(resolved));
    }

    return Ok(None);
}

async fn status(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<StatusResponse> {
    require_auth(&headers, &state)?;
    let mut daemon = lock_state_api(&state)?;
    let runtime_desired = daemon.last_runtime.is_some();
    let tunnel_desired = daemon.last_tunnel.is_some();
    let runtime = process_view(&mut daemon.runtime);
    let tunnel = process_view(&mut daemon.tunnel);
    let keep_awake = process_view(&mut daemon.keep_awake);

    return Ok(Json(StatusResponse {
        api: API_VERSION,
        daemon_version: env!("CARGO_PKG_VERSION"),
        runtime,
        runtime_desired,
        tunnel,
        tunnel_desired,
        keep_awake,
        maintenance_in_progress: daemon.maintenance_in_progress,
        runtime_restart: daemon.runtime_restart.view(),
        tunnel_restart: daemon.tunnel_restart.view(),
        settings: PublicSettingsView::from(&daemon.settings),
        known_dns_routes: daemon.known_dns_routes.clone(),
        pending_dns_routes: daemon.pending_dns_routes.clone(),
    }));
}

async fn lifecycle_events(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<LifecycleEventsQuery>,
) -> ApiResult<LifecycleEventsResponse> {
    require_auth(&headers, &state)?;
    let limit = query.limit.unwrap_or(100).clamp(1, MAX_LIFECYCLE_EVENTS);
    let since = query.since_sequence.unwrap_or_default();
    let daemon = lock_state_api(&state)?;
    let oldest_sequence = daemon
        .lifecycle_events
        .front()
        .map(|event| event.sequence)
        .unwrap_or_default();
    let latest_sequence = daemon
        .lifecycle_events
        .back()
        .map(|event| event.sequence)
        .unwrap_or_default();
    let gap_detected = since > 0
        && (since > latest_sequence
            || (oldest_sequence > 0 && since.saturating_add(1) < oldest_sequence));
    let events = daemon
        .lifecycle_events
        .iter()
        .filter(|event| event.sequence > since)
        .take(limit)
        .cloned()
        .collect();
    return Ok(Json(LifecycleEventsResponse {
        api: API_VERSION,
        events,
        oldest_sequence,
        latest_sequence,
        gap_detected,
    }));
}

async fn update_settings(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(patch): Json<SettingsPatch>,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;

    if patch.update_root.is_some() {
        return Err((
            StatusCode::FORBIDDEN,
            "update_root is operator-only; use /v1/internal/settings with the operator credential"
                .into(),
        ));
    }

    let previous_settings = daemon.settings.clone();
    let mut proposed_settings = previous_settings.clone();
    if let Some(value) = patch.keep_alive_during_lock {
        proposed_settings.keep_alive_during_lock = value;
    }

    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    save_settings(&state.data_root, &proposed_settings).map_err(internal_error)?;
    daemon.settings = proposed_settings;

    if let Err(error) = apply_keep_awake(&mut daemon) {
        daemon.settings = previous_settings.clone();
        if let Err(rollback_error) = save_settings(&state.data_root, &previous_settings) {
            error!(%rollback_error, "failed to roll back settings after keep-awake failure");
        }
        if let Err(rollback_error) = apply_keep_awake(&mut daemon) {
            error!(%rollback_error, "failed to restore keep-awake state after settings rollback");
        }
        return Err(internal_error(error));
    }

    return Ok(Json(ActionResponse {
        ok: true,
        message: "settings updated".into(),
    }));
}

async fn runtime_start(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<RuntimeRequest>,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    validate_runtime_request(&request).map_err(bad_request)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    start_runtime(&mut daemon, request).map_err(internal_error)?;
    if let Err(error) = save_desired_state(&state.data_root, &daemon) {
        let _ = stop_slot(&mut daemon.runtime, "BeamScale runtime");
        daemon.last_runtime = None;
        return Err(internal_error(error));
    }
    if let Err(error) = apply_keep_awake(&mut daemon) {
        warn!(%error, "runtime started but keep-awake inhibitor could not be reconciled");
    }
    record_lifecycle_event(
        &mut daemon,
        "runtime",
        "start",
        "ok",
        "desired runtime started",
    );
    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale local runtime started".into(),
    }));
}

async fn runtime_stop(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    daemon.last_runtime = None;
    save_desired_state(&state.data_root, &daemon).map_err(internal_error)?;
    stop_slot(&mut daemon.tunnel, "Cloudflare tunnel").map_err(internal_error)?;
    stop_slot(&mut daemon.runtime, "BeamScale runtime").map_err(internal_error)?;
    apply_keep_awake(&mut daemon).map_err(internal_error)?;
    record_lifecycle_event(
        &mut daemon,
        "runtime",
        "stop",
        "ok",
        "desired runtime and public tunnel stopped",
    );

    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale local runtime stopped".into(),
    }));
}

async fn runtime_restart(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    let request = daemon
        .last_runtime
        .clone()
        .ok_or_else(|| bad_request("runtime has not been started yet"))?;
    let tunnel_request = daemon.last_tunnel.clone();
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    stop_slot(&mut daemon.tunnel, "Cloudflare tunnel").map_err(internal_error)?;
    start_runtime(&mut daemon, request).map_err(internal_error)?;
    if let Some(tunnel_request) = tunnel_request {
        if !pending_dns_route_for_request(&daemon, &tunnel_request) {
            start_tunnel_process(&mut daemon, state.ingress_url.as_str(), tunnel_request)
                .map_err(internal_error)?;
        }
    }
    record_lifecycle_event(
        &mut daemon,
        "runtime",
        "restart",
        "ok",
        "runtime restarted; eligible public tunnel restored",
    );

    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale local runtime restarted".into(),
    }));
}

async fn tunnel_start(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<TunnelRequest>,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    validate_tunnel_request(&request).map_err(bad_request)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    if !slot_running(&mut daemon.runtime) {
        return Err((
            StatusCode::SERVICE_UNAVAILABLE,
            "BeamScale runtime must be running before public exposure".into(),
        ));
    }
    if pending_dns_route_for_request(&daemon, &request) {
        return Err((
            StatusCode::CONFLICT,
            "Cloudflare DNS route outcome is uncertain; reconcile it before retrying exposure"
                .into(),
        ));
    }
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    let _ = route_tunnel_dns(&mut daemon, &state.data_root, &request).map_err(internal_error)?;
    start_tunnel_process(&mut daemon, state.ingress_url.as_str(), request)
        .map_err(internal_error)?;
    if let Err(error) = save_desired_state(&state.data_root, &daemon) {
        let _ = stop_slot(&mut daemon.tunnel, "Cloudflare tunnel");
        daemon.last_tunnel = None;
        return Err(internal_error(error));
    }
    record_lifecycle_event(
        &mut daemon,
        "tunnel",
        "start",
        "ok",
        "public tunnel started",
    );
    return Ok(Json(ActionResponse {
        ok: true,
        message: "Cloudflare tunnel started".into(),
    }));
}

async fn tunnel_stop(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    daemon.last_tunnel = None;
    save_desired_state(&state.data_root, &daemon).map_err(internal_error)?;
    stop_slot(&mut daemon.tunnel, "Cloudflare tunnel").map_err(internal_error)?;
    record_lifecycle_event(&mut daemon, "tunnel", "stop", "ok", "public tunnel stopped");

    return Ok(Json(ActionResponse {
        ok: true,
        message: "Cloudflare tunnel stopped".into(),
    }));
}

async fn tunnel_restart(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;
    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    if !slot_running(&mut daemon.runtime) {
        return Err((
            StatusCode::SERVICE_UNAVAILABLE,
            "BeamScale runtime must be running before public exposure".into(),
        ));
    }
    let request = daemon
        .last_tunnel
        .clone()
        .ok_or_else(|| bad_request("tunnel has not been started yet"))?;
    if pending_dns_route_for_request(&daemon, &request) {
        return Err((
            StatusCode::CONFLICT,
            "Cloudflare DNS route outcome is uncertain; reconcile it before restarting exposure"
                .into(),
        ));
    }
    reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
        .map_err(internal_error)?;
    start_tunnel_process(&mut daemon, state.ingress_url.as_str(), request)
        .map_err(internal_error)?;
    record_lifecycle_event(
        &mut daemon,
        "tunnel",
        "restart",
        "ok",
        "public tunnel restarted without DNS mutation",
    );

    return Ok(Json(ActionResponse {
        ok: true,
        message: "Cloudflare tunnel restarted without changing DNS".into(),
    }));
}

async fn resolve_tunnel_dns(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(resolution): Json<DnsRouteResolution>,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    let idempotency = idempotency_key(&headers)?;

    let request = TunnelRequest {
        name: resolution.name.clone(),
        hostname: Some(resolution.hostname.clone()),
        config: None,
    };
    validate_tunnel_request(&request).map_err(bad_request)?;
    let route = dns_route_for_request(&request)
        .ok_or_else(|| bad_request("hostname is required for DNS route reconciliation"))?;

    let mut daemon = lock_state_api(&state)?;
    reject_maintenance(&daemon)?;
    reject_replayed_idempotency(&daemon, &idempotency)?;
    if !daemon.pending_dns_routes.contains(&route) {
        return Err(bad_request(
            "DNS route is not pending reconciliation; refusing to alter confirmed route state",
        ));
    }

    reserve_idempotency(&mut daemon, &state.data_root, idempotency).map_err(internal_error)?;

    let previous_pending = daemon.pending_dns_routes.clone();
    let previous_known = daemon.known_dns_routes.clone();
    daemon
        .pending_dns_routes
        .retain(|candidate| candidate != &route);
    if resolution.applied && !daemon.known_dns_routes.contains(&route) {
        daemon.known_dns_routes.push(route.clone());
    }
    if let Err(error) = save_desired_state(&state.data_root, &daemon) {
        daemon.pending_dns_routes = previous_pending;
        daemon.known_dns_routes = previous_known;
        return Err(internal_error(error));
    }

    let message = if resolution.applied {
        "Cloudflare DNS route marked confirmed; future starts will not recreate it"
    } else {
        "Cloudflare DNS route marked absent; next explicit start may create it again"
    };
    return Ok(Json(ActionResponse {
        ok: true,
        message: message.into(),
    }));
}

async fn doctor(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<DoctorResponse> {
    require_auth(&headers, &state)?;

    let (
        settings,
        runtime_running,
        tunnel_running,
        keep_awake_running,
        desired_runtime,
        desired_tunnel,
        pending_dns_routes,
        supervisor_root,
        supervisor_ebin_digest,
    ) = {
        let mut daemon = lock_state_api(&state)?;
        (
            daemon.settings.clone(),
            slot_running(&mut daemon.runtime),
            slot_running(&mut daemon.tunnel),
            slot_running(&mut daemon.keep_awake),
            daemon.last_runtime.clone(),
            daemon.last_tunnel.clone(),
            daemon.pending_dns_routes.clone(),
            daemon.supervisor_root.clone(),
            daemon.supervisor_ebin_sha256.clone(),
        )
    };

    let mut checks = Vec::new();

    let bmscl = probe_pinned(
        &settings.bmscl_binary,
        settings.bmscl_sha256.as_deref(),
        "bmscl",
        &["--version"],
    );
    checks.push(DoctorCheck {
        name: "bmscl_binary",
        ok: bmscl.ok,
        detail: bmscl.output,
    });

    let cloudflared = probe_pinned(
        &settings.cloudflared_binary,
        settings.cloudflared_sha256.as_deref(),
        "cloudflared",
        &["--version"],
    );
    checks.push(DoctorCheck {
        name: "cloudflared_binary",
        ok: cloudflared.ok,
        detail: cloudflared.output,
    });

    let zed = probe_pinned(
        &settings.zed_binary,
        settings.zed_sha256.as_deref(),
        "zed",
        &["--version"],
    );
    checks.push(DoctorCheck {
        name: "zed_binary",
        ok: zed.ok,
        detail: zed.output,
    });

    checks.push(DoctorCheck {
        name: "state_root",
        ok: state.data_root.is_dir(),
        detail: state.data_root.display().to_string(),
    });

    checks.push(match state.runtime_manifest_path.as_deref() {
        Some(path) => match runtime_manifest::load_and_validate(path) {
            Ok(manifest) => DoctorCheck {
                name: "runtime_manifest",
                ok: true,
                detail: format!(
                    "validated release={}; executable={}; sha256={}",
                    manifest.release_id,
                    manifest.executable.display(),
                    manifest.sha256
                ),
            },
            Err(error) => DoctorCheck {
                name: "runtime_manifest",
                ok: false,
                detail: format!("{}: {error:#}", path.display()),
            },
        },
        None => DoctorCheck {
            name: "runtime_manifest",
            ok: true,
            detail: "not configured; daemon remains in developer bmscl-dev runtime mode".into(),
        },
    });

    let service_mode = service_tools_path(&state.data_root).is_file();
    if service_mode {
        checks.extend(doctor_service_tool_integrity_checks(&state.data_root));
    }
    checks.push(match (supervisor_root, supervisor_ebin_digest) {
        (Some(root), Some(expected)) => match supervisor_ebin_sha256(&root) {
            Ok(actual) if actual == expected => DoctorCheck {
                name: "supervisor_root",
                ok: true,
                detail: format!(
                    "content-pinned compiled supervisor root={}; sha256={}",
                    root.display(),
                    actual
                ),
            },
            Ok(actual) => DoctorCheck {
                name: "supervisor_root",
                ok: false,
                detail: format!(
                    "compiled supervisor content drifted at {}; expected sha256={}, actual sha256={}",
                    root.display(),
                    expected,
                    actual
                ),
            },
            Err(error) => DoctorCheck {
                name: "supervisor_root",
                ok: false,
                detail: format!("cannot revalidate compiled supervisor {}: {error:#}", root.display()),
            },
        },
        (Some(root), None) => DoctorCheck {
            name: "supervisor_root",
            ok: false,
            detail: format!(
                "persistent supervisor root {} has no content digest; reinstall service",
                root.display()
            ),
        },
        (None, Some(_)) => DoctorCheck {
            name: "supervisor_root",
            ok: false,
            detail: "supervisor content digest exists without a supervisor root".into(),
        },
        (None, None) if service_mode => DoctorCheck {
            name: "supervisor_root",
            ok: false,
            detail: "persistent service mode has no pinned supervisor root; reinstall with --supervisor-root for reboot-safe runtime restoration".into(),
        },
        (None, None) => DoctorCheck {
            name: "supervisor_root",
            ok: true,
            detail: "not pinned outside persistent service mode".into(),
        },
    });

    checks.push(DoctorCheck {
        name: "dns_route_journal",
        ok: pending_dns_routes.is_empty(),
        detail: if pending_dns_routes.is_empty() {
            "no uncertain Cloudflare DNS mutations".into()
        } else {
            format!(
                "{} Cloudflare DNS route mutation(s) require explicit reconciliation",
                pending_dns_routes.len()
            )
        },
    });

    checks.push(match desired_runtime {
        Some(request) => DoctorCheck {
            name: "runtime",
            ok: runtime_running && request.project_dir.is_dir(),
            detail: format!(
                "{}; project={}",
                if runtime_running {
                    "running"
                } else {
                    "desired but stopped"
                },
                request.project_dir.display()
            ),
        },
        None => DoctorCheck {
            name: "runtime",
            ok: true,
            detail: "stopped by user intent".into(),
        },
    });

    checks.push(match desired_tunnel {
        Some(request) => {
            let origin_pinned = request.config.is_none();
            DoctorCheck {
                name: "tunnel",
                ok: tunnel_running && origin_pinned,
                detail: if origin_pinned {
                    format!(
                        "{}; name={}; origin={}",
                        if tunnel_running { "running" } else { "desired but stopped" },
                        request.name,
                        state.ingress_url
                    )
                } else {
                    format!(
                        "desired tunnel {} uses a legacy custom config; clear and re-expose it so BeamScale can pin the same-BEAM origin",
                        request.name
                    )
                },
            }
        }
        None => DoctorCheck {
            name: "tunnel",
            ok: true,
            detail: "stopped by user intent".into(),
        },
    });

    checks.push(DoctorCheck {
        name: "keep_awake",
        ok: !settings.keep_alive_during_lock || keep_awake_running,
        detail: if settings.keep_alive_during_lock {
            if keep_awake_running {
                "enabled and inhibitor running".into()
            } else {
                "enabled but inhibitor is not running".into()
            }
        } else {
            "disabled".into()
        },
    });

    let ingress_check = if runtime_running {
        doctor_ingress_check(&state).await
    } else {
        DoctorCheck {
            name: "local_ingress",
            ok: true,
            detail: "not checked because runtime is stopped".into(),
        }
    };
    checks.push(ingress_check);

    let ok = checks.iter().all(|check| check.ok);
    return Ok(Json(DoctorResponse { ok, checks }));
}

async fn doctor_ingress_check(state: &AppState) -> DoctorCheck {
    let url = format!(
        "{}/__beamscale/healthz",
        state.ingress_url.trim_end_matches('/')
    );

    let result = tokio::time::timeout(Duration::from_millis(750), async {
        let response = state
            .client
            .get(&url)
            .send()
            .await
            .map_err(|error| format!("request failed: {error}"))?;

        if !response.status().is_success() {
            return Err(format!("health endpoint returned {}", response.status()));
        }

        let body = read_response_body_bounded(response, MAX_INGRESS_RESPONSE_BYTES)
            .await
            .map_err(|error| format!("read health response: {error}"))?;
        let value = serde_json::from_slice::<serde_json::Value>(&body)
            .map_err(|error| format!("invalid health JSON: {error}"))?;

        let ok = value.get("ok").and_then(serde_json::Value::as_bool) == Some(true);
        let service = value.get("service").and_then(serde_json::Value::as_str);

        if !ok || service != Some("beamscale-local-ingress") {
            return Err("unexpected local ingress health identity".into());
        }

        Ok(())
    })
    .await;

    match result {
        Ok(Ok(())) => DoctorCheck {
            name: "local_ingress",
            ok: true,
            detail: format!("verified same-BEAM health endpoint at {url}"),
        },
        Ok(Err(error)) => DoctorCheck {
            name: "local_ingress",
            ok: false,
            detail: format!("{url}: {error}"),
        },
        Err(_) => DoctorCheck {
            name: "local_ingress",
            ok: false,
            detail: format!("{url}: health check timed out"),
        },
    }
}

async fn update_status(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<UpdateStatusResponse> {
    require_auth(&headers, &state)?;
    let settings = lock_state_api(&state)?.settings.clone();

    return Ok(Json(UpdateStatusResponse {
        bmscl: probe_pinned(
            &settings.bmscl_binary,
            settings.bmscl_sha256.as_deref(),
            "bmscl",
            &["--version"],
        ),
        cloudflared: probe_pinned(
            &settings.cloudflared_binary,
            settings.cloudflared_sha256.as_deref(),
            "cloudflared",
            &["--version"],
        ),
        zed: probe_pinned(
            &settings.zed_binary,
            settings.zed_sha256.as_deref(),
            "zed",
            &["--version"],
        ),
        zed_self_update_check: probe_pinned(
            &settings.zed_binary,
            settings.zed_sha256.as_deref(),
            "zed",
            &["self-update", "--check"],
        ),
    }));
}

async fn update_apply(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<ActionResponse> {
    require_auth(&headers, &state)?;
    reject_service_mode_self_update(&state.data_root)?;
    let idempotency = idempotency_key(&headers)?;

    let (settings, restart_runtime, restart_tunnel) = {
        let mut daemon = lock_state_api(&state)?;
        reject_maintenance(&daemon)?;
        reject_replayed_idempotency(&daemon, &idempotency)?;
        reserve_idempotency(&mut daemon, &state.data_root, idempotency.clone())
            .map_err(internal_error)?;

        // Enter maintenance before releasing the state lock so no new
        // lifecycle mutation can interleave with the drain/update/restore
        // transaction.
        daemon.maintenance_in_progress = true;

        let runtime_was_running = slot_running(&mut daemon.runtime);
        let tunnel_was_running = slot_running(&mut daemon.tunnel);
        let restart_runtime = if runtime_was_running {
            daemon.last_runtime.clone()
        } else {
            None
        };
        let restart_tunnel = if tunnel_was_running {
            daemon.last_tunnel.clone()
        } else {
            None
        };

        // Public exposure must never outlive its supervised origin. Drain the
        // tunnel first, then the runtime.
        if tunnel_was_running {
            if let Err(error) = stop_slot(&mut daemon.tunnel, "Cloudflare tunnel") {
                daemon.maintenance_in_progress = false;
                return Err(internal_error(error));
            }
        }
        if runtime_was_running {
            if let Err(error) = stop_slot(&mut daemon.runtime, "BeamScale runtime") {
                daemon.maintenance_in_progress = false;
                return Err(internal_error(error));
            }
        }
        if let Err(error) = apply_keep_awake(&mut daemon) {
            warn!(%error, "failed to suspend keep-awake inhibitor for update");
        }

        (daemon.settings.clone(), restart_runtime, restart_tunnel)
    };

    let update_result = apply_updates(&settings);

    let mut daemon = lock_state_api(&state)?;
    let runtime_restart_result = match restart_runtime {
        Some(request) => start_runtime(&mut daemon, request),
        None => Ok(()),
    };

    let tunnel_restart_result = if let Some(request) = restart_tunnel {
        if runtime_restart_result.is_err() {
            Err(anyhow::anyhow!(
                "Cloudflare tunnel was not restored because BeamScale runtime restart failed"
            ))
        } else if pending_dns_route_for_request(&daemon, &request) {
            Err(anyhow::anyhow!(
                "Cloudflare tunnel cannot be restored because its DNS route requires reconciliation"
            ))
        } else if !slot_running(&mut daemon.runtime) {
            Err(anyhow::anyhow!(
                "Cloudflare tunnel cannot be restored because BeamScale runtime is not running"
            ))
        } else {
            start_tunnel_process(&mut daemon, state.ingress_url.as_str(), request)
        }
    } else {
        Ok(())
    };

    daemon.maintenance_in_progress = false;

    if let Err(error) = apply_keep_awake(&mut daemon) {
        warn!(%error, "failed to reconcile keep-awake policy after update");
    }

    if let Err(update_error) = update_result {
        if let Err(runtime_error) = &runtime_restart_result {
            warn!(%runtime_error, "runtime restart after failed update also failed");
        }
        if let Err(tunnel_error) = &tunnel_restart_result {
            warn!(%tunnel_error, "tunnel restart after failed update also failed");
        }
        return Err(internal_error(update_error));
    }
    if let Err(runtime_error) = runtime_restart_result {
        if let Err(tunnel_error) = &tunnel_restart_result {
            warn!(%tunnel_error, "tunnel remained stopped after runtime restart failure");
        }
        return Err(internal_error(runtime_error));
    }
    if let Err(tunnel_error) = tunnel_restart_result {
        return Err(internal_error(tunnel_error));
    }

    return Ok(Json(ActionResponse {
        ok: true,
        message: "BeamScale desktop components updated".into(),
    }));
}

fn validate_runtime_request(request: &RuntimeRequest) -> Result<()> {
    if request.poll_ms == 0 {
        bail!("poll_ms must be greater than zero");
    }
    if !request.project_dir.is_dir() {
        bail!(
            "project_dir must be an existing directory: {}",
            request.project_dir.display()
        );
    }
    if request
        .module
        .as_deref()
        .is_some_and(|value| value.trim().is_empty())
    {
        bail!("module must not be empty when provided");
    }
    return Ok(());
}

fn validate_tunnel_request(request: &TunnelRequest) -> Result<()> {
    let name = request.name.as_str();
    let name_bytes = name.as_bytes();
    if name != name.trim()
        || name.is_empty()
        || name.len() > 128
        || !name_bytes.first().is_some_and(u8::is_ascii_alphanumeric)
        || !name_bytes.last().is_some_and(u8::is_ascii_alphanumeric)
        || !name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
    {
        bail!(
            "tunnel name must be 1..=128 ASCII letters, digits, '.', '_' or '-', start/end alphanumeric, and have no edge whitespace"
        );
    }

    if let Some(hostname) = &request.hostname {
        if !valid_dns_hostname(hostname) {
            bail!(
                "hostname must be a valid ASCII DNS hostname or wildcard hostname with no edge whitespace"
            );
        }
    }

    if request.config.is_some() {
        bail!(
            "custom cloudflared config is disabled for desktop exposure; BeamScale pins the tunnel origin to the same-BEAM loopback ingress"
        );
    }
    return Ok(());
}

fn valid_dns_hostname(raw: &str) -> bool {
    if raw != raw.trim() {
        return false;
    }
    let hostname = raw.strip_prefix("*.").unwrap_or(raw);
    if hostname.is_empty() || hostname.len() > 253 || hostname.ends_with('.') {
        return false;
    }

    let mut labels = hostname.split('.');
    let mut label_count = 0_usize;
    for label in labels.by_ref() {
        label_count += 1;
        if label.is_empty() || label.len() > 63 {
            return false;
        }
        let bytes = label.as_bytes();
        if !bytes.first().is_some_and(u8::is_ascii_alphanumeric)
            || !bytes.last().is_some_and(u8::is_ascii_alphanumeric)
            || !bytes
                .iter()
                .all(|byte| byte.is_ascii_alphanumeric() || *byte == b'-')
        {
            return false;
        }
    }

    return label_count >= 2;
}

fn start_runtime(daemon: &mut DaemonState, mut request: RuntimeRequest) -> Result<()> {
    revalidate_tool_before_exec(
        &daemon.settings.bmscl_binary,
        daemon.settings.bmscl_sha256.as_deref(),
        "bmscl",
    )?;
    request.project_dir = request.project_dir.canonicalize().with_context(|| {
        format!(
            "resolve project directory {}",
            request.project_dir.display()
        )
    })?;
    stop_slot(&mut daemon.runtime, "BeamScale runtime")?;

    let mut command = Command::new(&daemon.settings.bmscl_binary);
    command
        .arg("dev")
        .arg(&request.project_dir)
        .arg("--poll-ms")
        .arg(request.poll_ms.to_string())
        .env("BMSCL_DESKTOP_DAEMON", "1")
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());
    if let Some(module) = &request.module {
        command.arg("--module").arg(module);
    }
    if let Some(supervisor_root) = &daemon.supervisor_root {
        command.env("BMSCL_SUPERVISOR_ROOT", supervisor_root);
    }

    scrub_daemon_secret(&mut command);
    configure_process_tree(&mut command);
    let command_argv = command_display(&command);
    let child = command
        .spawn()
        .with_context(|| format!("launch local BeamScale runtime: {}", command_argv.join(" ")))?;
    info!(pid = child.id(), command = ?command_argv, "started BeamScale local runtime");
    daemon.runtime = Some(ManagedChild::new(child, command_argv));
    daemon.last_runtime = Some(request);
    daemon.runtime_restart.reset();
    return Ok(());
}

fn dns_route_for_request(request: &TunnelRequest) -> Option<DnsRoute> {
    return request.hostname.as_ref().map(|hostname| DnsRoute {
        tunnel: request.name.clone(),
        // DNS names are case-insensitive. Canonicalize journal identity so
        // retries/reconciliation cannot fork state on casing alone.
        hostname: hostname.to_ascii_lowercase(),
    });
}

fn pending_dns_route_for_request(daemon: &DaemonState, request: &TunnelRequest) -> bool {
    return dns_route_for_request(request)
        .as_ref()
        .is_some_and(|route| daemon.pending_dns_routes.contains(route));
}

fn route_tunnel_dns(
    daemon: &mut DaemonState,
    root: &Path,
    request: &TunnelRequest,
) -> Result<bool> {
    let Some(route) = dns_route_for_request(request) else {
        return Ok(false);
    };
    revalidate_tool_before_exec(
        &daemon.settings.cloudflared_binary,
        daemon.settings.cloudflared_sha256.as_deref(),
        "cloudflared",
    )?;
    let hostname = route.hostname.as_str();

    if daemon.known_dns_routes.contains(&route) {
        info!(
            tunnel = %route.tunnel,
            hostname = %route.hostname,
            "Cloudflare DNS route already provisioned by this daemon; skipping mutation"
        );
        return Ok(false);
    }
    if daemon.pending_dns_routes.contains(&route) {
        bail!(
            "Cloudflare DNS route outcome is uncertain for {} -> {}; reconcile it before retrying",
            route.hostname,
            route.tunnel
        );
    }
    if daemon
        .known_dns_routes
        .len()
        .saturating_add(daemon.pending_dns_routes.len())
        >= MAX_DNS_ROUTES
    {
        bail!("Cloudflare DNS route journal reached maximum size {MAX_DNS_ROUTES}");
    }

    // Persist uncertainty before the external DNS mutation. If the process
    // crashes or the cloudflared command times out after Cloudflare accepted
    // the mutation, restart will not blindly issue a second create.
    daemon.pending_dns_routes.push(route.clone());
    if let Err(error) = save_desired_state(root, daemon) {
        daemon
            .pending_dns_routes
            .retain(|candidate| candidate != &route);
        return Err(error).context("persist pending Cloudflare DNS route");
    }

    let mut command = Command::new(&daemon.settings.cloudflared_binary);
    command
        .args(["tunnel", "route", "dns"])
        .arg(&request.name)
        .arg(hostname);
    let status = run_status_with_timeout(
        &mut command,
        "cloudflared tunnel route dns",
        Duration::from_secs(60),
    )
    .with_context(|| format!("route Cloudflare tunnel {} to {hostname}", request.name))?;
    if !status.success() {
        bail!(
            "cloudflared tunnel route dns failed with {status}; route outcome is left pending for explicit reconciliation"
        );
    }

    daemon
        .pending_dns_routes
        .retain(|candidate| candidate != &route);
    daemon.known_dns_routes.push(route.clone());
    if let Err(error) = save_desired_state(root, daemon) {
        daemon
            .known_dns_routes
            .retain(|candidate| candidate != &route);
        if !daemon.pending_dns_routes.contains(&route) {
            daemon.pending_dns_routes.push(route);
        }
        return Err(error).context("persist confirmed Cloudflare DNS route");
    }
    return Ok(true);
}

fn build_tunnel_command(
    settings: &Settings,
    ingress_url: &str,
    request: &TunnelRequest,
) -> Result<Command> {
    revalidate_tool_before_exec(
        &settings.cloudflared_binary,
        settings.cloudflared_sha256.as_deref(),
        "cloudflared",
    )?;
    validate_tunnel_request(request)?;
    let origin = validate_loopback_http_origin(ingress_url)?;

    let mut command = Command::new(&settings.cloudflared_binary);
    command
        .arg("tunnel")
        .arg("--no-autoupdate")
        .arg("run")
        .arg("--url")
        .arg(origin)
        .arg(&request.name)
        .stdin(Stdio::null())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());
    return Ok(command);
}

fn start_tunnel_process(
    daemon: &mut DaemonState,
    ingress_url: &str,
    request: TunnelRequest,
) -> Result<()> {
    stop_slot(&mut daemon.tunnel, "Cloudflare tunnel")?;

    let mut command = build_tunnel_command(&daemon.settings, ingress_url, &request)?;
    scrub_daemon_secret(&mut command);
    configure_process_tree(&mut command);
    let command_argv = command_display(&command);
    let child = command
        .spawn()
        .with_context(|| format!("launch Cloudflare tunnel: {}", command_argv.join(" ")))?;
    info!(
        pid = child.id(),
        origin = %ingress_url,
        command = ?command_argv,
        "started Cloudflare tunnel pinned to BeamScale loopback ingress"
    );
    daemon.tunnel = Some(ManagedChild::new(child, command_argv));
    daemon.last_tunnel = Some(request);
    daemon.tunnel_restart.reset();
    return Ok(());
}

fn apply_keep_awake(daemon: &mut DaemonState) -> Result<()> {
    let should_run = daemon.settings.keep_alive_during_lock && slot_running(&mut daemon.runtime);
    if should_run {
        return ensure_keep_awake(daemon);
    }
    return stop_slot(&mut daemon.keep_awake, "keep-awake inhibitor");
}

fn ensure_keep_awake(daemon: &mut DaemonState) -> Result<()> {
    if slot_running(&mut daemon.keep_awake) {
        return Ok(());
    }

    let pid = std::process::id().to_string();
    let mut command = if cfg!(target_os = "macos") {
        let mut command =
            Command::new(trusted_system_tool(&["/usr/bin/caffeinate"], "caffeinate")?);
        command.args(["-i", "-w", &pid]);
        command
    } else if cfg!(target_os = "linux") {
        let inhibit = trusted_system_tool(
            &[
                "/usr/bin/systemd-inhibit",
                "/bin/systemd-inhibit",
                "/run/current-system/sw/bin/systemd-inhibit",
            ],
            "systemd-inhibit",
        )?;
        let sleep = trusted_system_tool(
            &[
                "/usr/bin/sleep",
                "/bin/sleep",
                "/run/current-system/sw/bin/sleep",
            ],
            "sleep",
        )?;
        let mut command = Command::new(inhibit);
        command
            .args([
                "--what=sleep",
                "--mode=block",
                "--why=BeamScale local runtime",
            ])
            .arg(sleep)
            .arg("infinity");
        command
    } else if cfg!(target_os = "windows") {
        let script = "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class BmsclPower { [DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags); }'; while ($true) { [BmsclPower]::SetThreadExecutionState(0x80000001) | Out-Null; Start-Sleep -Seconds 30 }";
        let mut command = Command::new(trusted_system_tool(
            &[r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"],
            "Windows PowerShell",
        )?);
        command.args(["-NoProfile", "-NonInteractive", "-Command", script]);
        command
    } else {
        bail!("keep-awake is not implemented for this operating system");
    };

    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::inherit());
    scrub_daemon_secret(&mut command);
    configure_process_tree(&mut command);
    let command_argv = command_display(&command);
    let child = command
        .spawn()
        .with_context(|| format!("launch keep-awake inhibitor: {}", command_argv.join(" ")))?;
    info!(pid = child.id(), command = ?command_argv, "keep-awake enabled");
    daemon.keep_awake = Some(ManagedChild::new(child, command_argv));
    return Ok(());
}

async fn desired_state_watchdog(state: AppState) {
    let mut interval = tokio::time::interval(WATCHDOG_INTERVAL);
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    // Avoid immediately rechecking children that startup just restored.
    interval.tick().await;

    loop {
        interval.tick().await;

        let mut daemon = match lock_state(&state) {
            Ok(daemon) => daemon,
            Err(error) => {
                error!(%error, "desktop desired-state watchdog cannot lock state");
                continue;
            }
        };

        if daemon.maintenance_in_progress {
            drop(daemon);
            continue;
        }

        if let Some(request) = daemon.last_runtime.clone() {
            if !slot_running(&mut daemon.runtime) && daemon.runtime_restart.ready() {
                warn!("desired BeamScale runtime exited; restarting");
                record_lifecycle_event(
                    &mut daemon,
                    "runtime",
                    "watchdog-restart",
                    "attempt",
                    "desired runtime was not running",
                );
                if let Err(error) = start_runtime(&mut daemon, request) {
                    let delay = daemon.runtime_restart.record_failure();
                    record_lifecycle_event(
                        &mut daemon,
                        "runtime",
                        "watchdog-restart",
                        "error",
                        format!("restart failed; retry in {} ms", delay.as_millis()),
                    );
                    warn!(
                        %error,
                        retry_in_ms = delay.as_millis(),
                        "failed to restart desired BeamScale runtime"
                    );
                } else {
                    record_lifecycle_event(
                        &mut daemon,
                        "runtime",
                        "watchdog-restart",
                        "ok",
                        "desired runtime recovered",
                    );
                }
            }
        } else {
            daemon.runtime_restart.reset();
        }

        let runtime_running = slot_running(&mut daemon.runtime);
        if !runtime_running {
            if slot_running(&mut daemon.tunnel) {
                warn!("BeamScale runtime is down; suspending public Cloudflare tunnel");
                if let Err(error) = stop_slot(&mut daemon.tunnel, "Cloudflare tunnel") {
                    record_lifecycle_event(
                        &mut daemon,
                        "tunnel",
                        "suspend",
                        "error",
                        "failed to suspend tunnel while runtime was down",
                    );
                    warn!(%error, "failed to suspend Cloudflare tunnel while runtime is down");
                } else {
                    record_lifecycle_event(
                        &mut daemon,
                        "tunnel",
                        "suspend",
                        "ok",
                        "runtime was down; public tunnel suspended",
                    );
                }
            }
            daemon.tunnel_restart.reset();
        } else if let Some(request) = daemon.last_tunnel.clone() {
            if pending_dns_route_for_request(&daemon, &request) {
                daemon.tunnel_restart.reset();
            } else if !slot_running(&mut daemon.tunnel) && daemon.tunnel_restart.ready() {
                warn!("desired Cloudflare tunnel exited; restarting without DNS mutation");
                record_lifecycle_event(
                    &mut daemon,
                    "tunnel",
                    "watchdog-restart",
                    "attempt",
                    "desired public tunnel was not running",
                );
                if let Err(error) =
                    start_tunnel_process(&mut daemon, state.ingress_url.as_str(), request)
                {
                    let delay = daemon.tunnel_restart.record_failure();
                    record_lifecycle_event(
                        &mut daemon,
                        "tunnel",
                        "watchdog-restart",
                        "error",
                        format!("restart failed; retry in {} ms", delay.as_millis()),
                    );
                    warn!(
                        %error,
                        retry_in_ms = delay.as_millis(),
                        "failed to restart desired Cloudflare tunnel"
                    );
                } else {
                    record_lifecycle_event(
                        &mut daemon,
                        "tunnel",
                        "watchdog-restart",
                        "ok",
                        "desired public tunnel recovered without DNS mutation",
                    );
                }
            }
        } else {
            daemon.tunnel_restart.reset();
        }

        if let Err(error) = apply_keep_awake(&mut daemon) {
            warn!(%error, "failed to reconcile keep-awake policy");
        }

        drop(daemon);
    }
}

impl RestartBackoff {
    fn ready(&self) -> bool {
        return self
            .next_attempt
            .is_none_or(|deadline| Instant::now() >= deadline);
    }

    fn record_failure(&mut self) -> Duration {
        let exponent = self.failures.min(6);
        let delay = Duration::from_secs(1_u64 << exponent);
        self.failures = self.failures.saturating_add(1);
        self.next_attempt = Some(Instant::now() + delay);
        return delay;
    }

    fn reset(&mut self) {
        self.failures = 0;
        self.next_attempt = None;
    }

    fn view(&self) -> RestartView {
        let retry_in_ms = self
            .next_attempt
            .map(|deadline| {
                deadline
                    .saturating_duration_since(Instant::now())
                    .as_millis()
            })
            .unwrap_or_default()
            .min(u128::from(u64::MAX)) as u64;
        return RestartView {
            failures: self.failures,
            retry_in_ms,
        };
    }
}

fn revalidate_tool_before_exec(
    program: &str,
    expected_sha256: Option<&str>,
    label: &str,
) -> Result<()> {
    let Some(expected) = expected_sha256 else {
        return Ok(());
    };
    validate_service_tool_pin(Path::new(program), expected, label)
        .map(|_| ())
        .with_context(|| format!("revalidate pinned {label} before execution"))
}

fn reject_service_mode_self_update(root: &Path) -> std::result::Result<(), ApiError> {
    if service_tools_path(root).is_file() {
        return Err((
            StatusCode::CONFLICT,
            "persistent service mode uses content-pinned executables; upgrade tools externally and rerun bmscl local service install to review and repin the new bytes".into(),
        ));
    }
    Ok(())
}
fn apply_updates(settings: &Settings) -> Result<()> {
    revalidate_tool_before_exec(&settings.zed_binary, settings.zed_sha256.as_deref(), "zed")?;
    let mut self_update = Command::new(&settings.zed_binary);
    self_update.args(["self-update"]);
    let status = run_status_with_timeout(
        &mut self_update,
        "zed self-update",
        Duration::from_secs(5 * 60),
    )
    .context("run zed self-update")?;
    if !status.success() {
        bail!("zed self-update failed with {status}");
    }

    if let Some(root) = &settings.update_root {
        let mut install = Command::new(&settings.zed_binary);
        install.args(["install", "--frozen"]).current_dir(root);
        let status = run_status_with_timeout(
            &mut install,
            "zed install --frozen",
            Duration::from_secs(10 * 60),
        )
        .with_context(|| format!("run frozen Zed install in {}", root.display()))?;
        if !status.success() {
            bail!("zed install --frozen failed with {status}");
        }
    }
    return Ok(());
}

fn probe_pinned(
    program: &str,
    expected_sha256: Option<&str>,
    label: &str,
    args: &[&str],
) -> VersionProbe {
    if let Err(error) = revalidate_tool_before_exec(program, expected_sha256, label) {
        return VersionProbe {
            ok: false,
            output: error.to_string(),
        };
    }
    probe(program, args)
}

fn probe(program: &str, args: &[&str]) -> VersionProbe {
    match run_probe_bounded(
        program,
        args,
        Duration::from_secs(5),
        MAX_PROBE_OUTPUT_BYTES,
    ) {
        Ok((status, stdout, stderr)) => {
            let mut text = String::from_utf8_lossy(&stdout).trim().to_string();
            let stderr = String::from_utf8_lossy(&stderr).trim().to_string();
            if text.is_empty() {
                text = stderr;
            } else if !stderr.is_empty() {
                text.push('\n');
                text.push_str(&stderr);
            }
            return VersionProbe {
                ok: status.success(),
                output: text,
            };
        }
        Err(error) => {
            return VersionProbe {
                ok: false,
                output: error.to_string(),
            };
        }
    }
}

fn run_probe_bounded(
    program: &str,
    args: &[&str],
    timeout: Duration,
    maximum: usize,
) -> Result<(ExitStatus, Vec<u8>, Vec<u8>)> {
    return run_probe_bounded_with_extra_scrub(program, args, timeout, maximum, &[]);
}

fn run_probe_bounded_with_extra_scrub(
    program: &str,
    args: &[&str],
    timeout: Duration,
    maximum: usize,
    extra_secret_keys: &[&str],
) -> Result<(ExitStatus, Vec<u8>, Vec<u8>)> {
    use std::sync::mpsc;

    let mut command = Command::new(program);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    scrub_daemon_secret(&mut command);
    for key in extra_secret_keys {
        command.env_remove(key);
    }
    configure_process_tree(&mut command);

    let mut child = command
        .spawn()
        .with_context(|| format!("launch diagnostic probe {program}"))?;
    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => {
            let _ = terminate_process_tree(&mut child);
            bail!("diagnostic probe stdout pipe is unavailable");
        }
    };
    let stderr = match child.stderr.take() {
        Some(stderr) => stderr,
        None => {
            let _ = terminate_process_tree(&mut child);
            bail!("diagnostic probe stderr pipe is unavailable");
        }
    };

    let (stdout_tx, stdout_rx) = mpsc::sync_channel(1);
    let (stderr_tx, stderr_rx) = mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let _ = stdout_tx.send(read_probe_bounded(stdout, maximum));
    });
    std::thread::spawn(move || {
        let _ = stderr_tx.send(read_probe_bounded(stderr, maximum));
    });

    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => {
                let _ = terminate_process_tree(&mut child);
                bail!(
                    "diagnostic probe {program} timed out after {} seconds",
                    timeout.as_secs()
                );
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(25)),
            Err(error) => {
                let cleanup = terminate_process_tree(&mut child);
                return match cleanup {
                    Ok(()) => Err(error).context("poll diagnostic probe"),
                    Err(cleanup_error) => Err(error).context(format!(
                        "poll diagnostic probe; process-tree cleanup also failed: {cleanup_error}"
                    )),
                };
            }
        }
    };

    let remaining = || deadline.saturating_duration_since(Instant::now());
    let stdout = match stdout_rx.recv_timeout(remaining()) {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(error)) => {
            let _ = cleanup_residual_process_tree(&mut child);
            return Err(error).context("capture diagnostic probe stdout");
        }
        Err(_) => {
            let _ = cleanup_residual_process_tree(&mut child);
            bail!("diagnostic probe {program} stdout did not close before deadline");
        }
    };
    let stderr = match stderr_rx.recv_timeout(remaining()) {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(error)) => {
            let _ = cleanup_residual_process_tree(&mut child);
            return Err(error).context("capture diagnostic probe stderr");
        }
        Err(_) => {
            let _ = cleanup_residual_process_tree(&mut child);
            bail!("diagnostic probe {program} stderr did not close before deadline");
        }
    };

    return Ok((status, stdout, stderr));
}

fn read_probe_bounded<R>(reader: R, maximum: usize) -> Result<Vec<u8>>
where
    R: Read,
{
    let mut output = Vec::new();
    let mut limited = reader.take((maximum + 1) as u64);
    limited
        .read_to_end(&mut output)
        .context("read diagnostic probe output")?;
    if output.len() > maximum {
        bail!("diagnostic probe output exceeds {maximum} byte limit");
    }
    Ok(output)
}

impl ManagedChild {
    fn new(child: Child, command: Vec<String>) -> Self {
        return Self {
            child,
            command,
            started_at_unix_ms: now_unix_ms(),
        };
    }
}

impl Drop for ManagedChild {
    fn drop(&mut self) {
        let should_terminate = match self.child.try_wait() {
            Ok(None) => true,
            Ok(Some(_)) => false,
            Err(error) => {
                warn!(
                    pid = self.child.id(),
                    %error,
                    "failed to inspect managed process during drop; terminating defensively"
                );
                true
            }
        };
        if !should_terminate {
            return;
        }

        if let Err(error) = terminate_process_tree(&mut self.child) {
            warn!(
                pid = self.child.id(),
                %error,
                "failed to terminate managed process tree during drop"
            );
        }
        let _ = self.child.wait();
    }
}

fn process_view(slot: &mut Option<ManagedChild>) -> ProcessView {
    let Some(process) = slot.as_mut() else {
        return stopped_process_view();
    };

    match process.child.try_wait() {
        Ok(None) => {
            return ProcessView {
                running: true,
                pid: Some(process.child.id()),
                command: Some(process.command.clone()),
                started_at_unix_ms: Some(process.started_at_unix_ms),
            };
        }
        Ok(Some(status)) => {
            info!(pid = process.child.id(), %status, "managed process leader exited");
            if let Some(mut exited) = slot.take() {
                if let Err(error) = cleanup_residual_process_tree(&mut exited.child) {
                    warn!(
                        pid = exited.child.id(),
                        %error,
                        "failed to clean residual managed process group"
                    );
                }
            }
            return stopped_process_view();
        }
        Err(error) => {
            warn!(pid = process.child.id(), %error, "failed to inspect managed process");
            return ProcessView {
                running: false,
                pid: None,
                command: Some(process.command.clone()),
                started_at_unix_ms: Some(process.started_at_unix_ms),
            };
        }
    }
}

fn stopped_process_view() -> ProcessView {
    return ProcessView {
        running: false,
        pid: None,
        command: None,
        started_at_unix_ms: None,
    };
}

fn slot_running(slot: &mut Option<ManagedChild>) -> bool {
    let Some(process) = slot.as_mut() else {
        return false;
    };

    match process.child.try_wait() {
        Ok(None) => true,
        Ok(Some(status)) => {
            info!(pid = process.child.id(), %status, "managed process leader exited");
            if let Some(mut exited) = slot.take() {
                if let Err(error) = cleanup_residual_process_tree(&mut exited.child) {
                    warn!(
                        pid = exited.child.id(),
                        %error,
                        "failed to clean residual managed process group"
                    );
                }
            }
            false
        }
        Err(error) => {
            warn!(pid = process.child.id(), %error, "failed to inspect managed process");
            false
        }
    }
}

fn stop_slot(slot: &mut Option<ManagedChild>, label: &str) -> Result<()> {
    let Some(mut process) = slot.take() else {
        return Ok(());
    };

    let leader_running = process.child.try_wait()?.is_none();
    if leader_running {
        info!(pid = process.child.id(), %label, "stopping managed process tree");
        terminate_process_tree(&mut process.child)
            .with_context(|| format!("stop {label} process tree"))?;
    } else {
        cleanup_residual_process_tree(&mut process.child)
            .with_context(|| format!("clean residual {label} process group"))?;
    }

    let _ = process.child.wait();
    return Ok(());
}

#[cfg(unix)]
fn configure_process_tree(command: &mut Command) {
    use std::os::unix::process::CommandExt;

    command.process_group(0);
}

#[cfg(not(unix))]
fn configure_process_tree(_command: &mut Command) {}

#[cfg(unix)]
fn terminate_process_tree(child: &mut Child) -> Result<()> {
    use nix::sys::signal::Signal;

    let process_group =
        i32::try_from(child.id()).context("managed child process id exceeds i32")?;

    signal_process_group(process_group, Signal::SIGTERM)?;
    for _ in 0..20 {
        match child.try_wait() {
            Ok(Some(_)) => break,
            Ok(None) => thread::sleep(Duration::from_millis(50)),
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error).context("poll managed process leader during shutdown");
            }
        }
    }

    // Always SIGKILL the group after the grace period, even if the direct
    // leader exited after SIGTERM. Descendants can ignore SIGTERM while
    // retaining sockets, inherited pipes, or the local origin.
    signal_process_group(process_group, Signal::SIGKILL)?;
    let _ = child.wait();
    return Ok(());
}

#[cfg(unix)]
fn cleanup_residual_process_tree(child: &mut Child) -> Result<()> {
    use nix::sys::signal::Signal;

    let process_group =
        i32::try_from(child.id()).context("managed child process id exceeds i32")?;
    signal_process_group(process_group, Signal::SIGKILL)?;
    let _ = child.wait();
    Ok(())
}

#[cfg(unix)]
fn signal_process_group(process_group: i32, signal: nix::sys::signal::Signal) -> Result<()> {
    use nix::{errno::Errno, sys::signal::killpg, unistd::Pid};

    match killpg(Pid::from_raw(process_group), signal) {
        Ok(()) | Err(Errno::ESRCH) => Ok(()),
        Err(error) => Err(error.into()),
    }
}

#[cfg(windows)]
fn terminate_process_tree(child: &mut Child) -> Result<()> {
    const TASKKILL: &str = r"C:\Windows\System32\taskkill.exe";
    const TASKKILL_TIMEOUT: Duration = Duration::from_secs(2);

    let pid = child.id().to_string();
    let mut killer = Command::new(TASKKILL);
    killer
        .args(["/PID", &pid, "/T", "/F"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    let mut killer_child = killer
        .spawn()
        .with_context(|| format!("launch fixed taskkill for managed process tree at {TASKKILL}"))?;
    let deadline = Instant::now() + TASKKILL_TIMEOUT;

    let status = loop {
        match killer_child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) if Instant::now() >= deadline => {
                let _ = killer_child.kill();
                let _ = killer_child.wait();
                break None;
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(25)),
            Err(error) => {
                let _ = killer_child.kill();
                let _ = killer_child.wait();
                let _ = child.kill();
                let _ = child.wait();
                return Err(error).context("poll taskkill for managed process tree");
            }
        }
    };

    match status {
        Some(status) if status.success() => {
            let _ = child.wait();
            Ok(())
        }
        Some(status) if child.try_wait()?.is_some() => Ok(()),
        Some(status) => {
            let _ = child.kill();
            let _ = child.wait();
            bail!("taskkill failed with {status}");
        }
        None => {
            let _ = child.kill();
            let _ = child.wait();
            bail!(
                "taskkill timed out after {} seconds",
                TASKKILL_TIMEOUT.as_secs()
            );
        }
    }
}

#[cfg(windows)]
fn cleanup_residual_process_tree(child: &mut Child) -> Result<()> {
    // taskkill cannot reliably discover a tree once its parent PID has exited.
    // Reap the leader here; normal and timeout shutdown paths call
    // terminate_process_tree while the leader is still alive.
    let _ = child.wait();
    Ok(())
}

#[cfg(not(any(unix, windows)))]
fn terminate_process_tree(child: &mut Child) -> Result<()> {
    child.kill().context("kill managed child")?;
    return Ok(());
}

#[cfg(not(any(unix, windows)))]
fn cleanup_residual_process_tree(child: &mut Child) -> Result<()> {
    let _ = child.wait();
    Ok(())
}

fn trusted_system_tool(candidates: &[&str], label: &str) -> Result<PathBuf> {
    for candidate in candidates {
        let path = PathBuf::from(candidate);
        match fs::metadata(&path) {
            Ok(metadata) if metadata.is_file() => {
                let canonical = fs::canonicalize(&path)
                    .with_context(|| format!("canonicalize trusted {label} {}", path.display()))?;
                let metadata = fs::symlink_metadata(&canonical)
                    .with_context(|| format!("inspect trusted {label} {}", canonical.display()))?;
                if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
                    bail!(
                        "trusted {label} must resolve to a regular non-symlink file: {}",
                        canonical.display()
                    );
                }
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    if metadata.permissions().mode() & 0o111 == 0 {
                        bail!("trusted {label} is not executable: {}", canonical.display());
                    }
                }
                return Ok(canonical);
            }
            Ok(_) => continue,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(error).with_context(|| {
                    format!("inspect trusted {label} candidate {}", path.display())
                });
            }
        }
    }
    bail!(
        "trusted {label} was not found in fixed operating-system locations: {}",
        candidates.join(", ")
    )
}

fn scrub_daemon_secret(command: &mut Command) {
    command.env_remove("BMSCL_DAEMON_TOKEN");
    command.env_remove("BMSCL_DAEMON_OPERATOR_TOKEN");
    command.env_remove("BMSCL_LOCAL_INGRESS_TOKEN");
}

fn run_status_with_timeout(
    command: &mut Command,
    label: &str,
    timeout: Duration,
) -> Result<ExitStatus> {
    command.stdin(Stdio::null());
    scrub_daemon_secret(command);
    configure_process_tree(command);

    let display = command_display(command);
    let mut child = command
        .spawn()
        .with_context(|| format!("launch {label}: {}", display.join(" ")))?;
    let deadline = Instant::now() + timeout;

    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Ok(status),
            Ok(None) if Instant::now() >= deadline => {
                let _ = terminate_process_tree(&mut child);
                let _ = child.wait();
                bail!("{label} timed out after {} seconds", timeout.as_secs());
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(50)),
            Err(error) => {
                let cleanup = terminate_process_tree(&mut child);
                return match cleanup {
                    Ok(()) => Err(error).with_context(|| format!("poll {label}")),
                    Err(cleanup_error) => Err(error).with_context(|| {
                        format!("poll {label}; process-tree cleanup also failed: {cleanup_error}")
                    }),
                };
            }
        }
    }
}

fn command_display(command: &Command) -> Vec<String> {
    let mut display = vec![command.get_program().to_string_lossy().to_string()];
    display.extend(
        command
            .get_args()
            .map(|arg| arg.to_string_lossy().to_string()),
    );
    return display;
}

fn require_auth(headers: &HeaderMap, state: &AppState) -> std::result::Result<(), ApiError> {
    let actual = headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if !actual.is_some_and(|token| constant_time_eq(token.as_bytes(), state.token.as_bytes())) {
        return Err((StatusCode::UNAUTHORIZED, "invalid daemon token".into()));
    }

    let protocol = headers
        .get(PROTOCOL_HEADER)
        .and_then(|value| value.to_str().ok());
    if protocol != Some(API_VERSION) {
        return Err((
            StatusCode::PRECONDITION_FAILED,
            format!("unsupported or missing {PROTOCOL_HEADER}; expected {API_VERSION}"),
        ));
    }

    return Ok(());
}

fn require_operator_auth(
    headers: &HeaderMap,
    state: &AppState,
) -> std::result::Result<(), ApiError> {
    let actual = headers
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "));
    if !actual
        .is_some_and(|token| constant_time_eq(token.as_bytes(), state.operator_token.as_bytes()))
    {
        return Err((StatusCode::FORBIDDEN, "operator credential required".into()));
    }

    let protocol = headers
        .get(PROTOCOL_HEADER)
        .and_then(|value| value.to_str().ok());
    if protocol != Some(API_VERSION) {
        return Err((
            StatusCode::PRECONDITION_FAILED,
            format!("unsupported or missing {PROTOCOL_HEADER}; expected {API_VERSION}"),
        ));
    }
    return Ok(());
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    let maximum = left.len().max(right.len());
    let mut difference = left.len() ^ right.len();
    for index in 0..maximum {
        let left_byte = left.get(index).copied().unwrap_or(0);
        let right_byte = right.get(index).copied().unwrap_or(0);
        difference |= usize::from(left_byte ^ right_byte);
    }
    return difference == 0;
}

fn idempotency_key(headers: &HeaderMap) -> std::result::Result<String, ApiError> {
    let key = headers
        .get(IDEMPOTENCY_HEADER)
        .and_then(|value| value.to_str().ok())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| {
            (
                StatusCode::BAD_REQUEST,
                format!("missing {IDEMPOTENCY_HEADER}"),
            )
        })?;

    if !valid_idempotency_key(key) {
        return Err((
            StatusCode::BAD_REQUEST,
            format!("invalid {IDEMPOTENCY_HEADER}"),
        ));
    }

    return Ok(key.to_string());
}

fn valid_idempotency_key(key: &str) -> bool {
    return !key.is_empty()
        && key.len() <= 160
        && key
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'));
}

fn reject_maintenance(daemon: &DaemonState) -> std::result::Result<(), ApiError> {
    if daemon.maintenance_in_progress {
        return Err((
            StatusCode::CONFLICT,
            "BeamScale desktop update is in progress; retry after maintenance completes".into(),
        ));
    }
    return Ok(());
}

fn reject_replayed_idempotency(
    daemon: &DaemonState,
    key: &str,
) -> std::result::Result<(), ApiError> {
    if daemon.recent_idempotency.contains(key) {
        return Err((
            StatusCode::CONFLICT,
            "duplicate mutation idempotency key".into(),
        ));
    }
    return Ok(());
}

fn remember_idempotency(daemon: &mut DaemonState, key: String) {
    if daemon.recent_idempotency.insert(key.clone()) {
        daemon.idempotency_order.push_back(key);
    }

    while daemon.idempotency_order.len() > MAX_RECENT_IDEMPOTENCY_KEYS {
        if let Some(expired) = daemon.idempotency_order.pop_front() {
            daemon.recent_idempotency.remove(&expired);
        }
    }
}

fn reserve_idempotency(daemon: &mut DaemonState, root: &Path, key: String) -> Result<()> {
    let previous_set = daemon.recent_idempotency.clone();
    let previous_order = daemon.idempotency_order.clone();
    remember_idempotency(daemon, key);

    if let Err(error) = save_replay_state(root, daemon) {
        daemon.recent_idempotency = previous_set;
        daemon.idempotency_order = previous_order;
        return Err(error);
    }

    return Ok(());
}

fn lock_state(state: &AppState) -> Result<MutexGuard<'_, DaemonState>> {
    return state
        .inner
        .lock()
        .map_err(|_| anyhow::anyhow!("daemon state lock poisoned"));
}

fn lock_state_api(state: &AppState) -> std::result::Result<MutexGuard<'_, DaemonState>, ApiError> {
    return state
        .inner
        .lock()
        .map_err(|_| internal_error(anyhow::anyhow!("daemon state lock poisoned")));
}

fn internal_error(error: impl std::fmt::Display) -> ApiError {
    error!(error = %error, "internal BeamScale desktop daemon operation failed");
    return (
        StatusCode::INTERNAL_SERVER_ERROR,
        "internal BeamScale desktop daemon error".into(),
    );
}

fn bad_request(error: impl std::fmt::Display) -> ApiError {
    return (StatusCode::BAD_REQUEST, error.to_string());
}

fn validate_loopback_http_origin(raw: &str) -> Result<String> {
    let parsed =
        reqwest::Url::parse(raw).with_context(|| format!("parse BMSCL_LOCAL_INGRESS_URL={raw}"))?;
    if parsed.scheme() != "http" {
        bail!("BMSCL_LOCAL_INGRESS_URL must use http://");
    }
    if parsed.port().is_none() {
        bail!("BMSCL_LOCAL_INGRESS_URL must include an explicit port");
    }
    if !parsed.username().is_empty() || parsed.password().is_some() {
        bail!("BMSCL_LOCAL_INGRESS_URL must not contain credentials");
    }
    let host = parsed
        .host_str()
        .context("BMSCL_LOCAL_INGRESS_URL must include a host")?
        .to_ascii_lowercase();
    if host != "127.0.0.1" && host != "[::1]" {
        bail!("BMSCL_LOCAL_INGRESS_URL must use numeric loopback");
    }
    if parsed.query().is_some() || parsed.fragment().is_some() || parsed.path() != "/" {
        bail!("BMSCL_LOCAL_INGRESS_URL must be an origin without path, query, or fragment");
    }
    return Ok(raw.trim_end_matches('/').to_string());
}

async fn read_response_body_bounded(
    mut response: reqwest::Response,
    maximum: usize,
) -> Result<Vec<u8>> {
    if response
        .content_length()
        .is_some_and(|length| length > maximum as u64)
    {
        bail!("loopback ingress response exceeds {maximum} byte limit");
    }

    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .context("read loopback ingress response")?
    {
        if body.len().saturating_add(chunk.len()) > maximum {
            bail!("loopback ingress response exceeds {maximum} byte limit");
        }
        body.extend_from_slice(&chunk);
    }
    return Ok(body);
}

fn bad_gateway(error: impl std::fmt::Display) -> ApiError {
    warn!(error = %error, "BeamScale loopback ingress request failed");
    return (
        StatusCode::BAD_GATEWAY,
        "BeamScale loopback ingress request failed".into(),
    );
}

fn data_root() -> Result<PathBuf> {
    if let Ok(path) = env::var("BMSCL_DESKTOP_HOME") {
        if path.trim().is_empty() {
            bail!("BMSCL_DESKTOP_HOME must not be empty");
        }
        return Ok(PathBuf::from(path));
    }
    if let Ok(home) = env::var("HOME") {
        return Ok(PathBuf::from(home)
            .join(".beamscale")
            .join("desktop-daemon"));
    }
    if let Ok(home) = env::var("USERPROFILE") {
        return Ok(PathBuf::from(home)
            .join(".beamscale")
            .join("desktop-daemon"));
    }
    bail!("cannot locate home directory; set BMSCL_DESKTOP_HOME")
}

fn settings_path(root: &Path) -> PathBuf {
    return root.join("settings.json");
}

fn service_tools_path(root: &Path) -> PathBuf {
    return root.join("service-tools.json");
}

fn desired_state_path(root: &Path) -> PathBuf {
    return root.join("desired-state.json");
}

fn replay_state_path(root: &Path) -> PathBuf {
    return root.join("replay-state.json");
}

fn token_path(root: &Path) -> PathBuf {
    return root.join("token");
}

fn operator_token_path(root: &Path) -> PathBuf {
    return root.join("operator-token");
}

fn load_settings(root: &Path) -> Result<Settings> {
    let path = settings_path(root);
    if !path.exists() {
        return Ok(Settings::default());
    }
    let bytes = read_private_file_bounded(&path, MAX_STATE_FILE_BYTES)?;
    return serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()));
}

fn apply_service_tool_overrides(
    root: &Path,
    settings: &mut Settings,
) -> Result<Option<(PathBuf, String)>> {
    let path = service_tools_path(root);
    if !path.exists() {
        return Ok(None);
    }

    let bytes = read_private_file_bounded(&path, MAX_STATE_FILE_BYTES)?;
    let overrides: ServiceToolOverrides =
        serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;

    validate_current_daemon_pin(
        overrides.daemon_binary.as_deref(),
        overrides.daemon_sha256.as_deref(),
    )?;

    let bmscl_sha256 = overrides.bmscl_sha256.clone();
    let cloudflared_sha256 = overrides.cloudflared_sha256.clone();
    let zed_sha256 = overrides.zed_sha256.clone();

    settings.bmscl_binary = validate_required_service_tool_pin(
        overrides.bmscl_binary,
        overrides.bmscl_sha256,
        "bmscl",
    )?;
    settings.bmscl_sha256 = bmscl_sha256;

    // A persisted service-tool document is an explicit service-mode trust boundary.
    // Missing optional tools must not fall back to PATH or stale settings.json values.
    settings.cloudflared_binary = validate_optional_service_tool_pin(
        overrides.cloudflared_binary,
        overrides.cloudflared_sha256,
        "cloudflared",
    )?;
    settings.cloudflared_sha256 = cloudflared_sha256;
    settings.zed_binary =
        validate_optional_service_tool_pin(overrides.zed_binary, overrides.zed_sha256, "zed")?;
    settings.zed_sha256 = zed_sha256;

    let supervisor_pin = match (overrides.supervisor_root, overrides.supervisor_ebin_sha256) {
        (Some(path), Some(expected)) => {
            validate_supervisor_digest(&expected)?;
            let root = validate_service_supervisor_root(&path)?;
            let actual = supervisor_ebin_sha256(&root)?;
            if actual != expected {
                bail!(
                    "service supervisor content digest mismatch at {}: expected {}, actual {}",
                    root.display(),
                    expected,
                    actual
                );
            }
            Some((root, expected))
        }
        (None, None) => None,
        (Some(path), None) => {
            bail!(
                "service supervisor root {} is missing supervisor_ebin_sha256; reinstall service",
                path.display()
            )
        }
        (None, Some(_)) => {
            bail!("service-tools.json has supervisor_ebin_sha256 without supervisor_root")
        }
    };
    Ok(supervisor_pin)
}

fn validate_current_daemon_pin(path: Option<&Path>, digest: Option<&str>) -> Result<()> {
    let path = path
        .context("service-tools.json must pin daemon_binary; reinstall the persistent service")?;
    let digest = digest
        .context("service-tools.json must pin daemon_sha256; reinstall the persistent service")?;
    let pinned = validate_service_tool_pin(path, digest, "beamscale-desktop-daemon")?;
    let current =
        env::current_exe().context("resolve current BeamScale desktop daemon executable")?;
    let current = fs::canonicalize(&current)
        .with_context(|| format!("canonicalize current daemon {}", current.display()))?;
    if Path::new(&pinned) != current {
        bail!(
            "persistent service daemon path mismatch: installed {}, running {}; reinstall service",
            pinned,
            current.display()
        );
    }
    Ok(())
}

fn validate_required_service_tool_pin(
    path: Option<PathBuf>,
    digest: Option<String>,
    label: &str,
) -> Result<String> {
    match (path, digest) {
        (Some(path), Some(expected)) => validate_service_tool_pin(&path, &expected, label),
        (Some(path), None) => bail!(
            "service tool {label} at {} is missing a SHA-256 pin; reinstall service",
            path.display()
        ),
        (None, Some(_)) => bail!("service-tools.json has {label}_sha256 without {label}_binary"),
        (None, None) => bail!("service-tools.json must pin {label}_binary and {label}_sha256"),
    }
}

fn validate_optional_service_tool_pin(
    path: Option<PathBuf>,
    digest: Option<String>,
    label: &str,
) -> Result<String> {
    match (path, digest) {
        (Some(path), Some(expected)) => validate_service_tool_pin(&path, &expected, label),
        (Some(path), None) => bail!(
            "service tool {label} at {} is missing a SHA-256 pin; reinstall service",
            path.display()
        ),
        (None, Some(_)) => bail!("service-tools.json has {label}_sha256 without {label}_binary"),
        (None, None) => Ok(String::new()),
    }
}

fn validate_service_tool_pin(path: &Path, expected: &str, label: &str) -> Result<String> {
    validate_sha256(expected, label)?;
    let pinned = validate_service_tool_path(path, label)?;
    let actual = executable_sha256(path, label)?;
    if actual != expected {
        bail!(
            "service tool {label} content digest mismatch at {}: expected {}, actual {}; reinstall service after reviewed upgrades",
            path.display(),
            expected,
            actual
        );
    }
    Ok(pinned)
}

fn validate_sha256(value: &str, label: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        bail!("{label}_sha256 must be exactly 64 lowercase hexadecimal characters");
    }
    Ok(())
}

fn executable_sha256(path: &Path, label: &str) -> Result<String> {
    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("inspect pinned {label} {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "pinned {label} must be a regular non-symlink file: {}",
            path.display()
        );
    }
    if metadata.len() > MAX_PINNED_TOOL_BYTES {
        bail!(
            "pinned {label} exceeds {MAX_PINNED_TOOL_BYTES} bytes: {}",
            path.display()
        );
    }

    let mut file =
        fs::File::open(path).with_context(|| format!("open pinned {label} {}", path.display()))?;
    let before = file
        .metadata()
        .with_context(|| format!("inspect opened {label} {}", path.display()))?;
    ensure_pinned_path_matches_open_file(
        &metadata,
        &before,
        path,
        label,
        "pathname changed between inspection and open",
    )?;
    let mut digest = DigestContext::new(&SHA256);
    let mut buffer = [0_u8; 64 * 1024];
    let mut total = 0_u64;
    loop {
        let read = file
            .read(&mut buffer)
            .with_context(|| format!("hash pinned {label} {}", path.display()))?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(read as u64)
            .with_context(|| format!("{label} byte count overflow"))?;
        if total > MAX_PINNED_TOOL_BYTES {
            bail!("pinned {label} exceeded {MAX_PINNED_TOOL_BYTES} bytes while hashing");
        }
        digest.update(&buffer[..read]);
    }

    let after = file
        .metadata()
        .with_context(|| format!("re-inspect pinned {label} {}", path.display()))?;
    if before.len() != total || after.len() != total {
        bail!(
            "pinned {label} changed size while hashing: {}",
            path.display()
        );
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev() || before.ino() != after.ino() {
            bail!(
                "pinned {label} identity changed while hashing: {}",
                path.display()
            );
        }
    }
    let before_modified = before.modified().ok();
    let after_modified = after.modified().ok();
    if before_modified.is_some() && after_modified.is_some() && before_modified != after_modified {
        bail!(
            "pinned {label} modification time changed while hashing: {}",
            path.display()
        );
    }

    let path_after = fs::symlink_metadata(path)
        .with_context(|| format!("re-inspect pinned {label} path {}", path.display()))?;
    if path_after.file_type().is_symlink() || !path_after.file_type().is_file() {
        bail!(
            "pinned {label} pathname changed to a symlink/non-file while hashing: {}",
            path.display()
        );
    }
    ensure_pinned_path_matches_open_file(
        &path_after,
        &after,
        path,
        label,
        "pathname changed while hashing",
    )?;

    Ok(digest
        .finish()
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn ensure_pinned_path_matches_open_file(
    path_metadata: &fs::Metadata,
    opened_metadata: &fs::Metadata,
    path: &Path,
    label: &str,
    phase: &str,
) -> Result<()> {
    if path_metadata.len() != opened_metadata.len() {
        bail!("pinned {label} {phase}: {}", path.display());
    }
    let path_modified = path_metadata.modified().ok();
    let opened_modified = opened_metadata.modified().ok();
    if path_modified.is_some() && opened_modified.is_some() && path_modified != opened_modified {
        bail!("pinned {label} {phase}: {}", path.display());
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if path_metadata.dev() != opened_metadata.dev()
            || path_metadata.ino() != opened_metadata.ino()
        {
            bail!("pinned {label} {phase}: {}", path.display());
        }
    }

    Ok(())
}

fn doctor_service_tool_integrity_checks(root: &Path) -> Vec<DoctorCheck> {
    let path = service_tools_path(root);
    let overrides = match read_private_file_bounded(&path, MAX_STATE_FILE_BYTES).and_then(|bytes| {
        serde_json::from_slice::<ServiceToolOverrides>(&bytes)
            .with_context(|| format!("parse {}", path.display()))
    }) {
        Ok(overrides) => overrides,
        Err(error) => {
            return vec![DoctorCheck {
                name: "service_tool_pins",
                ok: false,
                detail: format!("cannot load service tool pins: {error:#}"),
            }];
        }
    };

    [
        (
            "daemon_binary",
            overrides.daemon_binary,
            overrides.daemon_sha256,
            true,
        ),
        (
            "bmscl_binary",
            overrides.bmscl_binary,
            overrides.bmscl_sha256,
            true,
        ),
        (
            "cloudflared_binary",
            overrides.cloudflared_binary,
            overrides.cloudflared_sha256,
            false,
        ),
        (
            "zed_binary",
            overrides.zed_binary,
            overrides.zed_sha256,
            false,
        ),
    ]
    .into_iter()
    .map(|(name, path, digest, required)| {
        let label = name.trim_end_matches("_binary");
        match (path, digest) {
            (Some(path), Some(expected)) => {
                match validate_service_tool_pin(&path, &expected, label) {
                    Ok(_) => DoctorCheck {
                        name,
                        ok: true,
                        detail: format!("content matches pinned sha256 at {}", path.display()),
                    },
                    Err(error) => DoctorCheck {
                        name,
                        ok: false,
                        detail: format!("{error:#}"),
                    },
                }
            }
            (None, None) if !required => DoctorCheck {
                name,
                ok: true,
                detail: "not installed in persistent service mode".into(),
            },
            (Some(path), None) => DoctorCheck {
                name,
                ok: false,
                detail: format!("{} has no SHA-256 pin; reinstall service", path.display()),
            },
            (None, Some(_)) => DoctorCheck {
                name,
                ok: false,
                detail: "digest exists without executable path".into(),
            },
            (None, None) => DoctorCheck {
                name,
                ok: false,
                detail: "required persistent service executable is not pinned".into(),
            },
        }
    })
    .collect()
}

fn validate_service_supervisor_root(path: &Path) -> Result<PathBuf> {
    if !path.is_absolute() {
        bail!(
            "service supervisor root must be absolute: {}",
            path.display()
        );
    }

    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("inspect service supervisor root {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
        bail!(
            "service supervisor root must be a pinned real directory: {}",
            path.display()
        );
    }

    let root = fs::canonicalize(path)
        .with_context(|| format!("canonicalize service supervisor root {}", path.display()))?;
    let ebin = root.join("_build/default/lib/bmscl_supervisor/ebin");
    let ebin_metadata = fs::symlink_metadata(&ebin)
        .with_context(|| format!("inspect service supervisor ebin {}", ebin.display()))?;
    if ebin_metadata.file_type().is_symlink() || !ebin_metadata.file_type().is_dir() {
        bail!(
            "service supervisor ebin must be a real directory: {}",
            ebin.display()
        );
    }

    for module in [
        "bmscl_deployment_manager.beam",
        "bmscl_route_table.beam",
        "bmscl_runtime.beam",
    ] {
        let module_path = ebin.join(module);
        let metadata = fs::symlink_metadata(&module_path).with_context(|| {
            format!(
                "inspect service supervisor module {}",
                module_path.display()
            )
        })?;
        if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
            bail!(
                "service supervisor module must be a regular non-symlink file: {}",
                module_path.display()
            );
        }
    }

    Ok(root)
}

fn validate_supervisor_digest(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        bail!("supervisor_ebin_sha256 must be exactly 64 lowercase hexadecimal characters");
    }
    Ok(())
}

fn supervisor_ebin_sha256(root: &Path) -> Result<String> {
    let ebin = root.join("_build/default/lib/bmscl_supervisor/ebin");
    let metadata = fs::symlink_metadata(&ebin)
        .with_context(|| format!("inspect supervisor ebin {}", ebin.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
        bail!(
            "supervisor ebin must be a real directory, not a symlink: {}",
            ebin.display()
        );
    }

    let mut files = Vec::new();
    for entry in
        fs::read_dir(&ebin).with_context(|| format!("read supervisor ebin {}", ebin.display()))?
    {
        let entry = entry.context("read supervisor ebin entry")?;
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| anyhow::anyhow!("supervisor ebin filenames must be UTF-8"))?;
        if name.is_empty() || name.chars().any(char::is_control) {
            bail!("supervisor ebin filename contains invalid control text");
        }
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path)
            .with_context(|| format!("inspect supervisor ebin entry {}", path.display()))?;
        if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
            bail!(
                "supervisor ebin may contain only regular non-symlink files: {}",
                path.display()
            );
        }
        if metadata.len() > MAX_SUPERVISOR_EBIN_FILE_BYTES {
            bail!(
                "supervisor ebin file exceeds {} bytes: {}",
                MAX_SUPERVISOR_EBIN_FILE_BYTES,
                path.display()
            );
        }
        files.push((name, path, metadata.len()));
        if files.len() > MAX_SUPERVISOR_EBIN_FILES {
            bail!("supervisor ebin contains more than {MAX_SUPERVISOR_EBIN_FILES} files");
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
            .with_context(|| format!("open supervisor ebin file {}", path.display()))?;
        let before = file
            .metadata()
            .with_context(|| format!("inspect opened supervisor file {}", path.display()))?;
        if before.len() != expected_len {
            bail!(
                "supervisor ebin file changed before hashing: {}",
                path.display()
            );
        }

        let mut buffer = [0_u8; 64 * 1024];
        let mut read_total = 0_u64;
        loop {
            let read = file
                .read(&mut buffer)
                .with_context(|| format!("hash supervisor file {}", path.display()))?;
            if read == 0 {
                break;
            }
            read_total = read_total
                .checked_add(read as u64)
                .context("supervisor ebin byte count overflow")?;
            total = total
                .checked_add(read as u64)
                .context("supervisor ebin total byte count overflow")?;
            if read_total > MAX_SUPERVISOR_EBIN_FILE_BYTES
                || total > MAX_SUPERVISOR_EBIN_TOTAL_BYTES
            {
                bail!("supervisor ebin exceeded configured byte limits while hashing");
            }
            digest.update(&buffer[..read]);
        }

        let after = file
            .metadata()
            .with_context(|| format!("re-inspect supervisor file {}", path.display()))?;
        if after.len() != before.len() || after.len() != read_total {
            bail!(
                "supervisor ebin file changed size while hashing: {}",
                path.display()
            );
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if after.dev() != before.dev() || after.ino() != before.ino() {
                bail!(
                    "supervisor ebin file identity changed while hashing: {}",
                    path.display()
                );
            }
        }
        let before_modified = before.modified().ok();
        let after_modified = after.modified().ok();
        if before_modified.is_some()
            && after_modified.is_some()
            && before_modified != after_modified
        {
            bail!(
                "supervisor ebin file modification time changed while hashing: {}",
                path.display()
            );
        }
    }

    Ok(digest
        .finish()
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn validate_service_tool_path(path: &Path, label: &str) -> Result<String> {
    if !path.is_absolute() {
        bail!(
            "service tool path for {label} must be absolute: {}",
            path.display()
        );
    }

    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("inspect service tool {label} at {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "service tool {label} must be a pinned regular non-symlink file: {}",
            path.display()
        );
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            bail!("service tool {label} is not executable: {}", path.display());
        }
    }

    path.to_str()
        .map(str::to_owned)
        .with_context(|| format!("service tool {label} path is not UTF-8: {}", path.display()))
}

fn save_settings(root: &Path, settings: &Settings) -> Result<()> {
    let path = settings_path(root);
    let bytes = serde_json::to_vec_pretty(settings)?;
    write_private_file_atomic(&path, &bytes)?;
    return Ok(());
}

fn load_desired_state(root: &Path) -> Result<DesiredState> {
    let path = desired_state_path(root);
    if !path.exists() {
        return Ok(DesiredState::default());
    }

    let bytes = read_private_file_bounded(&path, MAX_STATE_FILE_BYTES)?;
    let mut desired: DesiredState =
        serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;
    canonicalize_dns_route_state(&mut desired);
    validate_dns_route_state(&desired).with_context(|| format!("validate {}", path.display()))?;
    return Ok(desired);
}

fn canonicalize_dns_route_state(desired: &mut DesiredState) {
    if let Some(hostname) = desired
        .tunnel
        .as_mut()
        .and_then(|request| request.hostname.as_mut())
    {
        *hostname = hostname.to_ascii_lowercase();
    }
    for route in desired
        .known_dns_routes
        .iter_mut()
        .chain(desired.pending_dns_routes.iter_mut())
    {
        route.hostname = route.hostname.to_ascii_lowercase();
    }
}

fn validate_dns_route_state(desired: &DesiredState) -> Result<()> {
    let total = desired
        .known_dns_routes
        .len()
        .saturating_add(desired.pending_dns_routes.len());
    if total > MAX_DNS_ROUTES {
        bail!("desired state contains {total} DNS routes; maximum is {MAX_DNS_ROUTES}");
    }

    let mut known = HashSet::new();
    for route in &desired.known_dns_routes {
        validate_dns_route(route)?;
        if !known.insert(route.clone()) {
            bail!("desired state contains duplicate known DNS route");
        }
    }

    let mut pending = HashSet::new();
    for route in &desired.pending_dns_routes {
        validate_dns_route(route)?;
        if known.contains(route) {
            bail!("desired state marks the same DNS route as both known and pending");
        }
        if !pending.insert(route.clone()) {
            bail!("desired state contains duplicate pending DNS route");
        }
    }
    return Ok(());
}

fn validate_dns_route(route: &DnsRoute) -> Result<()> {
    let request = TunnelRequest {
        name: route.tunnel.clone(),
        hostname: Some(route.hostname.clone()),
        config: None,
    };
    return validate_tunnel_request(&request);
}

fn save_desired_state(root: &Path, daemon: &DaemonState) -> Result<()> {
    let desired = DesiredState {
        runtime: daemon.last_runtime.clone(),
        tunnel: daemon.last_tunnel.clone(),
        known_dns_routes: daemon.known_dns_routes.clone(),
        pending_dns_routes: daemon.pending_dns_routes.clone(),
    };
    let path = desired_state_path(root);
    let bytes = serde_json::to_vec_pretty(&desired)?;
    write_private_file_atomic(&path, &bytes)?;
    return Ok(());
}

fn load_replay_state(root: &Path) -> Result<ReplayState> {
    let path = replay_state_path(root);
    if !path.exists() {
        return Ok(ReplayState::default());
    }

    let bytes = read_private_file_bounded(&path, MAX_STATE_FILE_BYTES)?;
    let replay: ReplayState =
        serde_json::from_slice(&bytes).with_context(|| format!("parse {}", path.display()))?;

    if replay.keys.len() > MAX_RECENT_IDEMPOTENCY_KEYS {
        bail!(
            "replay state contains {} keys; maximum is {}",
            replay.keys.len(),
            MAX_RECENT_IDEMPOTENCY_KEYS
        );
    }

    let mut unique = HashSet::new();
    for key in &replay.keys {
        if !valid_idempotency_key(key) {
            bail!("replay state contains an invalid idempotency key");
        }
        if !unique.insert(key.clone()) {
            bail!("replay state contains a duplicate idempotency key");
        }
    }

    return Ok(replay);
}

fn replay_collections(replay: ReplayState) -> Result<(HashSet<String>, VecDeque<String>)> {
    let mut recent = HashSet::new();
    let mut order = VecDeque::new();

    for key in replay.keys {
        if !recent.insert(key.clone()) {
            bail!("replay state contains a duplicate idempotency key");
        }
        order.push_back(key);
    }

    return Ok((recent, order));
}

fn save_replay_state(root: &Path, daemon: &DaemonState) -> Result<()> {
    let replay = ReplayState {
        keys: daemon.idempotency_order.iter().cloned().collect(),
    };
    let path = replay_state_path(root);
    let bytes = serde_json::to_vec_pretty(&replay)?;
    write_private_file_atomic(&path, &bytes)?;
    return Ok(());
}

fn secure_data_root(root: &Path) -> Result<()> {
    let metadata = fs::symlink_metadata(root)
        .with_context(|| format!("inspect private state root {}", root.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_dir() {
        bail!(
            "private state root must be a real directory, not a symlink: {}",
            root.display()
        );
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(root, fs::Permissions::from_mode(0o700))
            .with_context(|| format!("set private permissions on {}", root.display()))?;
    }
    #[cfg(windows)]
    {
        harden_windows_state_root(root)?;
    }
    return Ok(());
}

#[cfg(windows)]
fn current_windows_identity() -> Result<String> {
    let whoami = trusted_system_tool(&[r"C:\Windows\System32\whoami.exe"], "whoami.exe")?;
    let whoami_text = whoami.to_string_lossy().to_string();
    let (status, stdout, stderr) =
        run_probe_bounded(&whoami_text, &[], Duration::from_secs(5), 4096)
            .context("run fixed whoami for state ACL")?;
    if !status.success() {
        bail!(
            "fixed whoami failed while securing state ACL: {}",
            String::from_utf8_lossy(&stderr).trim()
        );
    }
    let identity = String::from_utf8(stdout).context("fixed whoami returned non-UTF8 identity")?;
    let identity = identity.trim();
    if identity.is_empty()
        || identity.len() > 512
        || identity
            .chars()
            .any(|ch| ch == '\n' || ch == '\r' || ch == '\0')
    {
        bail!("fixed whoami returned an invalid account identity");
    }
    Ok(identity.to_string())
}

#[cfg(windows)]
fn run_icacls(args: &[String]) -> Result<()> {
    let icacls = trusted_system_tool(&[r"C:\Windows\System32\icacls.exe"], "icacls.exe")?;
    let mut command = Command::new(icacls);
    command.args(args);
    let status = run_status_with_timeout(
        &mut command,
        "secure Windows BeamScale state ACL",
        Duration::from_secs(30),
    )?;
    if !status.success() {
        bail!("icacls failed while securing BeamScale state with {status}");
    }
    Ok(())
}

#[cfg(windows)]
fn harden_windows_state_root(root: &Path) -> Result<()> {
    let root_text = root.to_string_lossy().to_string();
    if root_text
        .chars()
        .any(|ch| ch == '\n' || ch == '\r' || ch == '\0')
    {
        bail!("private Windows state root contains unsupported control characters");
    }

    let identity = current_windows_identity()?;
    let user_grant = format!("{identity}:(OI)(CI)F");

    run_icacls(&[
        root_text.clone(),
        "/inheritance:r".into(),
        "/T".into(),
        "/C".into(),
    ])?;

    for sid in ["*S-1-1-0", "*S-1-5-11", "*S-1-5-32-545", "*S-1-5-32-546"] {
        run_icacls(&[
            root_text.clone(),
            "/remove:g".into(),
            sid.into(),
            "/T".into(),
            "/C".into(),
        ])?;
        run_icacls(&[
            root_text.clone(),
            "/remove:d".into(),
            sid.into(),
            "/T".into(),
            "/C".into(),
        ])?;
    }

    run_icacls(&[
        root_text,
        "/grant:r".into(),
        user_grant,
        "*S-1-5-18:(OI)(CI)F".into(),
        "/T".into(),
        "/C".into(),
    ])?;
    Ok(())
}

fn load_or_create_token(root: &Path) -> Result<String> {
    if let Ok(token) = env::var("BMSCL_DAEMON_TOKEN") {
        if !token.is_empty() {
            return validate_secret_token(&token, "BMSCL_DAEMON_TOKEN");
        }
    }

    let path = token_path(root);
    if path.exists() {
        let bytes = read_private_file_bounded(&path, MAX_TOKEN_BYTES)?;
        let token = String::from_utf8(bytes)
            .with_context(|| format!("daemon token {} must be UTF-8", path.display()))?;
        return validate_secret_token(&token, &path.display().to_string());
    }

    let mut bytes = [0_u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    let token = bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    write_private_file_atomic(&path, token.as_bytes())?;
    return Ok(token);
}

fn load_or_create_operator_token(root: &Path) -> Result<String> {
    if let Ok(token) = env::var("BMSCL_DAEMON_OPERATOR_TOKEN") {
        if !token.is_empty() {
            return validate_secret_token(&token, "BMSCL_DAEMON_OPERATOR_TOKEN");
        }
    }

    let path = operator_token_path(root);
    if path.exists() {
        let bytes = read_private_file_bounded(&path, MAX_TOKEN_BYTES)?;
        let token = String::from_utf8(bytes)
            .with_context(|| format!("operator token {} must be UTF-8", path.display()))?;
        return validate_secret_token(&token, &path.display().to_string());
    }

    let mut bytes = [0_u8; 32];
    rand::rng().fill_bytes(&mut bytes);
    let token = bytes
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    write_private_file_atomic(&path, token.as_bytes())?;
    return Ok(token);
}

fn validate_secret_token(raw: &str, source: &str) -> Result<String> {
    let bytes = raw.as_bytes();
    if bytes.len() < MIN_TOKEN_BYTES || bytes.len() > MAX_TOKEN_BYTES {
        bail!(
            "secret token from {source} must contain {MIN_TOKEN_BYTES}..={MAX_TOKEN_BYTES} bytes"
        );
    }
    if bytes.iter().any(|byte| *byte < 0x21 || *byte > 0x7e) {
        bail!("secret token from {source} must contain visible ASCII bytes only");
    }
    return Ok(raw.to_string());
}

#[cfg(not(unix))]
fn secure_private_file(path: &Path) -> Result<fs::Metadata> {
    let metadata = fs::symlink_metadata(path)
        .with_context(|| format!("inspect private state file {}", path.display()))?;
    if metadata.file_type().is_symlink() || !metadata.file_type().is_file() {
        bail!(
            "private state file must be a regular non-symlink file: {}",
            path.display()
        );
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600))
            .with_context(|| format!("set private permissions on {}", path.display()))?;
    }
    return fs::metadata(path).with_context(|| format!("stat private file {}", path.display()));
}

fn read_private_file_bounded(path: &Path, maximum: usize) -> Result<Vec<u8>> {
    let path_before = fs::symlink_metadata(path)
        .with_context(|| format!("inspect private state file {}", path.display()))?;
    if path_before.file_type().is_symlink() || !path_before.file_type().is_file() {
        bail!(
            "private state file must be a regular non-symlink file: {}",
            path.display()
        );
    }
    if path_before.len() > maximum as u64 {
        bail!(
            "private state file {} is {} bytes; maximum is {}",
            path.display(),
            path_before.len(),
            maximum
        );
    }

    let mut file = fs::File::open(path)
        .with_context(|| format!("open private state file {}", path.display()))?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))
            .with_context(|| format!("set private permissions on opened {}", path.display()))?;
    }

    let opened_before = file
        .metadata()
        .with_context(|| format!("inspect opened private state file {}", path.display()))?;
    ensure_private_path_matches_open_file(
        &path_before,
        &opened_before,
        path,
        "private state pathname changed between inspection and open",
    )?;

    let mut bytes = Vec::new();
    std::io::Read::by_ref(&mut file)
        .take(maximum as u64 + 1)
        .read_to_end(&mut bytes)
        .with_context(|| format!("read private state file {}", path.display()))?;
    if bytes.len() > maximum {
        bail!(
            "private state file {} exceeded {} bytes while reading",
            path.display(),
            maximum
        );
    }

    let opened_after = file
        .metadata()
        .with_context(|| format!("re-inspect opened private state file {}", path.display()))?;
    ensure_private_path_matches_open_file(
        &opened_before,
        &opened_after,
        path,
        "private state opened-file identity changed while reading",
    )?;
    if opened_after.len() != bytes.len() as u64 {
        bail!(
            "private state file changed size while reading: {}",
            path.display()
        );
    }

    let path_after = fs::symlink_metadata(path)
        .with_context(|| format!("re-inspect private state pathname {}", path.display()))?;
    if path_after.file_type().is_symlink() || !path_after.file_type().is_file() {
        bail!(
            "private state pathname changed to a symlink/non-file while reading: {}",
            path.display()
        );
    }
    ensure_private_path_matches_open_file(
        &path_after,
        &opened_after,
        path,
        "private state pathname changed while reading",
    )?;

    Ok(bytes)
}

fn ensure_private_path_matches_open_file(
    path_metadata: &fs::Metadata,
    opened_metadata: &fs::Metadata,
    path: &Path,
    phase: &str,
) -> Result<()> {
    if path_metadata.len() != opened_metadata.len() {
        bail!("{phase}: {}", path.display());
    }

    let path_modified = path_metadata.modified().ok();
    let opened_modified = opened_metadata.modified().ok();
    if path_modified.is_some() && opened_modified.is_some() && path_modified != opened_modified {
        bail!("{phase}: {}", path.display());
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if path_metadata.dev() != opened_metadata.dev()
            || path_metadata.ino() != opened_metadata.ino()
        {
            bail!("{phase}: {}", path.display());
        }
    }

    Ok(())
}

fn write_private_file_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    let parent = path
        .parent()
        .context("private state file must have a parent directory")?;
    let file_name = path
        .file_name()
        .context("private state file must have a file name")?
        .to_string_lossy();
    let temporary = parent.join(format!(
        ".{file_name}.{}.{}.tmp",
        std::process::id(),
        rand::random::<u64>()
    ));

    let result = (|| -> Result<()> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};

            let mut file = fs::OpenOptions::new()
                .create_new(true)
                .write(true)
                .mode(0o600)
                .open(&temporary)
                .with_context(|| format!("open {}", temporary.display()))?;
            file.write_all(bytes)
                .with_context(|| format!("write {}", temporary.display()))?;
            file.sync_all()
                .with_context(|| format!("sync {}", temporary.display()))?;
            fs::set_permissions(&temporary, fs::Permissions::from_mode(0o600))
                .with_context(|| format!("set private permissions on {}", temporary.display()))?;
            fs::rename(&temporary, path)
                .with_context(|| format!("atomically replace {}", path.display()))?;
            fs::File::open(parent)
                .with_context(|| format!("open state directory {}", parent.display()))?
                .sync_all()
                .with_context(|| format!("sync state directory {}", parent.display()))?;
            return Ok(());
        }

        #[cfg(not(unix))]
        {
            fs::write(&temporary, bytes)
                .with_context(|| format!("write {}", temporary.display()))?;
            secure_private_file(&temporary)?;
            if path.exists() {
                fs::remove_file(path).with_context(|| format!("replace {}", path.display()))?;
            }
            fs::rename(&temporary, path)
                .with_context(|| format!("commit private state {}", path.display()))?;
            secure_private_file(path)?;
            return Ok(());
        }
    })();

    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    return result;
}

fn record_lifecycle_event(
    daemon: &mut DaemonState,
    component: &'static str,
    action: &'static str,
    outcome: &'static str,
    detail: impl Into<String>,
) {
    let mut detail = detail.into();
    if detail.len() > MAX_EVENT_DETAIL_BYTES {
        let mut boundary = MAX_EVENT_DETAIL_BYTES;
        while boundary > 0 && !detail.is_char_boundary(boundary) {
            boundary -= 1;
        }
        detail.truncate(boundary);
    }
    let sequence = daemon
        .lifecycle_events
        .back()
        .map(|event| event.sequence.saturating_add(1))
        .unwrap_or(1);
    daemon.lifecycle_events.push_back(LifecycleEvent {
        sequence,
        at_unix_ms: now_unix_ms(),
        component,
        action,
        outcome,
        detail,
    });
    while daemon.lifecycle_events.len() > MAX_LIFECYCLE_EVENTS {
        daemon.lifecycle_events.pop_front();
    }
}

fn now_unix_ms() -> u128 {
    return SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(unix)]
    #[test]
    fn private_state_identity_rejects_same_sized_different_files() {
        let root = tempfile::tempdir().expect("temp root");
        let first = root.path().join("first");
        let second = root.path().join("second");
        fs::write(&first, b"same").expect("write first");
        fs::write(&second, b"size").expect("write second");

        let first_metadata = fs::symlink_metadata(&first).expect("first metadata");
        let second_file = fs::File::open(&second).expect("open second");
        let second_metadata = second_file.metadata().expect("second metadata");
        assert!(
            ensure_private_path_matches_open_file(
                &first_metadata,
                &second_metadata,
                &first,
                "identity mismatch",
            )
            .is_err()
        );
    }

    #[cfg(unix)]
    #[test]
    fn private_state_read_hardens_mode_on_opened_file() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let path = root.path().join("state.json");
        fs::write(&path, b"{}").expect("write state");
        fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).expect("loosen state mode");

        assert_eq!(read_private_file_bounded(&path, 16).expect("read"), b"{}");
        let mode = fs::metadata(&path).expect("metadata").permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn service_helper_scrubs_cloud_and_admin_credentials() {
        let source = include_str!("main.rs");
        for key in [
            "BMSCL_ADMIN_TOKEN",
            "CLOUDFLARE_API_TOKEN",
            "CLOUDFLARE_API_KEY",
            "CF_API_TOKEN",
            "CF_API_KEY",
        ] {
            assert!(
                source.contains(key),
                "service-helper scrub contract must include {key}"
            );
        }
        assert!(source.contains("run_probe_bounded_with_extra_scrub"));
    }

    #[test]
    fn maintenance_lease_clears_flag_on_drop() {
        let state = AppState {
            inner: Arc::new(Mutex::new(DaemonState {
                settings: Settings::default(),
                supervisor_root: None,
                supervisor_ebin_sha256: None,
                runtime: None,
                tunnel: None,
                keep_awake: None,
                last_runtime: None,
                last_tunnel: None,
                known_dns_routes: Vec::new(),
                pending_dns_routes: Vec::new(),
                recent_idempotency: HashSet::new(),
                idempotency_order: VecDeque::new(),
                maintenance_in_progress: true,
                runtime_restart: RestartBackoff::default(),
                tunnel_restart: RestartBackoff::default(),
                lifecycle_events: VecDeque::new(),
            })),
            token: Arc::new("normal-test-token-1234567890".into()),
            operator_token: Arc::new("operator-test-token-1234567890".into()),
            data_root: Arc::new(PathBuf::from(".")),
            client: reqwest::Client::new(),
            ingress_url: Arc::new("http://127.0.0.1:8080".into()),
            runtime_manifest_path: None,
        };

        {
            let _lease = MaintenanceLease::new(&state);
            assert!(state.inner.lock().expect("lock").maintenance_in_progress);
        }
        assert!(!state.inner.lock().expect("lock").maintenance_in_progress);
    }

    #[cfg(unix)]
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn service_mutation_lease_survives_aborted_waiter() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let helper_path = root.path().join("beamscale-service");
        let marker = root.path().join("started");
        fs::write(&helper_path, b"#!/bin/sh\n: > \"$1\"\nsleep 1\nexit 0\n").expect("write helper");
        fs::set_permissions(&helper_path, fs::Permissions::from_mode(0o700)).expect("chmod helper");
        let pinned = PinnedServiceHelper {
            path: helper_path.clone(),
            sha256: executable_sha256(&helper_path, "beamscale-service").expect("hash helper"),
        };

        let state = AppState {
            inner: Arc::new(Mutex::new(DaemonState {
                settings: Settings::default(),
                supervisor_root: None,
                supervisor_ebin_sha256: None,
                runtime: None,
                tunnel: None,
                keep_awake: None,
                last_runtime: None,
                last_tunnel: None,
                known_dns_routes: Vec::new(),
                pending_dns_routes: Vec::new(),
                recent_idempotency: HashSet::new(),
                idempotency_order: VecDeque::new(),
                maintenance_in_progress: true,
                runtime_restart: RestartBackoff::default(),
                tunnel_restart: RestartBackoff::default(),
                lifecycle_events: VecDeque::new(),
            })),
            token: Arc::new("normal-test-token-1234567890".into()),
            operator_token: Arc::new("operator-test-token-1234567890".into()),
            data_root: Arc::new(root.path().to_path_buf()),
            client: reqwest::Client::new(),
            ingress_url: Arc::new("http://127.0.0.1:8080".into()),
            runtime_manifest_path: None,
        };

        let maintenance = MaintenanceLease::new(&state);
        let marker_arg = marker.to_string_lossy().to_string();
        let task = tokio::spawn(run_service_mutation_async(
            pinned,
            vec![marker_arg],
            Duration::from_secs(3),
            maintenance,
        ));

        for _ in 0..100 {
            if marker.exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        assert!(marker.exists(), "helper did not start");

        task.abort();
        tokio::task::yield_now().await;
        assert!(
            state.inner.lock().expect("lock").maintenance_in_progress,
            "aborting the waiter must not release maintenance while spawn_blocking still runs"
        );

        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            if !state.inner.lock().expect("lock").maintenance_in_progress {
                break;
            }
            assert!(
                Instant::now() < deadline,
                "maintenance lease never released"
            );
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    }

    #[cfg(unix)]
    #[test]
    fn pinned_service_helper_detects_content_drift() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let helper_path = root.path().join("beamscale-service");
        fs::write(&helper_path, b"#!/bin/sh\nexit 0\n").expect("write helper");
        fs::set_permissions(&helper_path, fs::Permissions::from_mode(0o700)).expect("chmod helper");
        let pinned = PinnedServiceHelper {
            path: helper_path.clone(),
            sha256: executable_sha256(&helper_path, "beamscale-service").expect("hash helper"),
        };

        fs::write(&helper_path, b"#!/bin/sh\nexit 7\n").expect("replace helper");
        fs::set_permissions(&helper_path, fs::Permissions::from_mode(0o700))
            .expect("chmod replacement");

        let error = run_service_helper(&pinned, &[], Duration::from_secs(1))
            .expect_err("content drift must be rejected before execution");
        assert!(
            error.to_string().contains("changed after validation"),
            "{error:#}"
        );
    }

    #[test]
    fn service_status_response_rejects_unknown_manager_and_bad_path() {
        let valid = ServiceStatusResponse {
            installed: true,
            running: Some(true),
            manager: "systemd-user".into(),
            definition_path: "/tmp/unit.service".into(),
        };
        assert!(validate_service_status_response(&valid).is_ok());

        let mut unknown = valid;
        unknown.manager = "shell".into();
        assert!(validate_service_status_response(&unknown).is_err());

        let invalid_path = ServiceStatusResponse {
            installed: true,
            running: Some(false),
            manager: "launchd-user".into(),
            definition_path: "bad\npath".into(),
        };
        assert!(validate_service_status_response(&invalid_path).is_err());

        let impossible = ServiceStatusResponse {
            installed: false,
            running: Some(false),
            manager: "scheduled-task".into(),
            definition_path: "BeamScale Desktop Daemon".into(),
        };
        assert!(validate_service_status_response(&impossible).is_err());
    }

    #[test]
    fn operator_service_install_uses_registration_only_handover() {
        let source = include_str!("main.rs");
        assert!(source.contains(r#""--no-start".to_string()"#));
        assert!(source.contains("current daemon remains authoritative"));
    }

    #[test]
    fn service_install_request_rejects_unknown_fields() {
        assert!(
            serde_json::from_str::<ServiceInstallRequest>(
                r#"{\"supervisor_root\":null,\"unexpected\":true}"#
            )
            .is_err()
        );
    }

    #[test]
    fn lifecycle_event_cursor_ahead_of_journal_is_a_gap() {
        let since = 500_u64;
        let oldest_sequence = 1_u64;
        let latest_sequence = 3_u64;
        let gap_detected = since > 0
            && (since > latest_sequence
                || (oldest_sequence > 0 && since.saturating_add(1) < oldest_sequence));
        assert!(gap_detected);
    }

    #[test]
    fn lifecycle_event_query_defaults_are_bounded() {
        let defaults = LifecycleEventsQuery::default();
        assert_eq!(defaults.since_sequence, None);
        assert_eq!(defaults.limit, None);
        assert_eq!(
            defaults.limit.unwrap_or(100).clamp(1, MAX_LIFECYCLE_EVENTS),
            100
        );
        assert_eq!(
            Some(0_usize).unwrap_or(100).clamp(1, MAX_LIFECYCLE_EVENTS),
            1
        );
        assert_eq!(
            Some(MAX_LIFECYCLE_EVENTS + 100)
                .unwrap_or(100)
                .clamp(1, MAX_LIFECYCLE_EVENTS),
            MAX_LIFECYCLE_EVENTS
        );
    }

    #[test]
    fn lifecycle_event_detail_truncation_preserves_utf8_boundaries() {
        let mut daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };
        record_lifecycle_event(
            &mut daemon,
            "runtime",
            "test",
            "ok",
            "é".repeat(MAX_EVENT_DETAIL_BYTES),
        );
        let detail = &daemon.lifecycle_events.back().expect("event").detail;
        assert!(detail.len() <= MAX_EVENT_DETAIL_BYTES);
        assert!(std::str::from_utf8(detail.as_bytes()).is_ok());
    }

    #[test]
    fn lifecycle_event_journal_is_bounded_and_monotonic() {
        let mut daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        for index in 0..(MAX_LIFECYCLE_EVENTS + 20) {
            record_lifecycle_event(
                &mut daemon,
                "runtime",
                "test",
                "ok",
                format!("event-{index}"),
            );
        }

        assert_eq!(daemon.lifecycle_events.len(), MAX_LIFECYCLE_EVENTS);
        assert_eq!(
            daemon.lifecycle_events.front().map(|event| event.sequence),
            Some(21)
        );
        assert_eq!(
            daemon.lifecycle_events.back().map(|event| event.sequence),
            Some((MAX_LIFECYCLE_EVENTS + 20) as u64)
        );
    }

    #[test]
    fn windows_acl_tools_use_fixed_system32_paths() {
        let source = include_str!("main.rs");
        assert!(source.contains(r#"C:\Windows\System32\whoami.exe"#));
        assert!(source.contains(r#"C:\Windows\System32\icacls.exe"#));

        let forbidden_icacls = ["Command::new(", "\"icacls.exe\"", ")"].concat();
        let forbidden_whoami = ["Command::new(", "\"whoami.exe\"", ")"].concat();
        assert!(!source.contains(&forbidden_icacls));
        assert!(!source.contains(&forbidden_whoami));
    }

    #[cfg(unix)]
    #[test]
    fn pinned_path_identity_rejects_same_sized_different_files() {
        let root = tempfile::tempdir().expect("temp root");
        let first = root.path().join("first");
        let second = root.path().join("second");
        fs::write(&first, b"same").expect("write first");
        fs::write(&second, b"size").expect("write second");

        let first_metadata = fs::symlink_metadata(&first).expect("first metadata");
        let second_file = fs::File::open(&second).expect("open second");
        let second_metadata = second_file.metadata().expect("second metadata");
        assert!(
            ensure_pinned_path_matches_open_file(
                &first_metadata,
                &second_metadata,
                &first,
                "test",
                "identity mismatch",
            )
            .is_err()
        );
    }

    #[test]
    fn secret_tokens_require_visible_ascii() {
        assert!(validate_secret_token(&"a".repeat(MIN_TOKEN_BYTES), "test").is_ok());
        assert!(
            validate_secret_token(&format!("{}é", "a".repeat(MIN_TOKEN_BYTES)), "test",).is_err()
        );
        assert!(
            validate_secret_token(&format!("{} ", "a".repeat(MIN_TOKEN_BYTES)), "test",).is_err()
        );
    }

    #[test]
    fn child_processes_do_not_inherit_control_tokens() {
        let mut command = Command::new("ignored");
        command
            .env("BMSCL_DAEMON_TOKEN", "daemon-secret")
            .env("BMSCL_DAEMON_OPERATOR_TOKEN", "operator-secret")
            .env("BMSCL_LOCAL_INGRESS_TOKEN", "ingress-secret");
        scrub_daemon_secret(&mut command);

        let envs = command
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().to_string(),
                    value.map(|value| value.to_string_lossy().to_string()),
                )
            })
            .collect::<std::collections::HashMap<_, _>>();

        assert_eq!(envs.get("BMSCL_DAEMON_TOKEN"), Some(&None));
        assert_eq!(envs.get("BMSCL_DAEMON_OPERATOR_TOKEN"), Some(&None));
        assert_eq!(envs.get("BMSCL_LOCAL_INGRESS_TOKEN"), Some(&None));
    }

    #[cfg(unix)]
    #[test]
    fn bounded_probe_rejects_excessive_output() {
        let result = run_probe_bounded(
            "sh",
            &["-c", "yes x | head -c 4096"],
            Duration::from_secs(2),
            1024,
        );
        assert!(result.is_err());
    }

    #[cfg(unix)]
    #[test]
    fn managed_tree_kills_descendant_that_ignores_sigterm() {
        use nix::{errno::Errno, sys::signal::kill, unistd::Pid};

        let root = tempfile::tempdir().expect("temp root");
        let pid_file = root.path().join("descendant.pid");
        let script = format!(
            "trap 'exit 0' TERM; (trap '' TERM; sleep 30) & child=$!; printf '%s' \"$child\" > {}; wait \"$child\"",
            pid_file.display()
        );

        let mut command = Command::new("sh");
        command.args(["-c", &script]);
        configure_process_tree(&mut command);
        let mut child = command.spawn().expect("spawn managed tree");

        for _ in 0..100 {
            if pid_file.exists() {
                break;
            }
            thread::sleep(Duration::from_millis(10));
        }
        let descendant = fs::read_to_string(&pid_file)
            .expect("read descendant pid")
            .parse::<i32>()
            .expect("parse descendant pid");

        terminate_process_tree(&mut child).expect("terminate tree");

        for _ in 0..50 {
            match kill(Pid::from_raw(descendant), None) {
                Err(Errno::ESRCH) => return,
                Ok(()) | Err(_) => thread::sleep(Duration::from_millis(10)),
            }
        }
        panic!("managed process teardown left descendant {descendant} alive");
    }

    #[cfg(unix)]
    #[test]
    fn bounded_probe_does_not_wait_for_inherited_descendant_pipe() {
        let started = Instant::now();
        let result = run_probe_bounded(
            "sh",
            &["-c", "sleep 30 & printf ok"],
            Duration::from_millis(100),
            1024,
        );
        let error = result.expect_err("inherited descendant pipe must not outlive deadline");
        assert!(error.to_string().contains("did not close before deadline"));
        assert!(started.elapsed() < Duration::from_secs(1));
    }

    #[cfg(unix)]
    #[test]
    fn bounded_probe_times_out() {
        let result = run_probe_bounded("sh", &["-c", "sleep 5"], Duration::from_millis(50), 1024);
        assert!(result.is_err());
    }

    #[test]
    fn desired_state_round_trips_atomically() {
        let root = tempfile::tempdir().expect("tempdir");
        let runtime = RuntimeRequest {
            project_dir: root.path().to_path_buf(),
            poll_ms: 125,
            module: Some("hello".into()),
        };
        let tunnel = TunnelRequest {
            name: "beamscale-local".into(),
            hostname: Some("dev.example.com".into()),
            config: Some(root.path().join("cloudflared.yml")),
        };
        let daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: Some(runtime.clone()),
            last_tunnel: Some(tunnel.clone()),
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        save_desired_state(root.path(), &daemon).expect("save desired state");
        let loaded = load_desired_state(root.path()).expect("load desired state");

        assert_eq!(
            loaded,
            DesiredState {
                runtime: Some(runtime),
                tunnel: Some(tunnel),
                known_dns_routes: Vec::new(),
                pending_dns_routes: Vec::new(),
            }
        );
        let leftovers = fs::read_dir(root.path())
            .expect("read state directory")
            .filter_map(|entry| entry.ok())
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".tmp"))
            .count();
        assert_eq!(leftovers, 0);
    }

    #[test]
    fn dns_route_state_rejects_duplicates_and_overlap() {
        let route = DnsRoute {
            tunnel: "beam-local".into(),
            hostname: "dev.example.com".into(),
        };
        let duplicate_known = DesiredState {
            runtime: None,
            tunnel: None,
            known_dns_routes: vec![route.clone(), route.clone()],
            pending_dns_routes: Vec::new(),
        };
        assert!(validate_dns_route_state(&duplicate_known).is_err());

        let overlap = DesiredState {
            runtime: None,
            tunnel: None,
            known_dns_routes: vec![route.clone()],
            pending_dns_routes: vec![route],
        };
        assert!(validate_dns_route_state(&overlap).is_err());
    }

    #[test]
    fn pending_dns_routes_survive_restart_for_reconciliation() {
        let root = tempfile::tempdir().expect("tempdir");
        let route = DnsRoute {
            tunnel: "beam-local".into(),
            hostname: "pending.example.com".into(),
        };
        let daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: vec![route.clone()],
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        save_desired_state(root.path(), &daemon).expect("save pending DNS state");
        let loaded = load_desired_state(root.path()).expect("load pending DNS state");
        assert_eq!(loaded.pending_dns_routes, vec![route]);
    }

    #[test]
    fn remembered_dns_routes_survive_tunnel_stop_intent() {
        let root = tempfile::tempdir().expect("tempdir");
        let route = DnsRoute {
            tunnel: "beam-local".into(),
            hostname: "dev.example.com".into(),
        };
        let daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: vec![route.clone()],
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        save_desired_state(root.path(), &daemon).expect("save desired state");
        let loaded = load_desired_state(root.path()).expect("load desired state");
        assert_eq!(loaded.tunnel, None);
        assert_eq!(loaded.known_dns_routes, vec![route]);
    }

    #[test]
    fn cleared_runtime_intent_stays_cleared() {
        let root = tempfile::tempdir().expect("tempdir");
        let mut daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: Some(RuntimeRequest {
                project_dir: root.path().to_path_buf(),
                poll_ms: 250,
                module: None,
            }),
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        save_desired_state(root.path(), &daemon).expect("save initial desired state");
        daemon.last_runtime = None;
        save_desired_state(root.path(), &daemon).expect("save stopped desired state");

        let loaded = load_desired_state(root.path()).expect("load stopped desired state");
        assert_eq!(loaded.runtime, None);
    }

    #[test]
    fn restart_backoff_is_capped_and_resettable() {
        let mut backoff = RestartBackoff::default();
        assert!(backoff.ready());
        let first = backoff.record_failure();
        assert_eq!(first, Duration::from_secs(1));
        assert!(!backoff.ready());

        let mut last = first;
        for _ in 0..10 {
            last = backoff.record_failure();
        }
        assert_eq!(last, Duration::from_secs(64));
        assert!(backoff.failures >= 11);

        backoff.reset();
        assert_eq!(backoff.failures, 0);
        assert!(backoff.next_attempt.is_none());
        assert!(backoff.ready());
    }

    #[test]
    fn runtime_validation_requires_directory_and_nonempty_module() {
        let root = tempfile::tempdir().expect("tempdir");
        let ok = RuntimeRequest {
            project_dir: root.path().to_path_buf(),
            poll_ms: 1,
            module: Some("lambda".into()),
        };
        assert!(validate_runtime_request(&ok).is_ok());

        let zero_poll = RuntimeRequest {
            poll_ms: 0,
            ..ok.clone()
        };
        assert!(validate_runtime_request(&zero_poll).is_err());

        let empty_module = RuntimeRequest {
            module: Some("   ".into()),
            ..ok.clone()
        };
        assert!(validate_runtime_request(&empty_module).is_err());

        let missing = RuntimeRequest {
            project_dir: root.path().join("missing"),
            ..ok
        };
        assert!(validate_runtime_request(&missing).is_err());
    }

    #[test]
    fn tunnel_validation_rejects_urls_ports_and_missing_config() {
        let root = tempfile::tempdir().expect("tempdir");

        let ok = TunnelRequest {
            name: "beamscale-local".into(),
            hostname: Some("dev.example.com".into()),
            config: None,
        };
        assert!(validate_tunnel_request(&ok).is_ok());

        for hostname in [
            "https://dev.example.com",
            "dev.example.com:443",
            " dev.example.com",
            "dev.example.com ",
            "dev example.com",
            "-bad.example.com",
            "bad-.example.com",
            "singlelabel",
            ".example.com",
            "example.com.",
        ] {
            let invalid = TunnelRequest {
                hostname: Some(hostname.into()),
                ..ok.clone()
            };
            assert!(validate_tunnel_request(&invalid).is_err(), "{hostname}");
        }

        let wildcard = TunnelRequest {
            hostname: Some("*.dev.example.com".into()),
            ..ok.clone()
        };
        assert!(validate_tunnel_request(&wildcard).is_ok());

        let edge_whitespace_name = TunnelRequest {
            name: " beamscale-local".into(),
            ..ok.clone()
        };
        assert!(validate_tunnel_request(&edge_whitespace_name).is_err());

        let invalid_name = TunnelRequest {
            name: "bad tunnel".into(),
            ..ok.clone()
        };
        assert!(validate_tunnel_request(&invalid_name).is_err());

        let custom_config = TunnelRequest {
            config: Some(root.path().join("cloudflared.yml")),
            ..ok.clone()
        };
        assert!(validate_tunnel_request(&custom_config).is_err());

        let settings = Settings::default();
        let command = build_tunnel_command(&settings, "http://127.0.0.1:8080", &ok)
            .expect("build pinned tunnel command");
        let args = command
            .get_args()
            .map(|arg| arg.to_string_lossy().to_string())
            .collect::<Vec<_>>();
        assert_eq!(
            args,
            vec![
                "tunnel",
                "--no-autoupdate",
                "run",
                "--url",
                "http://127.0.0.1:8080",
                "beamscale-local",
            ]
        );
        assert!(!args.iter().any(|arg| arg == "--config"));
    }

    #[test]
    fn authenticated_requests_require_protocol_version() {
        let mut headers = HeaderMap::new();
        headers.insert(
            "authorization",
            "Bearer test-token".parse().expect("authorization header"),
        );
        let state = AppState {
            inner: Arc::new(Mutex::new(DaemonState {
                settings: Settings::default(),
                supervisor_root: None,
                supervisor_ebin_sha256: None,
                runtime: None,
                tunnel: None,
                keep_awake: None,
                last_runtime: None,
                last_tunnel: None,
                known_dns_routes: Vec::new(),
                pending_dns_routes: Vec::new(),
                recent_idempotency: HashSet::new(),
                idempotency_order: VecDeque::new(),
                maintenance_in_progress: false,
                runtime_restart: RestartBackoff::default(),
                tunnel_restart: RestartBackoff::default(),
                lifecycle_events: VecDeque::new(),
            })),
            token: Arc::new("test-token".into()),
            operator_token: Arc::new("operator-test-token-1234567890".into()),
            data_root: Arc::new(PathBuf::from(".")),
            client: reqwest::Client::new(),
            ingress_url: Arc::new("http://127.0.0.1:8080".into()),
            runtime_manifest_path: None,
        };

        assert_eq!(
            require_auth(&headers, &state)
                .expect_err("protocol must be required")
                .0,
            StatusCode::PRECONDITION_FAILED
        );

        headers.insert(
            PROTOCOL_HEADER,
            API_VERSION.parse().expect("protocol header"),
        );
        assert!(require_auth(&headers, &state).is_ok());
    }

    #[test]
    fn operator_authority_rejects_normal_daemon_credential() {
        let state = AppState {
            inner: Arc::new(Mutex::new(DaemonState {
                settings: Settings::default(),
                supervisor_root: None,
                supervisor_ebin_sha256: None,
                runtime: None,
                tunnel: None,
                keep_awake: None,
                last_runtime: None,
                last_tunnel: None,
                known_dns_routes: Vec::new(),
                pending_dns_routes: Vec::new(),
                recent_idempotency: HashSet::new(),
                idempotency_order: VecDeque::new(),
                maintenance_in_progress: false,
                runtime_restart: RestartBackoff::default(),
                tunnel_restart: RestartBackoff::default(),
                lifecycle_events: VecDeque::new(),
            })),
            token: Arc::new("normal-test-token-1234567890".into()),
            operator_token: Arc::new("operator-test-token-1234567890".into()),
            data_root: Arc::new(PathBuf::from(".")),
            client: reqwest::Client::new(),
            ingress_url: Arc::new("http://127.0.0.1:8080".into()),
            runtime_manifest_path: None,
        };

        let mut headers = HeaderMap::new();
        headers.insert(
            PROTOCOL_HEADER,
            API_VERSION.parse().expect("protocol header"),
        );
        headers.insert(
            "authorization",
            "Bearer normal-test-token-1234567890"
                .parse()
                .expect("normal authorization"),
        );
        assert_eq!(
            require_operator_auth(&headers, &state)
                .expect_err("normal credential must not gain operator authority")
                .0,
            StatusCode::FORBIDDEN
        );

        headers.insert(
            "authorization",
            "Bearer operator-test-token-1234567890"
                .parse()
                .expect("operator authorization"),
        );
        assert!(require_operator_auth(&headers, &state).is_ok());
    }

    #[test]
    fn tunnel_names_cannot_be_option_like() {
        let valid = TunnelRequest {
            name: "beam-scale_1.prod".into(),
            hostname: Some("dev.example.com".into()),
            config: None,
        };
        assert!(validate_tunnel_request(&valid).is_ok());

        for name in [
            "--url",
            "-config",
            ".hidden",
            "_private",
            "trailing-",
            "trailing.",
        ] {
            let request = TunnelRequest {
                name: name.into(),
                hostname: Some("dev.example.com".into()),
                config: None,
            };
            assert!(
                validate_tunnel_request(&request).is_err(),
                "option-like/ambiguous tunnel name must fail: {name}"
            );
        }
    }

    #[test]
    fn local_ingress_must_be_numeric_loopback_http_origin() {
        assert!(validate_loopback_http_origin("http://127.0.0.1:8080").is_ok());
        assert!(validate_loopback_http_origin("http://[::1]:8080").is_ok());
        assert!(validate_loopback_http_origin("http://localhost:8080").is_err());
        assert!(validate_loopback_http_origin("https://127.0.0.1:8080").is_err());
        assert!(validate_loopback_http_origin("http://127.0.0.1").is_err());
        assert!(validate_loopback_http_origin("http://127.0.0.1:8080/path").is_err());
        assert!(validate_loopback_http_origin("http://example.com:8080").is_err());
    }

    #[test]
    fn secret_comparison_and_idempotency_admission_fail_closed() {
        assert!(constant_time_eq(b"same-token", b"same-token"));
        assert!(!constant_time_eq(b"same-token", b"same-tokee"));
        assert!(!constant_time_eq(b"short", b"longer"));

        let mut headers = HeaderMap::new();
        headers.insert(IDEMPOTENCY_HEADER, " request-1".parse().expect("header"));
        assert!(idempotency_key(&headers).is_err());
        headers.insert(IDEMPOTENCY_HEADER, "request-1 ".parse().expect("header"));
        assert!(idempotency_key(&headers).is_err());
        headers.insert(IDEMPOTENCY_HEADER, "request-1".parse().expect("header"));
        assert_eq!(idempotency_key(&headers).expect("key"), "request-1");
    }

    #[test]
    fn duplicate_idempotency_keys_are_rejected_and_bounded() {
        let mut daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        assert!(reject_replayed_idempotency(&daemon, "first").is_ok());
        remember_idempotency(&mut daemon, "first".into());
        assert_eq!(
            reject_replayed_idempotency(&daemon, "first")
                .expect_err("duplicate key must fail")
                .0,
            StatusCode::CONFLICT
        );

        for index in 0..(MAX_RECENT_IDEMPOTENCY_KEYS + 10) {
            remember_idempotency(&mut daemon, format!("key-{index}"));
        }
        assert!(daemon.idempotency_order.len() <= MAX_RECENT_IDEMPOTENCY_KEYS);
        assert!(daemon.recent_idempotency.len() <= MAX_RECENT_IDEMPOTENCY_KEYS);
    }

    #[test]
    fn replay_state_rejects_invalid_or_duplicate_keys() {
        let root = tempfile::tempdir().expect("tempdir");
        let path = replay_state_path(root.path());

        write_private_file_atomic(&path, br#"{"keys":["valid","bad key"]}"#)
            .expect("write invalid replay state");
        assert!(load_replay_state(root.path()).is_err());

        write_private_file_atomic(&path, br#"{"keys":["duplicate","duplicate"]}"#)
            .expect("write duplicate replay state");
        assert!(load_replay_state(root.path()).is_err());
    }

    #[test]
    fn replay_state_survives_restart_and_stays_bounded() {
        let root = tempfile::tempdir().expect("tempdir");
        let mut daemon = DaemonState {
            settings: Settings::default(),
            supervisor_root: None,
            supervisor_ebin_sha256: None,
            runtime: None,
            tunnel: None,
            keep_awake: None,
            last_runtime: None,
            last_tunnel: None,
            known_dns_routes: Vec::new(),
            pending_dns_routes: Vec::new(),
            recent_idempotency: HashSet::new(),
            idempotency_order: VecDeque::new(),
            maintenance_in_progress: false,
            runtime_restart: RestartBackoff::default(),
            tunnel_restart: RestartBackoff::default(),
            lifecycle_events: VecDeque::new(),
        };

        reserve_idempotency(&mut daemon, root.path(), "first".into())
            .expect("persist replay reservation");
        let loaded = load_replay_state(root.path()).expect("load replay state");
        let (recent, order) = replay_collections(loaded).expect("restore replay collections");
        assert!(recent.contains("first"));
        assert_eq!(order.front().map(String::as_str), Some("first"));

        for index in 0..(MAX_RECENT_IDEMPOTENCY_KEYS + 10) {
            reserve_idempotency(&mut daemon, root.path(), format!("key-{index}"))
                .expect("persist bounded replay key");
        }

        let loaded = load_replay_state(root.path()).expect("reload bounded replay state");
        assert_eq!(loaded.keys.len(), MAX_RECENT_IDEMPOTENCY_KEYS);
        assert!(!loaded.keys.iter().any(|key| key == "first"));
    }

    #[cfg(unix)]
    #[test]
    fn service_mode_never_falls_back_to_path_for_missing_optional_tools() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let bmscl = root.path().join("bmscl");
        fs::write(&bmscl, b"#!/bin/sh\nexit 0\n").expect("write bmscl");
        fs::set_permissions(&bmscl, fs::Permissions::from_mode(0o700)).expect("chmod bmscl");

        let overrides = ServiceToolOverrides {
            daemon_binary: env::current_exe().ok(),
            daemon_sha256: env::current_exe()
                .ok()
                .and_then(|path| executable_sha256(&path, "beamscale-desktop-daemon").ok()),
            bmscl_binary: Some(bmscl.clone()),
            bmscl_sha256: Some(executable_sha256(&bmscl, "bmscl").expect("hash bmscl")),
            cloudflared_binary: None,
            cloudflared_sha256: None,
            zed_binary: None,
            zed_sha256: None,
            supervisor_root: None,
            supervisor_ebin_sha256: None,
        };
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings {
            cloudflared_binary: "legacy-cloudflared".into(),
            zed_binary: "legacy-zed".into(),
            ..Settings::default()
        };
        apply_service_tool_overrides(root.path(), &mut settings).expect("apply overrides");
        assert_eq!(settings.bmscl_binary, bmscl.to_string_lossy());
        assert!(settings.cloudflared_binary.is_empty());
        assert!(settings.zed_binary.is_empty());
    }

    #[test]
    fn service_tool_document_requires_daemon_pin() {
        let root = tempfile::tempdir().expect("temp root");
        let overrides = ServiceToolOverrides::default();
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings::default();
        let error = apply_service_tool_overrides(root.path(), &mut settings)
            .expect_err("daemon pin must be required");
        assert!(error.to_string().contains("daemon_binary"));
    }

    #[test]
    fn current_daemon_pin_accepts_exact_running_executable() {
        let current = env::current_exe().expect("current executable");
        let digest = executable_sha256(&current, "beamscale-desktop-daemon")
            .expect("hash current executable");
        validate_current_daemon_pin(Some(&current), Some(&digest))
            .expect("exact current daemon pin");
    }

    #[test]
    fn service_tool_document_requires_bmscl_pin() {
        let root = tempfile::tempdir().expect("temp root");
        let overrides = ServiceToolOverrides::default();
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings::default();
        assert!(apply_service_tool_overrides(root.path(), &mut settings).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn runtime_tool_revalidation_rejects_post_start_substitution() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("bmscl");
        fs::write(&tool, b"first").expect("write tool");
        fs::set_permissions(&tool, fs::Permissions::from_mode(0o700)).expect("chmod tool");
        let digest = executable_sha256(&tool, "bmscl").expect("hash tool");

        revalidate_tool_before_exec(
            tool.to_str().expect("utf8 tool path"),
            Some(&digest),
            "bmscl",
        )
        .expect("unchanged tool is admitted");

        fs::write(&tool, b"second").expect("replace tool bytes");
        assert!(
            revalidate_tool_before_exec(
                tool.to_str().expect("utf8 tool path"),
                Some(&digest),
                "bmscl",
            )
            .is_err(),
            "post-start tool substitution must be rejected before execution"
        );
    }

    #[cfg(unix)]
    #[test]
    fn service_tool_pin_rejects_content_drift() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("bmscl");
        fs::write(&tool, b"first").expect("write tool");
        fs::set_permissions(&tool, fs::Permissions::from_mode(0o700)).expect("chmod tool");
        let digest = executable_sha256(&tool, "bmscl").expect("hash tool");
        assert!(validate_service_tool_pin(&tool, &digest, "bmscl").is_ok());

        fs::write(&tool, b"second").expect("mutate tool");
        assert!(
            validate_service_tool_pin(&tool, &digest, "bmscl").is_err(),
            "post-install executable substitution must fail closed"
        );
    }

    #[cfg(unix)]
    #[test]
    fn service_tool_overrides_require_absolute_executable_paths() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("bmscl");
        fs::write(&tool, b"#!/bin/sh\nexit 0\n").expect("write tool");
        fs::set_permissions(&tool, fs::Permissions::from_mode(0o700)).expect("chmod tool");

        let overrides = ServiceToolOverrides {
            daemon_binary: env::current_exe().ok(),
            daemon_sha256: env::current_exe()
                .ok()
                .and_then(|path| executable_sha256(&path, "beamscale-desktop-daemon").ok()),
            bmscl_binary: Some(tool.clone()),
            bmscl_sha256: Some(executable_sha256(&tool, "bmscl").expect("hash bmscl")),
            cloudflared_binary: None,
            cloudflared_sha256: None,
            zed_binary: None,
            zed_sha256: None,
            supervisor_root: None,
            supervisor_ebin_sha256: None,
        };
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings::default();
        apply_service_tool_overrides(root.path(), &mut settings).expect("apply overrides");
        assert_eq!(settings.bmscl_binary, tool.to_string_lossy().to_string());

        fs::set_permissions(&tool, fs::Permissions::from_mode(0o600)).expect("remove execute");
        assert!(apply_service_tool_overrides(root.path(), &mut settings).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn service_tool_overrides_reject_symlink_paths() {
        use std::os::unix::fs::{PermissionsExt, symlink};

        let root = tempfile::tempdir().expect("temp root");
        let target = root.path().join("bmscl-real");
        fs::write(&target, b"#!/bin/sh\nexit 0\n").expect("write target");
        fs::set_permissions(&target, fs::Permissions::from_mode(0o700)).expect("chmod target");
        let link = root.path().join("bmscl");
        symlink(&target, &link).expect("symlink");

        let overrides = ServiceToolOverrides {
            daemon_binary: env::current_exe().ok(),
            daemon_sha256: env::current_exe()
                .ok()
                .and_then(|path| executable_sha256(&path, "beamscale-desktop-daemon").ok()),
            bmscl_binary: Some(link),
            bmscl_sha256: Some("0".repeat(64)),
            cloudflared_binary: None,
            cloudflared_sha256: None,
            zed_binary: None,
            zed_sha256: None,
            supervisor_root: None,
            supervisor_ebin_sha256: None,
        };
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings::default();
        assert!(apply_service_tool_overrides(root.path(), &mut settings).is_err());
    }

    #[test]
    fn service_tool_overrides_pin_compiled_supervisor_root() {
        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("bmscl");
        fs::write(&tool, b"tool").expect("write bmscl");

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&tool, fs::Permissions::from_mode(0o700)).expect("chmod bmscl");
        }

        let supervisor = root.path().join("bmscl-supervisor");
        let ebin = supervisor.join("_build/default/lib/bmscl_supervisor/ebin");
        fs::create_dir_all(&ebin).expect("create supervisor ebin");
        for module in [
            "bmscl_deployment_manager.beam",
            "bmscl_route_table.beam",
            "bmscl_runtime.beam",
        ] {
            fs::write(ebin.join(module), b"beam").expect("write module");
        }

        let supervisor_digest = supervisor_ebin_sha256(&supervisor).expect("hash supervisor");
        let overrides = ServiceToolOverrides {
            daemon_binary: env::current_exe().ok(),
            daemon_sha256: env::current_exe()
                .ok()
                .and_then(|path| executable_sha256(&path, "beamscale-desktop-daemon").ok()),
            bmscl_binary: Some(tool.clone()),
            bmscl_sha256: Some(executable_sha256(&tool, "bmscl").expect("hash bmscl")),
            cloudflared_binary: None,
            cloudflared_sha256: None,
            zed_binary: None,
            zed_sha256: None,
            supervisor_root: Some(supervisor.clone()),
            supervisor_ebin_sha256: Some(supervisor_digest.clone()),
        };
        write_private_file_atomic(
            &service_tools_path(root.path()),
            &serde_json::to_vec(&overrides).expect("encode overrides"),
        )
        .expect("write overrides");

        let mut settings = Settings::default();
        let (pinned_root, pinned_digest) = apply_service_tool_overrides(root.path(), &mut settings)
            .expect("apply overrides")
            .expect("supervisor pin");
        assert_eq!(
            pinned_root,
            fs::canonicalize(&supervisor).expect("canonical supervisor")
        );
        assert_eq!(pinned_digest, supervisor_digest);

        fs::write(ebin.join("bmscl_runtime.beam"), b"changed beam")
            .expect("mutate required module");
        assert!(
            apply_service_tool_overrides(root.path(), &mut settings).is_err(),
            "content drift after service install must fail closed"
        );
    }

    #[test]
    fn service_tool_override_document_rejects_unknown_fields() {
        let parsed = serde_json::from_slice::<ServiceToolOverrides>(
            br#"{"bmscl_binary":null,"unexpected":"value"}"#,
        );
        assert!(parsed.is_err());
    }

    #[test]
    fn persistent_service_mode_rejects_in_place_self_update() {
        let root = tempfile::tempdir().expect("temp root");
        assert!(reject_service_mode_self_update(root.path()).is_ok());

        write_private_file_atomic(&service_tools_path(root.path()), b"{}")
            .expect("write service marker");
        let error = reject_service_mode_self_update(root.path())
            .expect_err("persistent service update must require repin");
        assert_eq!(error.0, StatusCode::CONFLICT);
        assert!(error.1.contains("repin"));
    }
    #[cfg(unix)]
    #[test]
    fn trusted_system_tool_rejects_non_executable_candidate() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("temp root");
        let tool = root.path().join("system-tool");
        fs::write(&tool, b"#!/bin/sh\nexit 0\n").expect("write tool");
        fs::set_permissions(&tool, fs::Permissions::from_mode(0o600)).expect("chmod tool");

        let value = tool.to_string_lossy().to_string();
        assert!(trusted_system_tool(&[value.as_str()], "test-tool").is_err());

        fs::set_permissions(&tool, fs::Permissions::from_mode(0o700)).expect("chmod executable");
        let pinned =
            trusted_system_tool(&[value.as_str()], "test-tool").expect("trusted fixed tool");
        assert_eq!(pinned, fs::canonicalize(&tool).expect("canonical tool"));
    }

    #[cfg(unix)]
    #[test]
    fn private_state_files_are_mode_600() {
        use std::os::unix::fs::PermissionsExt;

        let root = tempfile::tempdir().expect("tempdir");
        let path = root.path().join("state.json");
        write_private_file_atomic(&path, b"{}").expect("write private state");

        let mode = fs::metadata(&path)
            .expect("state metadata")
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn dns_route_identity_is_case_insensitive() {
        let mixed = TunnelRequest {
            name: "beamscale-local".into(),
            hostname: Some("Dev.Example.COM".into()),
            config: None,
        };
        let lower = TunnelRequest {
            name: "beamscale-local".into(),
            hostname: Some("dev.example.com".into()),
            config: None,
        };
        assert_eq!(dns_route_for_request(&mixed), dns_route_for_request(&lower));
        assert_eq!(
            dns_route_for_request(&mixed).expect("route").hostname,
            "dev.example.com"
        );
    }

    #[test]
    fn persisted_dns_routes_are_canonicalized_before_validation() {
        let mut desired = DesiredState {
            runtime: None,
            tunnel: Some(TunnelRequest {
                name: "beamscale-local".into(),
                hostname: Some("Dev.Example.COM".into()),
                config: None,
            }),
            known_dns_routes: vec![DnsRoute {
                tunnel: "beamscale-local".into(),
                hostname: "Dev.Example.COM".into(),
            }],
            pending_dns_routes: vec![],
        };

        canonicalize_dns_route_state(&mut desired);
        assert_eq!(
            desired
                .tunnel
                .as_ref()
                .and_then(|request| request.hostname.as_deref()),
            Some("dev.example.com")
        );
        assert_eq!(desired.known_dns_routes[0].hostname, "dev.example.com");
        validate_dns_route_state(&desired).expect("canonical state is valid");
    }

    #[test]
    fn persisted_dns_route_case_collisions_fail_closed() {
        let mut desired = DesiredState {
            runtime: None,
            tunnel: None,
            known_dns_routes: vec![DnsRoute {
                tunnel: "beamscale-local".into(),
                hostname: "Dev.Example.COM".into(),
            }],
            pending_dns_routes: vec![DnsRoute {
                tunnel: "beamscale-local".into(),
                hostname: "dev.example.com".into(),
            }],
        };

        canonicalize_dns_route_state(&mut desired);
        assert!(validate_dns_route_state(&desired).is_err());
    }

    #[test]
    fn operator_update_root_contract_fails_closed() {
        let root = tempfile::tempdir().expect("tempdir");

        let set = OperatorSettingsPatch {
            update_root: Some(root.path().to_path_buf()),
            clear_update_root: false,
        };
        assert_eq!(
            resolve_operator_update_root(set).expect("resolve root"),
            Some(root.path().canonicalize().expect("canonical root"))
        );

        let clear = OperatorSettingsPatch {
            update_root: None,
            clear_update_root: true,
        };
        assert_eq!(
            resolve_operator_update_root(clear).expect("clear root"),
            None
        );

        assert!(
            resolve_operator_update_root(OperatorSettingsPatch {
                update_root: None,
                clear_update_root: false,
            })
            .is_err()
        );
        assert!(
            resolve_operator_update_root(OperatorSettingsPatch {
                update_root: Some(root.path().to_path_buf()),
                clear_update_root: true,
            })
            .is_err()
        );

        assert!(
            serde_json::from_value::<SettingsPatch>(serde_json::json!({
                "keep_alive_during_lock": true,
                "unexpected": true
            }))
            .is_err()
        );
        assert!(
            serde_json::from_value::<RuntimeRequest>(serde_json::json!({
                "project_dir": root.path(),
                "poll_ms": 250,
                "unexpected": true
            }))
            .is_err()
        );
        assert!(
            serde_json::from_value::<TunnelRequest>(serde_json::json!({
                "name": "local",
                "hostname": "dev.example.com",
                "unexpected": true
            }))
            .is_err()
        );
        assert!(
            serde_json::from_value::<DnsRouteResolution>(serde_json::json!({
                "name": "local",
                "hostname": "dev.example.com",
                "applied": true,
                "unexpected": true
            }))
            .is_err()
        );
    }

    #[test]
    fn public_settings_view_does_not_expose_operator_paths_or_tool_paths() {
        let settings = Settings {
            keep_alive_during_lock: true,
            update_root: Some(PathBuf::from("/secret/operator/workspace")),
            bmscl_binary: "/opt/private/bin/bmscl".into(),
            cloudflared_binary: "/opt/private/bin/cloudflared".into(),
            zed_binary: "/opt/private/bin/zed".into(),
            bmscl_sha256: None,
            cloudflared_sha256: None,
            zed_sha256: None,
        };
        let value = serde_json::to_value(PublicSettingsView::from(&settings))
            .expect("serialize public settings");

        assert_eq!(
            value,
            serde_json::json!({
                "keep_alive_during_lock": true,
                "update_root_configured": true
            })
        );
        let encoded = value.to_string();
        assert!(!encoded.contains("/secret/operator/workspace"));
        assert!(!encoded.contains("cloudflared_binary"));
        assert!(!encoded.contains("bmscl_binary"));
        assert!(!encoded.contains("zed_binary"));
    }

    #[test]
    fn persisted_state_rejects_unknown_fields_on_downgrade() {
        assert!(
            serde_json::from_value::<Settings>(serde_json::json!({
                "keep_alive_during_lock": false,
                "unexpected_future_security_field": true
            }))
            .is_err()
        );

        assert!(
            serde_json::from_value::<DesiredState>(serde_json::json!({
                "unexpected_future_security_field": true
            }))
            .is_err()
        );

        assert!(
            serde_json::from_value::<ReplayState>(serde_json::json!({
                "keys": [],
                "unexpected_future_security_field": true
            }))
            .is_err()
        );

        assert!(
            serde_json::from_value::<DnsRoute>(serde_json::json!({
                "tunnel": "local",
                "hostname": "dev.example.com",
                "unexpected_future_security_field": true
            }))
            .is_err()
        );
    }
}
