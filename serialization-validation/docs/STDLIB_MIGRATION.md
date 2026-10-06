# Std-lib/core migration and format hardening plan

## Goal

This repository is the proving ground for Oreslang serialization, deserialization,
schema metadata, and validation. The long-term public surface should move toward
`core` / `std` only after each primitive has a stable ownership contract,
bounded resource behavior, and cross-format tests.

The split should be:

- **core candidates**: byte invariants, UTF-8, bounded codec options/errors,
  dynamic `Value`, incremental decode result protocol, and generated-codec ABI;
- **std serialization**: JSON, MessagePack, CBOR, Protobuf wire + descriptor
  adapters, XML, YAML, TOML, and stream/channel convenience APIs;
- **compiler/build integration**: typed codec generation, field metadata,
  annotations/attributes, schema import/export, and no-reflection direct writes.

Do not make every format a VM primitive. Core should contain the stable contracts
needed to implement codecs efficiently; format policy belongs in std-lib unless a
specific primitive proves impossible to express safely in Oreslang.

## Ownership contract

Oreslang source is pointer-free. Serialization code must not introduce C/Rust
source syntax such as `&value`, `&mut value`, or `*ptr`.

Normal function arguments use managed-reference semantics. Explicit ownership
operations are compiler-owned `rt` operations:

- `rt borrow value`: temporary/read-only borrowing without transfer;
- `rt take value`: explicit ownership transfer;
- `rt copy value`: detached copy when the type's copy contract permits it;
- `rt share value`: explicit read-only sharing according to the ownership model.

For decoding, the default fast path should borrow immutable input while parsing
and allocate only result state. Incremental decoders may own their accumulator.
A future chunk-splicing API may consume a chunk with `rt take`; it must not hide
an implicit move behind an ordinary call.

Channels remain data-only. Never send functions/callbacks, parser cursors, host
VM objects, reflection handles, locks, or mutable codec internals through a
`Channel<T>`.

## Async and channel integration

Use the built-in concurrency model instead of defining another scheduler:

- blocking: `readch` / `writech`;
- immediate probe: `try readch` / `try writech`;
- future-returning: `nb readch` / `nb writech`;
- callback completion where actor-scoped callbacks are appropriate:
  `nb cb readch` / `nb cb writech`;
- multiplexing/cancellation: `select`, `nb select`, and reusable
  `SelectSet` / `SelectCase`;
- composition: native Oreslang `Future<T>` and `async` / `await`.

The initial JSON adapter accepts `Channel<Option<List<int>>>` where
`Some(chunk)` carries bytes and `None` means EOF. Parser state remains local
to the consumer.

For multi-value protocols, do not overload one-value decode. Provide explicit
framing surfaces:

- NDJSON / JSON Lines;
- JSON text sequences where supported;
- length-delimited Protobuf;
- concatenated MessagePack;
- concatenated CBOR when the selected profile permits it.

Every sequence decoder must expose the consumed byte count or retain an
unconsumed suffix. It must never consume bytes belonging to the next value.

## Format matrix

| Format | Model | Current status | Required hardening before std-lib promotion |
| --- | --- | --- | --- |
| JSON | text/tree | native dynamic + incremental | canonical option, sequence framing, borrowed view/tape, fuzz/property vectors |
| MessagePack | binary/tree | native dynamic | incremental state machine, float bits, ext policy, canonical/minimal option |
| CBOR | binary/tree | definite-length baseline | floats, tags policy, indefinite-length streaming, deterministic/canonical profile |
| Protobuf | binary/schema | wire fields | descriptors/generated typed codecs, packed/map/oneof/proto2 presence, delimited stream |
| XML | text/tree/event | not implemented | disable external entities/DTD by default, namespace/attribute limits, event parser |
| YAML | text/tree/graph | not implemented | alias/anchor expansion limits, tag allowlist, cycle policy, YAML version/profile |
| TOML | text/config | not implemented | duplicate-key/table rejection, datetime exactness, bounded arrays/inline tables |
| JSON Schema | schema | metadata path | draft/version policy, reference resolver limits, cycle handling |
| TypeSpec | schema | planned integration | deterministic lowering into codec metadata + validation rules |

Other formats (BSON, Avro, Thrift, FlatBuffers, Cap'n Proto, CSV, multipart,
form encoding) should be added only when their type/wire semantics justify a
separate codec rather than being forced through the JSON-shaped `Value` model.

## Security invariants

All decoders must fail closed and be resource bounded before allocation.

Global requirements:

1. validate every byte source before treating integer list elements as bytes;
2. check declared lengths against remaining bytes before allocation/copy;
3. reject arithmetic overflow while computing lengths, offsets, field keys, or
   output sizes;
4. enforce depth, input, string/binary, container-entry, and output limits;
5. define duplicate-key behavior explicitly;
6. reject invalid UTF-8 by default for textual formats;
7. distinguish incomplete input from malformed input for incremental parsers;
8. never permit host/runtime objects to escape into guest values;
9. preserve unknown wire data only under an explicit bounded policy;
10. keep typed decoding reflection-free on the hot path.

### XML

Defaults must reject DTDs and external entities. No filesystem/network entity
resolution is permitted without a separate explicit capability. Bound entity
expansion, attributes per element, namespaces, text-node bytes, and depth.

### YAML

Aliases and anchors turn YAML into a graph, not merely a JSON-like tree.
Decoding must bound alias references and expanded nodes, reject or explicitly
represent cycles, and use an allowlist for application tags. Never instantiate
arbitrary host classes from YAML tags.

### Protobuf

Schema-less wire parsing preserves numeric fields only. Do not invent names
without descriptors. Descriptor-driven decode must bound nested messages,
packed element counts, unknown-field storage, and descriptor recursion.

### MessagePack / CBOR

Preserve binary-vs-text distinctions. General maps may have non-string keys.
Extension/tag interpretation must use an explicit registry/policy rather than
executing arbitrary application behavior during parse.

## Canonical/deterministic encoding

Canonical encoding is a policy, not the default meaning of "valid".

Add explicit options for:

- deterministic object/map ordering where the format defines it;
- shortest legal integer/length forms;
- normalized floating-point/NaN behavior when float primitives land;
- Protobuf deterministic ordering as an encoder option, without claiming the
  wire format itself is canonical;
- CBOR deterministic encoding profiles.

Decoders may optionally reject non-canonical encodings for signature/hash use
cases, but ordinary interoperable decoding should remain standards-compliant.

## Test gates for std-lib promotion

A format is not ready for std-lib/core migration until it has:

- positive round-trip vectors;
- negative malformed-input vectors;
- truncation at every byte boundary for representative values;
- exact max-limit and max-limit+1 tests;
- duplicate-key/unknown-field policy tests;
- integer boundary/overflow tests;
- UTF-8 boundary tests where applicable;
- streaming splits inside prefixes, escapes, varints, and multibyte UTF-8;
- fuzz/property tests against at least one independent implementation when
  practical;
- deterministic/canonical vectors if that mode is advertised;
- async/channel cancellation and backpressure tests for streaming adapters.

## Migration order

1. stabilize `core/bytes`, `core/utf8`, `core/options`, and `core/value`;
2. stabilize JSON + incremental result protocol;
3. extract generic byte-source / byte-sink and channel adapters;
4. converge MessagePack + CBOR on the same incremental protocol;
5. add descriptor-backed Protobuf typed codecs and delimited streams;
6. add TOML (small, non-graph text format);
7. add XML event parser with entity resolution disabled by default;
8. add YAML only with alias/tag/cycle limits designed first;
9. move proven primitives into core/std and leave this repository as a
   compatibility, conformance, fuzz, benchmark, and incubation suite.
