# ores-orm-core

ORM-first derivative schema and validator tooling for the ORESoftware fleet.

This repository fills the inverse/code-first lane that is intentionally different from `ORESoftware/ores-contracts`:

```text
Diesel schema/model definitions -----> ORM IR_D --\
                                                  +--> parity gate --> admitted derivative ORM IR
SeaORM entity/model definitions -----> ORM IR_S --/                     |
                                                                         +--> Rust Serde types
                                                                         +--> JSON Schema 2020-12
                                                                         +--> TypeScript validators/types
                                                                         +--> Dart validators/types
                                                                         +--> Gleam decoders/types
                                                                         +--> WIT compatibility evidence
```

It is analogous in purpose to deriving Zod schemas from Drizzle tables, but it is deliberately stricter about provenance and authority.

## Authority boundary

Human-authored TypeSpec and human-authored JSON Schema Draft 2020-12 remain independent peer authorities in `*-interfaces` repositories. `ORESoftware/typespec-json-schema-validator` (TJSV) owns their generic semantic parity and Contract IR admission. `ORESoftware/ores-contracts` owns contract-first persistence/codegen convergence for contract families that declare persistence semantics. `ORESoftware/ores-wit` owns WIT validation/canonicalization/binding orchestration.

`ores-orm-core` does **not** promote Diesel, SeaORM, Serde, generated JSON Schema, or a database catalog into a third authored authority. ORM-first output is derivative evidence. Public output may be published only when it is compatible with the exact admitted public contract surface and its retained Contract IR/receipts.

## Inputs

The first-class input lanes are independent and must converge when both are configured:

- Diesel `table!` schema output, normally produced by `diesel print-schema`, plus explicitly declared model metadata where needed.
- SeaORM entity/model definitions, including SeaORM 2 dense entities and generated entities from `sea-orm-cli generate entity`.
- Optional database catalog evidence for DB-first verification. Catalog evidence strengthens the proof but does not silently override either ORM lane.

A single ORM lane may be used during bootstrap, but release/publication policy can require both lanes. Unsupported or ambiguous ORM constructs fail closed rather than being guessed.

## Derived shape families

Generation is shape-aware rather than emitting one struct for every purpose:

- `row` / `select`: database-readable representation.
- `create` / `insert`: client-settable create fields; generated/default-only/immutable fields are excluded unless explicitly allowed.
- `update`: replace/update shape with field mutability policy applied.
- `patch`: partial update shape; all writable fields optional, with a non-empty-object validator where the target language supports it.
- `public_read`: explicitly public projection of a row.
- `public_create` and `public_update`: explicitly public write projections.

Public/private membership is explicit configuration/metadata. The generator must never decide that a field is secret or public from a name such as `password`, `token`, `_hidden`, or `internal`.

## Naming

Wire and database names are preserved exactly. Generated language identifiers may escape reserved words, but escaping must not alter the serialized name. Fleet defaults prefer `snake_case`; this repository does not inherit older generators' automatic camelCase conversion. Any name transformation must be explicit, deterministic, and represented in provenance.

## Validation targets

The canonical derivative validation artifact is Draft 2020-12 JSON Schema plus a normalized ORM IR. Language packages consume the same admitted semantics:

- Rust: `serde` + `schemars` projections, with strict unknown-field handling on closed records.
- TypeScript: generated types plus Zod-compatible runtime schemas (and JSON-Schema conformance tests).
- Dart: generated typed models plus runtime validation against the same shape constraints.
- Gleam: generated record/custom types plus `gleam/dynamic` decoders/validators.

Additional language adapters can be added without changing the ORM IR or authority model.

## Relationship to product repositories

Typical flow:

```text
<product>-interfaces                 authored TypeSpec + authored JSON Schema
        |                                      |
        +-------------- TJSV ------------------+
                           |
                      Contract IR
                           |
                  public-admission check
                           ^
                           |
<product>-orm-core -- ores-orm-core generator/parity --> generated derivative artifacts
                           |
                           +--> private server-only Serde/model surfaces
                           +--> admitted public projections copied/packaged by <product>-lib-core or client packages
```

`*-orm-core` remains the private backend home for executable Diesel/SeaORM behavior. Public packages receive only explicitly admitted generated projections, never executable ORM code or database credentials/configuration.

## Planned repository contract

The implementation will use Rust for durable codegen, checks, validators, and audit tooling. The intended configuration file is `.ores-orm.toml`; any executable CLI will use the fleet `flags-2-env` argv boundary and `.cli-flags.toml` rather than introducing a second parser.

Generated output will live under `generated/` with a README/provenance receipt and will be reproducible from exact source bytes, tool versions/revisions, options, and retained contract evidence.

## Related repositories

- `ORESoftware/orm-core-template.rs`
- `ORESoftware/ores-contracts`
- `ORESoftware/ores-interfaces`
- `ORESoftware/typespec-json-schema-validator`
- `ORESoftware/ores-wit`
- `flags-2-env/flags-2-env`


## Derivative language packages

The ORM-first lane now derives the same shape algebra into five deterministic artifacts:

- Rust Serde/Schemars DTO source;
- TypeScript + Zod validators;
- Dart typed models with presence-aware JSON decoding;
- Gleam types plus dynamic decoders;
- the Draft 2020-12 JSON Schema witness.

Public generation is fail-closed. A table must opt into `public_surface = true`, every storage column must be classified as public, private, or secret, and public shapes are assembled only from explicit `public_read`, `public_create`, and `public_update` allowlists. Serde capability by itself never implies public exposure.

The emitters preserve four distinct wire states where applicable: required/non-null, required/nullable, optional/non-null, and optional/nullable. Unknown fields are rejected at public decode boundaries. Unsafe cross-runtime domains such as unconstrained `int64`, decimal, and unresolved named database types stop generation until a reviewed wire mapping exists.

`emit::bundle` binds the language sources to a deterministic shape digest and records SHA-256 for every generated artifact. A public bundle remains a **candidate** until TJSV verifies it against the exact owning TypeSpec + authored JSON Schema contract and retained Contract IR/receipt. Generation is not publication or admission.


## TJSV public admission

Digest-binding contract files to a public derivative is not semantic admission. The manifest keeps those states separate:

- `required`: a public shape has no contract evidence attached;
- `evidence_bound`: exact Contract IR and receipt bytes are hashed into generation evidence, but the derivative remains blocked;
- `admitted`: the exact Contract IR, TJSV projection manifest, and self-digesting `projection-verification-receipt/v1` passed local integrity/binding checks, the trusted projection declares the expected contract scope and runtime-validator scope, and the verified output digests exactly equal this bundle's Rust/TypeScript/Dart/Gleam/JSON-Schema bytes;
- `not_applicable`: the shape is private/server-only.

`TjsvAdmissionBinding::verify` recomputes the Contract IR `irId`, projection `manifestId`, and projection `verificationId` using TJSV's canonical object-key ordering; checks the peer-authority model, mandatory contract-admission coverage, parity-run identity, source digests, declaration scope, projection identity, runtime-validator closure, output closure, and receipt summary; and retains exact byte digests for all three evidence artifacts. The canonical projection id is a bounded TJSV-safe identity derived from SHA-256 of the exact table name plus the shape kind.

`bundle::emit_admitted` is the only path that promotes a public derivative to `public_admitted`. It requires an in-process verified admission capability and proves that the TJSV-verified output path/digest set exactly matches the newly generated five-artifact bundle. Deserializing an admission manifest clears that in-process capability, so a stored or fabricated JSON object cannot be replayed directly as authorization to publish.

The admitted manifest format is `ores.orm-core.derivative-manifest/v3`. TypeSpec and independently authored JSON Schema remain the only contract authorities; Contract IR, projection manifests, runtime evidence, projection receipts, and ORM derivatives remain downstream evidence.

