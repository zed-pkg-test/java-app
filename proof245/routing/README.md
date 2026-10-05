# oreslang-http-routing

A native Oreslang HTTP routing library with a compiled segment trie, deterministic
precedence, explicit HTTP method semantics, zero-copy path captures, and
transport-neutral handler metadata.

The router is intentionally **not** an HTTP server. Network listeners, HTTP/1.1
or HTTP/2 parsing, percent-decoding, TLS, body streaming, and host capabilities
belong in an adapter. This package owns the part that should be portable across
Oreslang server hosts: route registration, route compilation, path matching,
method dispatch, capture extraction, and routing metadata.

## Design influences

The API combines the strongest ideas from modern routers without cloning any
one framework:

- Hono / Fastify / Chi / Roda: tree/trie dispatch instead of a flat route scan.
- Axum: routing is separate from typed extraction and transport services.
- Phoenix / Plug / Chi: middleware is composable metadata rather than hidden
  global state.
- Fastify / FastAPI / Elysia: routes carry schema/operation metadata suitable
  for validation, generated clients, and OpenAPI.
- Go net/http: HTTP method behavior is explicit and unsurprising.

The compiled router is a flat structure-of-arrays table. Registration uses a
mutable builder; build produces a detached snapshot suitable for read-only
sharing.

## Quick start

```ores
import module http_routing from "src/http_routing";

let routes = http_routing.builder();

val show_user = http_routing.path("/users/{id}", arr[
  http_routing.literal("users"),
  http_routing.param("id")
]);

routes.get(
  show_user,
  1001,          // handler_id
  7,             // pipeline_id
  42,            // contract_id
  "users.show"   // operation_id
).unwrap();

val router = routes.build();

val decision = router.resolve(
  "GET",
  http_routing.request_path(arr["users", "abc123"])
);

if decision.is_match(); do
  val user_id = router.capture_one(decision, "id").unwrap();
  stdio.println(user_id);
fi
```

Handlers, middleware pipelines, and contracts are opaque integer IDs by
design. A server adapter resolves them after routing. That keeps host objects,
VM internals, sockets, and actor implementation objects out of the routing
table.

## Route patterns

Patterns are currently constructed from segments:

- `literal("users")` — exact segment
- `param("id")` — one named segment
- `wildcard("rest")` — zero or more remaining segments; must be last
- `root()` — root path

Matching precedence is **static > parameter > wildcard**, with bounded
backtracking. A static branch that exists but dead-ends does not incorrectly
hide a valid parameter route.

Repeated capture names inside one pattern are rejected. Different routes may
use different capture names on a shared dynamic trie edge when their suffixes
diverge. Registrations that resolve to the same complete route shape must agree
on the canonical template and capture layout, so capture semantics remain
deterministic without unnecessarily coupling sibling routes.

### Why no string-template parser yet?

The compiler stack pinned by `SOURCE_REF` has native Array storage but does
not yet expose the general String splitting/slicing API that a pure-Oreslang
template parser should depend on. Rather than smuggle Java interop into the
router, the transport/build adapter supplies tokenized path segments.

A later `path("/users/{id}")` parser can be layered over this same segment
contract without changing the compiled router or match semantics.

## HTTP behavior

- Exact method wins.
- `any(...)` registers `*` as an explicit catch-all method.
- HEAD falls back to GET by default and sets `decision.suppress_body = true`.
- Explicit HEAD overrides GET fallback.
- OPTIONS is synthesized by default when no explicit OPTIONS/`*` target exists.
- A matched path with an unsupported method returns method-not-allowed rather
  than searching for a less-specific path.
- `allow_count` / `allow_method_at` expose the finite Allow set, including
  synthesized HEAD and OPTIONS.
- An endpoint with an explicit `any(...)` / `*` target has no finite Allow
  enumeration: `allow_count` returns 0 and `allow_method_at` returns None.
  The internal `*` sentinel is never emitted as an HTTP method.
- Unknown paths are distinct from method-not-allowed.
- Trailing/repeated slash semantics are strict because empty segments are
  preserved by the adapter contract.

## Safety and performance

- No regex engine in the core matcher, so route matching has no regex-ReDoS
  surface.
- Request path length and total matcher work are bounded. The work budget
  includes trie-node visits, static fan-out comparisons, and method-target
  comparisons; configured limits also have hard safety ceilings (512 path
  segments and 65,536 matcher steps).
- Each endpoint is capped at 64 method targets to keep method dispatch and
  Allow synthesis bounded.
- Foreign/out-of-range Decision values are rejected by capture/Allow helpers
  rather than indexing another router's endpoint table.
- Wildcards are terminal and captures are views over the existing RequestPath;
  matching does not allocate parameter maps.
- The built routing table is detached from RouterBuilder mutations.
- The request-time structure is flat arrays rather than a graph of maps and
  closures, which is friendly to cache locality and AOT.
- No Java interop, filesystem access, sockets, reflection, or other ambient
  authority is required by the routing core.

See [docs/DESIGN.md](docs/DESIGN.md) and
[docs/ADAPTER_CONTRACT.md](docs/ADAPTER_CONTRACT.md).

## Testing

This repository pins the compiler/runtime revision in `SOURCE_REF`.

```bash
ORESLANG_COMPILER=/path/to/oreslang-compiler bash scripts/test.sh
```

The suite covers precedence/backtracking, captures, wildcard behavior, 404/405,
finite Allow synthesis, HEAD/OPTIONS semantics, metadata validation, strict
slash behavior, builder snapshot isolation, same-shape conflicts, safe
divergent capture names, foreign Decision rejection, static-fanout budgeting,
and routing limits.

A parser-independent style/safety audit runs before the compiler tests:

```bash
bash scripts/audit.sh
```

The pinned compiler revision is the native-collections feature stack plus the mandatory module-`as` parser integration,
so this library intentionally tracks that exact SHA rather than silently
testing against an unrelated local Oreslang checkout.
