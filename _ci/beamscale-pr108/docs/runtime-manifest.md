# Desktop runtime manifest

BeamScale's installed desktop appliance is moving from "launch whatever `bmscl dev` resolves today" toward a digest-pinned single-BEAM runtime authority.

This repository now defines the first fail-closed contract:

- schema: `beamscale.desktop-runtime/v1`
- runtime kind: `beamscale-single-beam`
- stable release identifier
- absolute executable path
- exact lowercase SHA-256 of that executable

The daemon **does not switch activation to this manifest in this change**. Existing developer mode still launches through `bmscl dev`. The manifest is an authority primitive for the next launcher step.

Manifest parsing itself is bound to the opened file identity: the validator checks the manifest path before open and after read, verifies the opened file remains stable, and on Unix requires device/inode identity to match the path.

The shared executable digest authority now binds the inspected pathname to the opened file while hashing and rechecks the pathname afterward (including device/inode identity on Unix). A validated **runtime executable pathname** is still not itself a race-free execution handle across the later validation-to-exec interval, so a future direct launcher must not simply validate and much later reopen the pathname for execution. The launcher should bind execution to the already-validated file identity (or re-open and re-verify immediately before exec with platform-appropriate anti-TOCTOU guarantees) so path replacement cannot swap bytes between validation and launch.

## Configure

Place a manifest at:

```text
~/.beamscale/desktop-daemon/runtime-manifest.json
```

or explicitly select another file:

```sh
export BMSCL_DESKTOP_RUNTIME_MANIFEST=/absolute/path/runtime-manifest.json
```

If an explicit/default manifest exists, daemon startup validates it and fails closed on:

- an unsupported schema version or runtime kind;
- unknown JSON fields;
- an invalid release identifier;
- a relative executable path;
- manifest or executable paths containing control characters/non-UTF-8 text;
- executable symlinks or non-regular files;
- a non-executable Unix file;
- an executable larger than 512 MiB;
- a malformed or mismatched SHA-256;
- a manifest larger than 64 KiB, including growth while it is being read;
- a manifest path that becomes a symlink/non-file or changes opened-file identity/size/modification time while it is being read;
- an executable that grows beyond 512 MiB, changes size, changes modification time, or changes opened-file identity while it is being hashed.

`bmscl local doctor` revalidates and re-hashes the selected executable, so binary drift after daemon startup is visible.

If no manifest is configured, doctor reports that the daemon remains in developer `bmscl dev` mode. This preserves existing local development while the direct single-BEAM launcher ABI is finalized.

## Example

See `contracts/desktop-runtime-manifest-v1.example.json`.

The independent JSON Schema is:

`contracts/desktop-runtime-manifest-v1.schema.json`

The Rust validator additionally enforces filesystem properties that JSON Schema cannot express, including absolute paths, regular-file/non-symlink identity, Unix execute permission, the daemon-wide 512 MiB pinned-executable size bound, canonicalization, and the actual executable digest. Executable hashing delegates to the same bounded identity/size/mtime-stability authority used by persistent service tool pins.

## Next activation step

A follow-on change should make persistent/appliance mode launch the validated runtime artifact directly and treat project/module/poll settings as request-time bindings rather than executable selection. That launcher must preserve current daemon guarantees: process-tree cleanup, replay protection, DNS journaling, health checks, watchdog/backoff, operator authority separation, and rollback. It must also close the validation-to-exec TOCTOU boundary described above; this PR intentionally does not claim that pathname validation alone solves it.
