#include "oreslang/runtime/HungryCarrier.hpp"
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

using namespace oreslang::runtime;
using namespace std::chrono_literals;

// This probe observes the REAL native carrier admission API and handler.
// It does not represent a general-purpose actor instrumentation API:
// actors compiled from .ores are not yet wired to HungryCarrier.
struct TraceState {
    static constexpr unsigned count = 24;
    std::array<std::uint64_t, count> observed{};
    std::atomic<unsigned> received{0};
    std::atomic<bool> wrong_thread{false};
    std::atomic<std::uint64_t> first_thread{0};
    std::atomic<bool> invalid_message{false};
};

static ActorStep trace_handler(void* raw, ActorMessage message,
                               bool resumed, std::int64_t) noexcept {
    auto& state = *static_cast<TraceState*>(raw);
    const auto thread_tag = static_cast<std::uint64_t>(
        std::hash<std::thread::id>{}(std::this_thread::get_id()));
    std::uint64_t initial = 0;
    (void)state.first_thread.compare_exchange_strong(initial, thread_tag);
    if (state.first_thread.load(std::memory_order_acquire) != thread_tag)
        state.wrong_thread.store(true, std::memory_order_release);
    const auto index = state.received.load(std::memory_order_relaxed);
    if (resumed || index >= TraceState::count || message.id == 0 ||
        message.input != static_cast<std::int64_t>(message.id * 3)) {
        state.invalid_message.store(true, std::memory_order_release);
        return {StepKind::done, 0, 0};
    }
    // Only the actor carrier touches observed[]; release count publishes it.
    state.observed[index] = message.id;
    state.received.store(index + 1, std::memory_order_release);
    return {StepKind::done, 0, 0};
}

static std::int64_t unused_broker(void*, std::int64_t input,
                                  const std::atomic<bool>&) noexcept {
    return input;
}

static void require(bool value, const char* detail) {
    if (!value) throw std::runtime_error(detail);
}

static bool reached(const TraceState& state, unsigned target) {
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (std::chrono::steady_clock::now() < deadline) {
        if (state.received.load(std::memory_order_acquire) >= target)
            return true;
        std::this_thread::sleep_for(1ms);
    }
    return state.received.load(std::memory_order_acquire) >= target;
}

static void run(bool emit_trace) {
    TraceState state;
    HungryCarrier actor(&state, trace_handler, nullptr, unused_broker, {});
    auto producer = actor.producer_handle();
    actor.start();
    std::array<std::uint64_t, TraceState::count> admitted{};
    for (unsigned i = 0; i < TraceState::count; ++i) {
        const std::uint64_t id = static_cast<std::uint64_t>(i + 1);
        require(producer.try_send({id, static_cast<std::int64_t>(id * 3)}),
                "message admission failed");
        admitted[i] = id; // record only a successful admission
        require(reached(state, i + 1), "native actor did not invoke handler");
        // The handler publishes its observation before the carrier accounts
        // the completed turn. Wait for both before requesting shutdown.
        const auto deadline = std::chrono::steady_clock::now() + 2s;
        while (actor.stats().completed < i + 1 &&
               std::chrono::steady_clock::now() < deadline)
            std::this_thread::sleep_for(1ms);
        require(actor.stats().completed == i + 1,
                "native handler did not complete its turn");
    }
    actor.join();
    const auto stats = actor.stats();
    require(stats.accepted == TraceState::count &&
            stats.completed == TraceState::count &&
            stats.abandoned_on_stop == 0 && stats.protocol_failures == 0,
            "native queue accounting did not agree with observed messages");
    require(!state.wrong_thread.load() && !state.invalid_message.load() &&
            state.first_thread.load() == stats.actor_thread_tag,
            "native actor violated single-carrier handler invariant");
    for (unsigned i = 0; i < TraceState::count; ++i)
        require(admitted[i] == state.observed[i],
                "native admitted and handled message IDs do not agree");
    require(!producer.try_send({999, 2997}), "closed actor handle admitted a message");

    if (!emit_trace) {
        std::cout << "PASS native actor ingress and delivery of "
                  << TraceState::count << " messages\n";
        return;
    }
    // This JSON is assembled AFTER quiescence from observed actual admissions
    // and handler invocations; it encodes causal pairs, not wall-clock timestamps.
    std::cout << "{\"schema_version\":\"1.0.0\",\"complete\":true,\"events\":[";
    unsigned seq = 0;
    std::cout << "{\"seq\":" << seq++
              << ",\"kind\":\"spawn\",\"actor\":\"native_actor\"}";
    for (unsigned i = 0; i < TraceState::count; ++i) {
        std::cout << ",{\"seq\":" << seq++
                  << ",\"kind\":\"admit\",\"actor\":\"native_actor\",\"message_id\":\"m"
                  << admitted[i] << "\"}";
        std::cout << ",{\"seq\":" << seq++
                  << ",\"kind\":\"receive\",\"actor\":\"native_actor\",\"message_id\":\"m"
                  << state.observed[i] << "\"}";
    }
    std::cout << ",{\"seq\":" << seq++
              << ",\"kind\":\"terminate\",\"actor\":\"native_actor\"}]}\n";
}

int main(int argc, char** argv) {
    try {
        if (argc > 2 || (argc == 2 && std::string(argv[1]) != "--trace"))
            throw std::invalid_argument("usage: oreslang-actor-trace-test [--trace]");
        run(argc == 2);
    } catch (const std::exception& ex) {
        std::cerr << "FAIL native actor trace: " << ex.what() << "\n";
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}
