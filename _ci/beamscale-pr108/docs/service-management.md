# Desktop daemon service management

`beamscale-service` is the Rust service-manager companion for the BeamScale desktop daemon. It installs the daemon as a per-user background service without putting daemon, operator, ingress, or Cloudflare credentials in service-manager arguments.

## Install

Build both binaries:

```sh
cargo build --release
```

Then install the daemon:

```sh
target/release/beamscale-service install \
  --binary "$(pwd)/target/release/beamscale-desktop-daemon" \
  --supervisor-root /absolute/path/to/bmscl-supervisor
```

An optional `--data-root PATH` is supported on macOS and Linux. `--supervisor-root PATH` pins the compiled `bmscl-supervisor` checkout required for reboot-safe `bmscl dev` restoration; if omitted, the helper may use `BMSCL_SUPERVISOR_ROOT`. The root, ebin directory, and required BEAM modules are canonicalized and rejected when symlinked or missing. Installation also records a deterministic SHA-256 over the complete `ebin` contents. Daemon startup recomputes that digest and refuses service-mode startup when compiled bytes drift. Windows intentionally rejects a custom data root rather than introducing a PowerShell/cmd wrapper solely to inject environment state.

Platform behavior:

- macOS: renders a private LaunchAgent, validates it with `plutil`, bootstraps it with `launchctl`, and keeps stdout/stderr under the private daemon state directory.
- Linux: renders a systemd user unit, validates it with `systemd-analyze --user verify`, then enables and starts it. The installer does not enable linger.
- Windows: creates a current-user ONLOGON Scheduled Task with `schtasks.exe`, runs the daemon directly, and protects the default state directory with `icacls.exe`.

The state root and logs are retained across uninstall. Token files, replay state, DNS journals, desired runtime/tunnel state, and update state are not deleted.

## Status and removal

```sh
beamscale-service status
beamscale-service uninstall
```

On Linux, an administrator may separately opt into login-independent user services with `loginctl enable-linger <user>`; BeamScale does not change that machine policy automatically.

## Security boundary

The service manager accepts only typed `install`, `uninstall`, and `status` operations. The supervisor root and its compiled `ebin` SHA-256 are persisted in the same private transactional service state and injected only into the managed runtime child. The digest covers every regular file in `ebin` using stable name/length/content framing; symlinks, directories, control-text filenames, oversized files, more than 4096 files, or more than 256 MiB total input are rejected. It does not expose arbitrary shell execution and does not use shell wrappers to launch the daemon. Declarative LaunchAgent/systemd templates are committed and compiled into the Rust helper, so reviewed service policy and rendered output stay coupled.


### Registration-only handover

When the desktop daemon itself requests persistent-service installation through the operator API, the packaged helper uses `install --no-start`. That mode writes and validates the service definition and enables future automatic starts, but it does not launch a second daemon immediately.

This avoids a first-install race where the already-running daemon owns the loopback control port. The current daemon remains authoritative for the present session; the service manager takes over on a later launch/login after that process exits.

Direct operator use of `beamscale-service install` keeps the existing start-now behavior. Registration-only mode is explicit rather than inferred from environment or process ancestry.

### Uninstall semantics

Service removal disables future automatic starts and removes the per-user service definition, but the current daemon process keeps running. This is intentional: `beamscale-service` can be invoked by the daemon itself through the operator RPC, so stopping the active service before the durable unregister operation completes could kill both the daemon and its helper mid-transaction.

After uninstall, existing daemon state is preserved. The current process can be stopped explicitly when convenient; it will not be restarted automatically on the next login/session.
