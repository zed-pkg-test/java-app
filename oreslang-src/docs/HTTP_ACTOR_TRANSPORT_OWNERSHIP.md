# HTTP transport ownership across actors

Oreslang uses a linear controlling-owner model for HTTP/TCP handoff, modeled
after Erlang/OTP `gen_tcp:controlling_process/2`.

## Invariants

1. A transport stream has exactly one controlling ownership domain:
   `SUPERVISOR`, one concrete `ACTOR`, or terminal `RELEASED`.
2. Only the supervisor may transfer initial ownership to an actor.
3. An actor must prove its `ActorId` on every transport operation.
4. Ownership transfer/revocation takes a write barrier; I/O takes a read lease.
   A transfer therefore cannot race in-flight reads/writes.
5. Released leases are never reusable. This prevents a stale capability from
   becoming valid if the kernel later reuses the same numeric file descriptor.
6. Raw OS file descriptors are never guest values and never cross actor
   mailboxes.
7. HTTP body bytes should normally flow directly between actor-owned memory and
   the transport. Mailbox streaming remains an explicit fallback for cases where
   ownership cannot or should not move.
8. Actor termination revokes transport authority before actor-confined memory is
   reclaimed.

## HTTP/1.1 vs HTTP/2 and HTTP/3

For raw TCP and HTTP/1.1, a lease may map to one accepted socket/file
descriptor.

For HTTP/2 and HTTP/3, the TCP/QUIC connection is multiplexed and must remain
owned by the connection supervisor. The transferable object is one
request/response stream capability. The same single-owner rules apply at stream
granularity.

## Oreslang ownership semantics

Transport capabilities are affine/linear resources and integrate with
`copy/take/share/borrow` as follows:

- `copy socket` — compile-time error.
- `share socket` — compile-time error, including for shared actors.
- `take socket` — the only cross-actor ownership move.
- `borrow socket` — allowed only within the current controlling actor/domain;
  a borrow may not outlive the owner or cross an actor boundary.

A successful actor spawn with `take` invalidates the supervisor-side value.
The runtime ownership publication and the language move must be one transaction:
either the target actor becomes the owner and the source value is consumed, or
the spawn fails and the source remains supervisor-owned (or is explicitly
closed). There must never be a state where both source and target are usable.

Untrusted actors do not receive a general-purpose raw socket capability. They
receive a narrowed, request-scoped HTTP capability after the supervisor has
already admitted/parses the request. This preserves the absence of generic
`NETWORK`, JNI, FFI, or native-handle authority.

## Shared actors

Shared actors live in the shared memory domain, but transport ownership is still
useful for protocol correctness. They do not need a memory-isolation handoff,
yet two actors must not concurrently consume or mutate the same HTTP stream
unless the transport explicitly implements a higher-level multiplexing
protocol.

## Isolated/private actors

The supervisor parses/adopts the connection, creates a transport lease, then
atomically transfers it to the target actor. Request/response bytes can move
directly into actor-owned native memory without mailbox copies.

## Mailbox streaming fallback

Direct ownership handoff is the default for raw TCP/HTTP/1.1 request streams
because it avoids body copies and preserves backpressure at the kernel/socket
boundary.

Mailbox streaming is the fallback when ownership cannot move. The supervisor
keeps the transport and sends **data-only** chunks to the actor. The standard
fallback must use:

- bounded chunk size,
- bounded mailbox depth,
- explicit credit/ack flow control so the supervisor cannot outrun the actor,
- immutable/copy-owned payloads only,
- no socket/fd/native handle in any mailbox message,
- cancellation propagated both directions,
- one terminal EOF/error message,
- the same request/response byte ceilings as direct handoff.

For an untrusted actor, mailbox-return data remains much smaller than direct
HTTP streaming data; large responses should use the owner-bound response
transport rather than serializing bytes back through the supervisor.

## Untrusted actors

Untrusted actors receive only the narrow request/response capability. They do
not receive `NETWORK`, JNI/FFI authority, a native socket object, or a numeric
fd. Existing byte, header, lifetime, CPU/fuel, and memory limits remain in
force. Actor death revokes the lease and aborts/cancels the bound transport.

## Native networking integration

The native-net layer should represent accepted sockets with an opaque runtime
handle containing at least:

- an internal fd/socket value,
- a monotonically unique generation/id,
- transport lifecycle state,
- its `HttpTransportLease`.

All native read/write/shutdown/close paths for actor-owned transports must enter
through the lease. Never authorize an operation by comparing only an integer
fd: fd numbers are reusable.

For an HTTP server the intended flow is:

```text
accept socket
  -> supervisor owns opaque transport
  -> parse/admit request
  -> choose actor
  -> transfer lease to actor (write barrier)
  -> actor streams request/response directly (read leases)
  -> actor completes or terminates
  -> runtime revokes capability
  -> close, or explicitly return a clean reusable connection to supervisor
```

Returning a keep-alive HTTP/1.1 connection to the supervisor should be a
separate explicit state transition added only when request-body draining,
response completion, parser state, and unread-buffer ownership can all be
proven clean.
