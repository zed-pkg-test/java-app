# Agent instructions

## Scope and hierarchy

- These instructions apply to the whole `zed-pkg/zed-clients` repository unless a deeper lowercase `agents.md` adds narrower rules.
- Before editing, resolve the current working directory and load every readable ancestor `agents.md` from the filesystem root to the working directory. Do not search siblings. Resolve symlinks, deduplicate resolved files, and report unreadable or cyclic instruction files.
- `.claude/CLAUDE.md`, `.gemini/GEMINI.md`, and `.openai/AGENTS.md` are pointers only. Never duplicate instructions in tool-specific files.

## Repository role

This repository is the multi-language Zed client and SDK release set. It maps one registry/API contract into language-idiomatic clients while preserving equivalent authentication, transport, error, versioning, and package-publication behavior.

## Working rules

- Treat the API/OpenAPI source and generator inputs as canonical. Do not hand-edit generated files without updating the generator or manifest that owns them.
- Preserve operation coverage and behavioral parity across supported language clients; document intentional language-specific differences.
- Keep public names, request/response schemas, error categories, retry/idempotency behavior, and authentication semantics compatible.
- Never log bearer tokens, refresh tokens, signed URLs, package credentials, or sensitive response bodies.
- Keep network timeouts, redirect policy, response-size limits, and error truncation bounded and explicit.
- Regenerate deterministically and fail CI on generator drift, uncommitted output, missing packages, or lockfile changes.
- Validate each language with its native formatter, compiler/type checker, tests, package metadata checks, and a shared contract fixture.
- Coordinate API/interface changes contract-first and update release notes/versioning for every affected client.

## Validation

The pinned `agents policy` workflow validates this hierarchy and the three tool pointers. Follow `README.md`, generator documentation, and existing CI before requesting review.
