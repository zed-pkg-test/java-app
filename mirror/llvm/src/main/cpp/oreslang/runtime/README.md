# Future LLVM native runtime

Track execution-domain/actor ABI work in [issue #1](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/1).
This directory intentionally contains no executable actor or memory allocator.

Java reference classes: `ActorRuntime`, `OresScheduler`, `OresFuture`, `RuntimeGarbageCollector`.
Native phases: structured cancellation, generational wake IDs, pinned/relocatable async frames,
actor mailbox lease, supervisor trees, independent physical heap regions and bounded arenas.

No RT scheduler policy, zero-GC or actor isolation is currently implemented.

## Dedicated hungry actor idle/I/O semantics — design only

Read [Hungry isolated actor idle/I/O contract](../../../../../docs/HUNGRY_ACTOR_IDLE_IO.md).
A dedicated thread is **not** a permanently busy-spinning thread: an unresolved
`await` or blocking `select` suspends an actor continuation and, if no eligible
work remains, the carrier ordinarily parks after a bounded polling window.
A long (100–200 ms) I/O operation belongs on an async I/O path/broker; the
actor retains its thread/core reservation, and a generation-fenced completion
wakes it. Strict no-GC hungry actors do not run arbitrary GC while idle.
No actor, I/O, affinity, reservation, or GC behavior described by the contract
exists yet in this C++ runtime scaffold.

## Audit implementation slice: Linux ParkingSignal (experimental)

`ParkingSignal.hpp/.cpp` supplies **one carrier-bound waiter with concurrent
notifiers**, backed by nonblocking `eventfd` and a monotonic `poll(2)` deadline.
It prevents lost notifications across the ready-check/park race, verifies the
dedicated carrier thread identity, handles spurious notifications, and makes
cancellation wake the carrier when the caller publishes stop + notifies.

The API is **NOT** an actor, mailbox, ownership checker, continuation scheduler,
CPU isolation manager, async I/O broker or zero-GC allocator. It does not claim
kernel-level lock freedom; `eventfd` uses syscalls and internal kernel locking.
It only guarantees that no *user-space actor heap mutex* is required by this
notification primitive. A bounded lock-free data queue and separate reserved
control/completion capacity are future work.

The producer MUST publish a thread-safe ready predicate/queued completion
before `notify()` (release/acquire); notification alone carries no payload.
The caller MUST join its single waiter and quiesce **all** concurrent notifiers
before destroying the signal. Error paths throw, so this prototype is **not**
certified for no-allocation/hard realtime hot paths. Install/warm it before
entering realtime scheduling; validate scheduling and memory resources
separately.

Linux CTest target: `parking_signal_wake_cancel_races`.
Implementation scope and remaining hazards: [audit](../../../../../docs/HUNGRY_ACTOR_AUDIT.md).

## Experimental dedicated carrier and bounded I/O integration

`HungryCarrier.hpp/.cpp`, `BoundedMpscQueue.hpp`, and `FixedArena.hpp`
build on `ParkingSignal` as the **first executable native actor-like runtime
slice**. Unlike the park-only signal, this slice starts a dedicated native
carrier plus a separate broker thread. A typed POD request is delivered via
a fixed 64-slot lock-free MPSC user mailbox. Exactly one handler executes
at a time; `ActorStep::await_io` suspends that handler and parks the carrier.
A broker posts the completion in its own fixed two-slot queue, and the
same actor carrier thread resumes the continuation. Other user handlers
wait until the first is finished, enforcing default serial non-reentrancy.

A separate stop flag and eventfd wake channel remain usable when the user
mailbox is full. One in-flight I/O operation per serial handler plus per-op
generation IDs make the completion queue bounded; mismatched completions
never resume a stale continuation. A separate fixed POD arena provides
bounded allocations without tracing GC but **does not** implement general
heap ownership, cycles, destructors, proof of no malloc by arbitrary guest
code, or native borrow checking.

**Scope limits and security conditions:**
- The native Oreslang parser/compiler still rejects source actor constructs;
  this is a C++ runtime test ABI, not compiled guest Oreslang continuation code.
- Broker callbacks must own their buffers independently and must never
  access `actor_state`. Their lifetimes, the caller's senders, and any
  external descriptors must outlive thread shutdown; cross-actor memory
  isolation is not yet enforced by the compiler or an OS memory boundary.
- The example delay is cancellable; a synchronous broker callback (including
  `recv`/native FFI) **may not be interruptible**, so shutdown can wait for
  it. Production strict realtime profiles must reject unbounded callbacks
  and use a proven asynchronous cancellation/acknowledgement protocol.
- On a kernel notification error the component fails closed and requests
  stop. It does not promise bounded cleanup/destructor time.
- Optional logical CPU pinning is verified on the worker but **does not**
  reserve a physical CPU, isolate SMT siblings, configure IRQ/cgroup/RT
  policies, or prove a hard latency ceiling. Startup refuses invalid affinity.
- No actor hot-path GC runs inside this prototype, but that is **not** proof
  that user callbacks/OS functions cannot allocate or pause.
- `std::atomic` lock freedom is hardware/platform dependent. The MPSC queue
  avoids C++ mutexes but the eventfd/poll kernel wake path is not lock-free.
- `stop` is terminal: it cancels the suspended user continuation; the broker
  must exit and be joined before the runtime can be destroyed.
- The actor object MUST outlive all concurrently calling producers.
- CTest `hungry_carrier_async_bounded_cancel` covers 150 ms delayed
  completion, actual loopback socket I/O on a broker, serial handler
  ordering, mailbox pressure, stop/cancel, four concurrent producers,
  fixed arena bounds and invalid CPU admission.

Next: true `.ores` actor ABI lowering, safe cross-actor ownership and
cycle reclamation, kernel async I/O cancellation with operation lifetime
fencing, adaptive spin/park metrics, strict host core admission and realtime
latency benchmarking. See [audit](../../../../../docs/HUNGRY_ACTOR_AUDIT.md).

## Cooperative cancellation for bounded broker operations

The native broker ABI now passes a read-only atomic stop token into each
host `BrokerOperation`. Trusted callbacks must check it regularly and only
invoke bounded/nonblocking transport syscalls. The socket example uses
10-ms poll slices and `MSG_DONTWAIT` reads to avoid waiting 500 ms for
a peer that never responds. When cancellation wins the atomic operation
terminal CAS, no actor handler resumes; broker-owned memory is retained
until the worker has returned and joined.

This is **cooperative cancellation**, not forced interruption of a blocking
kernel operation or arbitrary FFI; unbounded host callbacks must not be
admitted to strict low-latency profiles. Kernel io_uring completion/cancel
acknowledgement, pinned buffer leases and cross-process ownership remain
future work.

## Shutdown race and ring-head hardening

The lifecycle owner must prevent *new* producer calls from beginning
once it initiates destruction; `join()` now stops the actor and waits
for already-entered `try_send()` calls before worker/signal teardown.
An enqueued request may be **abandoned** after concurrent stop, and
`abandoned_on_stop` records this explicitly after `join()`.
Do not treat `try_send() == true` as confirmation that user code ran.

The MPSC consumer's `has_ready()` predicate checks that its **head
slot** is fully published. An independent queued-item count can report
false readiness if a producer reserves the head and stalls, potentially
causing a realtime carrier to busy-spin instead of park. The ring is
lock-free but not wait-free; the head-of-line producer remains a
progress dependency. Termination from the worker thread itself is
unsafe and now fails closed; a single external supervisor must own
`join()`/destruction.


## Stall-resistant dedicated ingress lanes (optional native profile)

In addition to the general bounded **MPSC** mailbox, the native carrier
can register up to four **SPSC** producer lanes before starting. Each lane
has a move-only, independently lifetime-protected \`DedicatedProducer\`
handle, a 64-message preallocated ring and exactly one active producer
at any instant. Concurrent use of the same handle returns false; it does
not turn a lane into an unsafe MPSC. Registration/start use a supervisor
mutex *outside* the actor's critical execution path.

The carrier uses round-robin polling across the general MPSC queue and
all registered SPSC lanes. If a producer stalls after writing an
**unpublished** lane-A entry, lane B can still publish and deliver work.
This mitigates MPSC head-of-line blocking without allowing incomplete
payloads to be consumed or another actor's handler to run concurrently.
The carrier still owns and executes every user handler serially, and
a pending \`await_io\` continues blocking all other user handlers.

A deterministic two-lane test stalls producer A precisely between
write and release-publication; independent lane B must still publish and
be consumed. End-to-end actor tests exercise 300 messages per lane,
late-registration rejection, capacity exhaustion and a lane handle
surviving actor destruction.

**Limitations:** per-lane FIFO but no total multi-producer global order;
slow producer A can still block *its own* lane; all producer handles
must be registered by the supervisor before \`start()\`; maximum lanes
and per-lane capacity are fixed. This is still a Linux C++ runtime
experiment, **not** compiled Oreslang language actors, OS CPU pin
reservation or verified no-GC execution. Extra control block refcounts,
instrumented test hooks and OS wake syscalls are not strict RT-certified.


## Producer-facing API gate

The raw \`HungryCarrier::try_send()\` interface has been removed from the
public API. General producers must receive a copyable \`ProducerHandle\`
from the supervisor; dedicated SPSC producers use move-only
\`DedicatedProducer\`. Both access the mailbox ONLY after atomically
acquiring the carrier's lifetime gate. After shutdown starts, admission
fails, and outstanding handle leases are quiesced before any queue or
eventfd storage can be reclaimed. The hot send still does not allocate
its payload, but handle refcount operations and OS notification costs are
not strict hard-realtime certified. Copyable handles can outlive the actor
and reject sends after it is destroyed.
