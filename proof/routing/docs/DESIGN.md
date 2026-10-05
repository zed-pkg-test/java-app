# Router design

## Goals

The router should be a small pure-Oreslang policy layer that can sit behind
multiple HTTP transports without acquiring transport authority itself. Its
runtime hot path should be predictable, bounded, AOT-friendly, and easy to
share read-only between request workers or actors.

## Registration versus dispatch

RouterBuilder is mutable setup state. Each route is lowered into a segment trie.

RouterBuilder.build() then copies the trie into a flat structure-of-arrays
representation:

- node_static_start
- node_static_count
- node_param_child
- node_wildcard_child
- node_endpoint
- static_literals
- static_children
- endpoints

The resulting Router does not retain Builder or BuildNode references. A router
is therefore a snapshot: later registrations can produce a newer generation
without changing requests already using the old generation.

This is useful for OresVM hot reload as well: a server can publish a new router
generation atomically while in-flight work retains the previous immutable
generation.

## Match algorithm

Each request is already segmented. At each trie node the router attempts:

1. exact/static edge;
2. parameter edge;
3. wildcard edge.

A static or parameter branch may fail deeper in the tree, so the matcher
backtracks to the next less-specific branch. This avoids insertion-order bugs
while retaining conventional specificity.

The search is bounded by max_match_steps and path length is bounded by
max_segments. A limit failure is a separate Decision state so an adapter can
map it to its chosen HTTP policy without confusing it with a genuine 404.

Wildcards are terminal. They can match zero or more remaining path segments.

## Path-first method semantics

Path matching happens before method selection. Once the most-specific path is
found, method dispatch is limited to that endpoint.

This matters for security and predictability. A DELETE request to a known
/static path must not fall through to a broader parameter or wildcard route
merely because that broader route happens to accept DELETE.

Per endpoint:

1. exact method;
2. explicit '*' method;
3. HEAD -> GET fallback when enabled;
4. automatic OPTIONS when enabled;
5. method-not-allowed.

Explicit HEAD and OPTIONS always beat synthesis.

## Captures

The router does not allocate a Map for every request. PathCapture is a view over
the original RequestPath with a start offset and count. A parameter capture has
count 1; a wildcard capture spans the remainder.

Capture names are part of the registered PathPattern. Duplicate capture names
inside one pattern are rejected. Two routes that occupy the same trie shape
must use the same parameter/wildcard name at each dynamic edge.

## Handlers and middleware

The routing core never stores host-language closures, ActorCell values, VM
objects, socket handles, or scheduler references.

RouteTarget carries:

- handler_id
- pipeline_id
- contract_id
- operation_id

These are stable metadata. The transport/application layer owns the tables that
map IDs to executable behavior. That lets applications represent Phoenix/Plug
or Tower-style middleware pipelines without coupling the router to a specific
callable ABI, and lets actor-backed servers route by message/handler ID.

## Schemas and generated clients

contract_id is intended to reference the application's authoritative contract
registry. For ORES projects that can be TypeSpec plus independently-authored
JSON Schema, with OpenAPI or other generated artifacts derived at build time.

operation_id is deliberately carried through the hot-path Decision so logging,
tracing, metrics, OpenAPI generation, and client generation can use one stable
route identity rather than reconstructing it from a raw URL.

## Future optimizations

The current static child fan-out is a compact linear scan inside each node.
That keeps the first implementation deterministic and dependency-free.

Once Oreslang has a stable hash/index primitive, high-fanout nodes can gain a
hashed index or radix-compressed static edges without changing the public API.
The path representation also leaves room for a future compile-time
string-template parser and generated route tables.
