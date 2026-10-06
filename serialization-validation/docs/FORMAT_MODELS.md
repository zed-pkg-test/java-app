# Format-specific semantic models

The shared `Value` type is the wire-neutral tree used by JSON, MessagePack,
and CBOR. It is intentionally **not** the universal representation for every
serialization format.

For std-lib/core migration, each format must preserve the semantics needed for
lossless or policy-explicit decoding. Projection into `Value` is optional and
must fail rather than silently erase information.

## JSON / MessagePack / CBOR

These formats map naturally to the current `Value` tree:

- null / boolean;
- number lexeme or exact integer representation;
- UTF-8 text;
- bytes where the wire format distinguishes binary;
- arrays;
- string-key objects;
- general maps;
- explicit extension/tag payloads only when the format policy defines them.

MessagePack extension values and CBOR tags must stay explicit. Never execute an
application hook merely because an extension/tag was present on the wire.

## Protocol Buffers

Schema-less Protobuf is **not** a `Value` tree. Its canonical dynamic surface
is numbered wire fields plus exact unknown bytes.

Descriptor-backed/generated decoding may project into typed Oreslang objects or
into a schema-aware dynamic value, but it must preserve:

- field number and wire type;
- proto2/proto3 presence rules;
- oneof selection;
- packed repeated fields;
- map-entry semantics;
- unknown fields under a bounded explicit policy.

## XML

XML requires its own event/tree model. Mapping XML directly to a JSON-like
object loses information.

The native model must preserve at least:

- expanded element name: namespace URI + local name;
- prefix where round-trip fidelity is requested;
- ordered attributes, with namespace declarations handled separately;
- ordered child content so mixed text/element content remains lossless;
- processing instructions/comments only when the selected fidelity profile
  requests them;
- source text/CDATA distinction only when exact lexical round-trip is requested.

Security defaults:

- DTD disabled;
- external entities disabled;
- no filesystem/network resolution without an explicit capability;
- bounded depth;
- bounded attributes per element;
- bounded namespace declarations;
- bounded text bytes;
- bounded total nodes.

A convenience `xml -> Value` projection may exist, but it must require an
explicit mapping profile and reject ambiguous mixed-content documents.

## YAML

YAML is a graph format, not merely JSON with different punctuation.

The native model must preserve:

- scalar tag and scalar text;
- sequence/mapping nodes;
- anchors and aliases;
- application tags under an allowlist;
- cycle identity when aliases form cycles.

Security defaults:

- bounded anchors;
- bounded alias references;
- bounded expanded-node count;
- bounded nesting;
- bounded scalar bytes;
- application tags disabled unless explicitly registered;
- no host-class/object construction from tags.

Provide a separate **JSON-compatible YAML profile** that rejects aliases,
application tags, non-string mapping keys, and other graph-only features before
projecting to `Value`.

## TOML

TOML is a typed configuration tree. Its model must preserve the distinctions
between:

- string;
- integer;
- float;
- boolean;
- offset date-time;
- local date-time;
- local date;
- local time;
- array;
- table;
- inline table.

Do not coerce TOML dates/times to plain strings in the lossless API. A
JSON-compatible projection may stringify them only under an explicit policy.

Reject duplicate keys/table redefinitions according to the selected TOML
version and bound table depth, array length, key length, and total input bytes.

## API layering

The target layering is:

```text
bytes
  |
  +-- format-native parser/event model
  |      |
  |      +-- lossless/native dynamic API
  |      +-- typed generated codec
  |      +-- explicit Value projection (when valid)
  |
  +-- validation metadata / schema policy
```

This keeps `core/value` small and stable. XML/YAML/TOML semantics belong in
their std-lib format modules rather than expanding one global tagged union every
time a format has unique concepts.

## Ownership

Source remains pointer-free.

- ordinary ownership transfer must be explicit at the call site with `rt take`
  when the API owns the supplied buffer/value;
- retained detached data uses `rt copy` only when the type's copy contract
  permits it;
- temporary read access should use the future pointer-free borrowed-parameter
  contract based on `rt borrow`;
- channel adapters own their stream/channel for the duration of decode and keep
  parser state local.

Do not add `&T`, `&mut T`, `T*`, or dereference syntax to these APIs.
