#pragma once
#include "oreslang/runtime/BoundedMpscQueue.hpp"
#include "oreslang/runtime/BoundedSpscQueue.hpp"
#include "oreslang/runtime/ParkingSignal.hpp"
#include <atomic>
#include <array>
#include <chrono>
#include <mutex>
#include <cstdint>
#include <memory>
#include <thread>

// Experimental native continuation carrier. This is an independent C++
// runtime skeleton: not yet wired into parsed/compiled .ores actors, the
// ownership checker, IOCP, io_uring, or realtime CPU partition admission.
// No C++ closures/heap objects cross the actor mailbox: only POD messages.
namespace oreslang::runtime {

struct ActorMessage {
    std::uint64_t id = 0;
    std::int64_t input = 0;
};
enum class StepKind : std::uint8_t { done, await_io };
// Per-operation terminal state. Only a Pending->Completed or
// Pending->Canceled transition may win. A new operation resets the state
// only after the previous broker result has been consumed on this carrier.
enum class OperationTerminal : std::uint8_t { idle, pending, completed, canceled };
struct ActorStep {
    StepKind kind = StepKind::done;
    std::int64_t value = 0;        // completion value or payload for IO
    std::uint32_t delay_ms = 0;    // test transport delay (not production I/O)
};
// Invoked ONLY on the one actor carrier thread. The actor-local state pointer
// cannot be accessed by the IO worker. This callback may NOT block, throw,
// allocate or run arbitrary GC in a future strict-no-GC admission profile.
// 'resumed' is true exactly once when the pending IO has completed.
using ActorHandler = ActorStep (*)(void* actor_state, ActorMessage message,
                                   bool resumed, std::int64_t result) noexcept;
// The IO callback runs on a separate broker thread, with separate owned IO
// state: it must NOT dereference the actor's private heap or use the carrier.
// It must periodically check the cancellation flag and use bounded/nonblocking
// syscalls. Cooperative cancellation does not forcibly interrupt arbitrary FFI.
using BrokerOperation = std::int64_t (*)(void* broker_state, std::int64_t payload,
                                         const std::atomic<bool>& cancel) noexcept;

struct CarrierStats {
    std::uint64_t accepted = 0;
    std::uint64_t completed = 0;
    std::uint64_t io_completions = 0;
    std::uint64_t parked = 0;
    std::uint64_t canceled_inflight = 0;
    std::uint64_t stale_completions = 0;
    std::uint64_t protocol_failures = 0;
    std::uint64_t actor_thread_tag = 0;
    std::uint64_t abandoned_on_stop = 0; // accepted minus finished turns; valid after join()
};

// Construction and thread start allocate and use syscalls, BEFORE any future
// realtime admission. Hot-path buffers are fixed-size. This deliberately does
// not claim that callbacks, syscalls or OS scheduling are allocation-free.
class HungryCarrier final {
    struct ProducerGate;
public:
    // Handles hold an independent shared lifetime gate. They may be copied,
    // sent to other native producer threads and survive carrier destruction.
    // Creation/copy can allocate or refcount; pre-create them before realtime
    // admission. Producers never dereference freed carrier storage.
    class ProducerHandle final {
    public:
        ProducerHandle() = default;
        bool try_send(ActorMessage message) const noexcept;
        explicit operator bool() const noexcept { return static_cast<bool>(gate_); }
    private:
        friend class HungryCarrier;
        explicit ProducerHandle(std::shared_ptr<ProducerGate> gate)
            : gate_(std::move(gate)) {}
        std::shared_ptr<ProducerGate> gate_;
    };

    static constexpr std::size_t queue_capacity = 64;
    static constexpr std::size_t max_dedicated_lanes = 4;
    // Move-only producer handle. Each lane has at most ONE active sender.
    // Concurrent attempts on the same handle fail immediately (not queued).
    // A stalled producer can delay its own lane but never the other lanes.
    // Handles are issued only by the supervisor BEFORE start().
    class DedicatedProducer final {
    public:
        DedicatedProducer() = default;
        DedicatedProducer(DedicatedProducer&& other) noexcept
            : gate_(std::move(other.gate_)), index_(other.index_) {}
        DedicatedProducer& operator=(DedicatedProducer&&) = delete;
        DedicatedProducer(const DedicatedProducer&) = delete;
        DedicatedProducer& operator=(const DedicatedProducer&) = delete;
        bool try_send(ActorMessage message) const noexcept;
        explicit operator bool() const noexcept { return static_cast<bool>(gate_); }
    private:
        friend class HungryCarrier;
        DedicatedProducer(std::shared_ptr<ProducerGate> gate, std::size_t index)
            : gate_(std::move(gate)), index_(index) {}
        std::shared_ptr<ProducerGate> gate_;
        std::size_t index_ = 0;
        mutable std::atomic_flag sending_ = ATOMIC_FLAG_INIT;
    };
    struct Options {
        std::chrono::microseconds spin_before_park{0}; // park-only default
        int pin_to_logical_cpu = -1; // optional affinity; NOT exclusive-core admission
        bool require_exclusive_core = false;  // fail closed until OS reservation manager exists
        bool require_strict_no_gc = false;    // fail closed until compiler/allocator proof exists
        bool require_hard_realtime = false;   // fail closed until watchdog/OS proof exists
    };
    HungryCarrier(void* actor_state, ActorHandler handler, void* broker_state,
                  BrokerOperation operation, Options opts);
    ~HungryCarrier() noexcept;
    HungryCarrier(const HungryCarrier&) = delete;
    HungryCarrier& operator=(const HungryCarrier&) = delete;

    void start();
    // Obtain before stopping/destructing the carrier; copies of returned
    // handles remain safe independently of the actor lifetime.
    ProducerHandle producer_handle() const noexcept;
    DedicatedProducer register_spsc_producer(); // single supervisor, before start
    // No raw-pointer send method: all producers must hold a lifetime-gated
    // ProducerHandle or move-only DedicatedProducer registered before start.
    // This prevents callers from bypassing shutdown admission checks.
    // Stop is an independent reserved control path (not an ordinary mailbox).
    void request_stop() noexcept;
    // A single external lifecycle owner calls join; it also initiates stop.
    // Joining/destroying from the actor or broker thread is fatal, not safe.
    void join() noexcept;
    bool stopped() const noexcept { return stopped_.load(std::memory_order_acquire); }
    CarrierStats stats() const noexcept;

private:
    struct Job {
        std::uint64_t generation = 0;
        std::int64_t payload = 0;
        std::uint32_t delay_ms = 0;
    };
    struct Completion {
        std::uint64_t generation = 0;
        std::int64_t value = 0;
    };
    void actor_loop() noexcept;
    void broker_loop() noexcept;
    void run_handler(ActorMessage, bool resumed, std::int64_t result) noexcept;
    void wait_actor() noexcept;
    bool has_input_ready() const noexcept;
    bool pop_next_input(ActorMessage& message) noexcept;
    bool try_send_spsc(std::size_t lane, ActorMessage message) noexcept;
    bool enqueue_mpsc(ActorMessage message) noexcept;
    bool pin_cpu() noexcept;

    void* actor_state_;
    ActorHandler handler_;
    void* broker_state_;
    BrokerOperation operation_;
    Options options_;
    std::shared_ptr<ProducerGate> producer_gate_;
    BoundedMpscQueue<ActorMessage, queue_capacity> messages_;
    std::array<BoundedSpscQueue<ActorMessage, queue_capacity>,
               max_dedicated_lanes> lanes_{};
    // Synchronizes only registration/start; never taken by the carrier hot path.
    std::mutex lane_registration_mutex_;
    std::size_t registered_lanes_ = 0; // immutable after start
    std::size_t next_input_ = 0;       // actor-carrier thread only

    BoundedMpscQueue<Job, 2> jobs_;          // one producer: carrier
    BoundedMpscQueue<Completion, 2> results_; // one producer: broker
    ParkingSignal actor_signal_;
    ParkingSignal broker_signal_;
    std::atomic<bool> stop_{false};
    std::atomic<bool> stopped_{false};
    std::atomic<bool> started_{false};
    std::atomic<bool> setup_done_{false};
    std::atomic<bool> setup_ok_{false};
    std::atomic<OperationTerminal> terminal_{OperationTerminal::idle};
    std::thread actor_thread_;
    std::thread broker_thread_;
    // Owned/exclusively accessed by carrier:
    bool pending_ = false;
    ActorMessage current_{};
    std::uint64_t generation_ = 0;
    // Atomic telemetry allows reads without touching actor-local state.
    std::atomic<std::uint64_t> accepted_{0}, completed_{0}, io_done_{0},
        parked_{0}, canceled_{0}, stale_{0}, failures_{0}, thread_tag_{0},
        abandoned_on_stop_{0};
};
} // namespace oreslang::runtime
