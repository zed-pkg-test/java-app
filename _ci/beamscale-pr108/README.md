## Digest-pinned desktop runtime manifest

Installed/appliance deployments can optionally define `beamscale.desktop-runtime/v1`, a closed digest-pinned manifest for the future `beamscale-single-beam` runtime launcher. Daemon startup validates the manifest when configured, and `bmscl local doctor` revalidates it.

The manifest does **not** yet replace `bmscl dev` activation. It establishes the runtime authority contract first. Executable hashing reuses the same bounded pathname-identity, size, mtime, and SHA-256 authority used by persistent service-tool pins.

See [desktop runtime manifest](docs/runtime-manifest.md).

# BeamScale Desktop Daemon

The BeamScale desktop daemon is the long-lived local control plane for running BeamScale infrastructure on a laptop or desktop.

A local installation is deliberately small:

- one long-lived BEAM VM / Erlang OS process;
- BeamScale Erlang actor workers inside that VM;
- one loopback HTTP ingress inside that same BEAM VM;
- an optional named Cloudflare Tunnel exposing the local server through a real domain;
- the daemon itself, which owns process lifetime, update coordination, and power-management policy.

The Rust CLI, Gleam CLI, Flutter desktop app, and Rust desktop app are clients of this daemon. They do not each launch their own Erlang runtime or Cloudflare process.

## Local API and security

The daemon listens on `127.0.0.1:9587` by default and refuses a non-loopback `BMSCL_DAEMON_LISTEN`.

The health endpoint is public on loopback. Control endpoints require a bearer token stored at `~/.beamscale/desktop-daemon/token`. On Unix the token is created with mode `0600`. Override the state directory with `BMSCL_DESKTOP_HOME` or inject `BMSCL_DAEMON_TOKEN` through an appropriate secret boundary.

The daemon exposes named operations only; it is intentionally not a general shell-execution HTTP service. Authenticated requests must also carry `x-ores-protocol-version: beamscale.desktop-daemon/v1`; mutations require bounded, replay-protected `x-ores-idempotency-key` values.

## Start the daemon

Build and run it directly during development:

```sh
cargo run
```

For a machine that hosts BeamScale continuously, run the resulting binary from the operating system's per-user login/service manager so the GUI does not own daemon lifetime.

Useful environment variables:

```text
BMSCL_DAEMON_LISTEN=127.0.0.1:9587
BMSCL_DESKTOP_HOME=~/.beamscale/desktop-daemon
RUST_LOG=info
```

Client applications may point at a non-default loopback port with `BMSCL_DAEMON_URL`.

## Persistent per-user service

The repository includes a Rust service-manager companion, `beamscale-service`. It installs the daemon directly through launchd, systemd user services, or Windows Scheduled Tasks without durable Bash/PowerShell installer logic and without putting daemon/operator credentials in service-manager arguments.

At install time the service manager resolves the current `bmscl` executable and, when present, `cloudflared` and `zed`. It also pins the compiled `bmscl-supervisor` checkout supplied by `--supervisor-root PATH` or `BMSCL_SUPERVISOR_ROOT`. The installer computes a deterministic SHA-256 over the complete compiled supervisor `ebin` directory and stores both the canonical root and content digest in private `service-tools.json` under the daemon state root. The daemon reads that file only at startup. Once this service-mode document exists, `bmscl` is mandatory and missing optional tools are treated as unavailable rather than falling back to the service process PATH or stale settings. Re-run service installation after adding, upgrading, or replacing `bmscl`, `cloudflared`, or `zed`; service-mode startup fails closed if the bytes at a pinned path no longer match the reviewed install-time digest. Ordinary HTTP clients remain unable to choose executable paths. Package-manager symlinks are accepted when they resolve to executable regular files.

```sh
cargo build --release
target/release/beamscale-service install \
  --binary "$(pwd)/target/release/beamscale-desktop-daemon" \
  --supervisor-root /absolute/path/to/bmscl-supervisor
target/release/beamscale-service status
```

Removal preserves private daemon state, replay journals, DNS reconciliation state, credentials, logs, and desired runtime/tunnel intent:

```sh
target/release/beamscale-service uninstall
```

See [desktop daemon service management](docs/service-management.md) for platform details and the Linux linger boundary.

## Run BeamScale locally

The daemon starts the existing BeamScale development runtime through `bmscl dev`. In persistent service mode it injects only the validated supervisor root persisted by `beamscale-service`, so login/reboot startup does not depend on interactive-shell state or project adjacency. The daemon sets `BMSCL_DESKTOP_DAEMON=1`; `bmscl-supervisor` uses that to start the loopback Cowboy ingress at `127.0.0.1:8080` in the same BEAM VM.

Rust CLI companion binary:

```sh
bmscl local status
bmscl local start /path/to/project
bmscl local restart
bmscl local stop
```

Gleam CLI:

```sh
bmscl local status
bmscl local start /path/to/project
bmscl local restart
bmscl local stop
```

The existing `bmscl dev` architecture keeps the outer BEAM VM (P1 / granddaddy) stable. Accepted Lambda-only Gleam changes compile into a fresh generation and are hot-activated for new Erlang actor invocations. Middleware or runtime-shape changes replace the P2 subtree while preserving P1. The local HTTP ingress is supervised beside that replaceable subtree so the listener can remain stable while P2 drains and restarts.

### Daemon and service-manager restarts

A successful explicit runtime or tunnel start is recorded as desired state. If the desktop daemon is restarted by launchd, systemd, a Windows service manager, or a normal SIGTERM/replace cycle, it restores those services from the persisted request without re-running the Cloudflare DNS route.

An explicit `runtime stop` or `tunnel stop` clears the corresponding desired state first, so a later daemon restart leaves that service stopped. Failed starts are not persisted as desired-running state. Unexpected runtime or tunnel exits are reconciled automatically with capped exponential restart backoff so a permanently broken child cannot be respawned in a tight loop.

On Unix the daemon state directory is normalized to mode `0700` and sensitive state files are written atomically with mode `0600`. Managed child processes are placed in their own process groups so ordinary stop/restart and daemon shutdown terminate descendant BEAM/cloudflared processes rather than orphaning them.

## Expose the laptop through Cloudflare Tunnel

Install and authenticate `cloudflared`, then create a named tunnel using the normal Cloudflare workflow. The daemon intentionally does **not** accept a caller-supplied cloudflared configuration file for desktop exposure.

BeamScale pins every daemon-managed tunnel to the validated same-BEAM loopback origin:

```text
http://127.0.0.1:8080
```

(or the explicitly configured numeric-loopback `BMSCL_LOCAL_INGRESS_URL`).

This listener is the local **public application origin**. Requests arriving through Cloudflare Tunnel are forwarded directly to it and must not depend on daemon-only bearer credentials. The authenticated daemon API on port 9587 remains the control plane; it does not proxy application invocations.

Create the named tunnel once, then expose it through the public CLI:

```sh
cloudflared tunnel login
cloudflared tunnel create beamscale-local

bmscl local start /path/to/project
bmscl local expose beamscale-local --hostname dev.example.com
```

When `--hostname` is supplied on an explicit exposure, the daemon journals the DNS mutation and runs `cloudflared tunnel route dns` separately. The long-running connector is then started with a pinned `--url` origin and automatic cloudflared self-update disabled so lifecycle/update ownership stays with the BeamScale daemon.

This separation is intentional:

- the end-user daemon token can expose only BeamScale's numeric-loopback same-BEAM ingress;
- a caller cannot use a custom cloudflared config to publish arbitrary localhost HTTP/TCP/SSH/Unix-socket services;
- DNS creation remains separately journaled for crash-safe reconciliation;
- tunnel restart/crash recovery restarts the connector without repeating DNS mutation.

The daemon remembers successfully provisioned `(tunnel, hostname)` routes in private desired state. If a DNS mutation outcome is uncertain, `bmscl local doctor` reports it and the typed CLI recovery command can record the independently verified result.

## Operator authority boundary

The daemon maintains a separate private operator credential at `operator-token` (or `BMSCL_DAEMON_OPERATOR_TOKEN`). It must be distinct from the normal end-user daemon token.

`GET /v1/internal/authority` lets trusted operator tooling verify that it holds the operator credential. `PUT /v1/internal/settings` is the first operator-only mutation endpoint and is limited to update-root configuration; it does not expose arbitrary process execution or infrastructure control.

Desktop/mobile agents do **not** receive the operator credential. Agent heartbeat, command lease, and result submission remain disabled until per-agent credentials are implemented. This prevents an ordinary agent compromise from becoming local operator authority.

## DNS mutation journal

Creating a Cloudflare Tunnel DNS route is an external side effect and cannot be atomically committed with the daemon's local state file. The daemon therefore journals a route as **pending** before invoking `cloudflared tunnel route dns` and promotes it to **known** only after the command succeeds.

If the daemon crashes, times out, or sees a non-zero result after the external mutation may have occurred, it leaves that route pending and refuses to recreate it automatically. `GET /v1/doctor` reports the uncertainty. Reconcile the actual Cloudflare DNS record, then call `POST /v1/tunnel/dns/resolve` with:

```json
{"name":"beamscale-local","hostname":"dev.example.com","applied":true}
```

Use `applied: true` only after confirming the CNAME points to the intended tunnel. Use `applied: false` only after confirming the record was not created and may safely be retried. This endpoint changes only the daemon's typed DNS journal; it does not run arbitrary shell commands or overwrite DNS.

## Keep BeamScale alive while the screen is locked

Both desktop apps expose **Keep BeamScale alive during lock-screen**. The UI only changes daemon policy, so closing the desktop UI does not intentionally tear down the inhibitor or the BeamScale runtime.

Current implementations are:

- macOS: a long-lived `caffeinate -i -w <daemon-pid>` child;
- Linux: `systemd-inhibit` blocking system sleep for the local runtime;
- Windows: a small PowerShell host that refreshes `SetThreadExecutionState`.

The policy is designed to prevent system/idle sleep; it does not require keeping the display lit and does not disable the normal lock screen.

CLI equivalents are:

```sh
bmscl local keep-awake on
bmscl local keep-awake off
```

## Lifecycle events

Authenticated desktop clients can poll `GET /v1/events` for a bounded, in-memory lifecycle journal. The daemon records user-requested runtime/tunnel start-stop-restart operations plus watchdog recovery/suspension events.

Use `?since_sequence=N&limit=M` for incremental polling. Responses include `oldest_sequence`, `latest_sequence`, and `gap_detected` so a client can detect either that it fell behind the 512-event retention window or that its cursor is ahead of a freshly restarted journal. In either case the client should force a full status refresh before resuming incremental polling. Each detail string is capped at 512 UTF-8-safe bytes, and responses expose no bearer tokens, environment variables, command output, project file contents, or Cloudflare credentials. Events are intentionally ephemeral across daemon restarts; durable desired state remains the source of truth.

## Doctor / preflight

Authenticated clients can call `GET /v1/doctor` to get a secret-free local preflight report. It checks the configured `bmscl`, `cloudflared`, and `zed` executables; the private daemon state directory; desired runtime/tunnel state; keep-awake inhibitor state; and loopback ingress reachability when the runtime is expected to be running.

The report intentionally includes operational details but never returns the daemon bearer token, Cloudflare credentials, environment secrets, or file contents.

## Updates

The daemon centralizes update coordination so an active local runtime can be stopped, updated, and restarted in a controlled sequence.

The BeamScale/Zed workspace root is operator-owned because it determines where `zed install --frozen` runs. A normal desktop token cannot change it.

Set it with the separate operator credential:

```sh
TOKEN="$(cat ~/.beamscale/desktop-daemon/operator-token)"
curl -fsS -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "x-ores-protocol-version: beamscale.desktop-daemon/v1" \
  -H "x-ores-idempotency-key: operator-update-root-1" \
  -H "Content-Type: application/json" \
  -d '{"update_root":"/absolute/path/to/workspace"}' \
  http://127.0.0.1:9587/v1/internal/settings
```

To clear it, send `{"clear_update_root":true}`. The daemon canonicalizes and persists a configured directory before allowing frozen installs.

End-user clients can then inspect and apply updates without gaining authority to redirect the install root:

```sh
bmscl local update status
bmscl local update apply
```

The current update implementation runs `zed self-update` and `zed install --frozen` under bounded child-process deadlines, excludes concurrent control mutations while maintenance is active, and restores the local BeamScale runtime when it was running before the update.

## Persisted-state downgrade safety

Private daemon state is decoded fail-closed. Settings, desired runtime/tunnel/DNS state, replay-journal state, and DNS route records reject unknown fields instead of silently discarding them.

This matters during rollback: if a newer daemon writes a security-relevant field that an older binary does not understand, the older daemon must refuse that state rather than starting with weakened semantics.

## Status disclosure boundary

Public `GET /v1/status` returns only end-user settings needed by desktop clients: the keep-awake policy and whether an operator update root is configured. It does **not** return the update-root path or resolved `bmscl`, `cloudflared`, or `zed` executable paths.

This keeps operator filesystem layout out of the normal desktop credential surface while preserving the existing keep-awake UI contract.

## HTTP API

Authenticated endpoints:

- `GET /v1/status`
- `PUT /v1/settings`
- `POST /v1/runtime/start`
- `POST /v1/runtime/stop`
- `POST /v1/runtime/restart`
- `POST /v1/tunnel/start`
- `POST /v1/tunnel/stop`
- `POST /v1/tunnel/restart`
- `POST /v1/tunnel/dns/resolve`
- `GET /v1/update/status`
- `POST /v1/update/apply`
- `GET /v1/doctor`

Operator-authenticated endpoints:

- `GET /v1/internal/authority`
- `PUT /v1/internal/settings`

`GET /health` is the unauthenticated loopback health check.

The daemon deliberately keeps the API small. New desktop capabilities should normally be introduced as typed/named daemon operations rather than adding arbitrary process execution.

### Compatibility aliases

The canonical daemon address variable is `BMSCL_DAEMON_LISTEN`. The older `BMSCL_DESKTOP_ADDR` variable is accepted as a fallback so existing local installations can migrate without changing service-manager configuration immediately.


### Supervisor content pin

Persistent service mode treats the compiled supervisor as immutable authority, not merely a pathname. The service helper hashes every regular non-symlink file in the supervisor `ebin` with filename/length framing, a stable lexical order, and explicit file-count/per-file/total-byte limits. The daemon recomputes the digest at startup and in `doctor`; any added, removed, replaced, symlinked, or modified compiled artifact fails closed until the service is reinstalled against the intended supervisor build.

### Updating a persistent service

Persistent service mode content-pins the reviewed `bmscl`, `cloudflared`, `zed`, and compiled supervisor bytes. The daemon therefore rejects in-place `update apply` while `service-tools.json` is present. Upgrade the tools externally, then rerun `bmscl local service install` so the new bytes are explicitly reviewed and repinned before the service is restarted.

Pinned executable bytes are revalidated immediately before use in persistent service mode. Runtime restart, tunnel/DNS mutation, update probes, doctor probes, and updater execution fail closed if the bytes at a pinned path drift after daemon startup.

The persistent daemon executable itself is also pinned by canonical path and SHA-256 at service installation. On reboot/service restart, the daemon verifies that the running executable is the installed path and still matches the recorded digest before it restores any desired runtime or public tunnel state.

Windows state roots are ACL-hardened at daemon startup even when the daemon is run manually rather than installed as a background service. The daemon uses fixed System32 `whoami.exe` and `icacls.exe` paths to remove inherited/broad grants and retain full control for the current user plus SYSTEM; existing state files are corrected recursively and new files inherit the restricted ACL.

## Operator service lifecycle API

Persistent OS service management is an operator authority, distinct from the normal desktop bearer token. The daemon exposes typed loopback-only endpoints backed by the packaged Rust `beamscale-service` sibling binary:

- `GET /v1/internal/service/status`
- `POST /v1/internal/service/install`
- `POST /v1/internal/service/uninstall`

These endpoints require `BMSCL_DAEMON_OPERATOR_TOKEN` / the private `operator-token` file plus the desktop protocol header. Install/uninstall also require mutation idempotency keys.

Clients cannot choose an executable or pass arbitrary argv. Install accepts only an optional `supervisor_root`; the daemon derives its own canonical executable, state root, and the sibling service helper. Helper output is bounded, execution has a timeout, and blocking OS service-manager work runs outside Tokio workers.

Service activation may replace the currently running daemon process, especially on launchd. A transport disconnect after an accepted install mutation is therefore an indeterminate-success condition: clients should reconnect and confirm through `/v1/internal/service/status` rather than blindly issuing another install with a new idempotency key.

