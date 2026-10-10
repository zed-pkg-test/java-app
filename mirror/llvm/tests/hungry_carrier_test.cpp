#include "oreslang/runtime/HungryCarrier.hpp"
#include "oreslang/runtime/FixedArena.hpp"
#include <sys/socket.h>
#include <cerrno>
#include <algorithm>
#include <unistd.h>
#include <poll.h>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <functional>
#include <memory>
#include <optional>
#include "oreslang/runtime/BoundedSpscQueue.hpp"
#include <iostream>
#include <stdexcept>
#include <type_traits>
#include <utility>
#include <thread>
#include <vector>
using namespace std::chrono_literals;
using namespace oreslang::runtime;

template <typename T, typename = void>
struct has_raw_send : std::false_type {};
template <typename T>
struct has_raw_send<T, std::void_t<decltype(std::declval<T&>().try_send(
    std::declval<ActorMessage>()))>> : std::true_type {};
static_assert(!has_raw_send<HungryCarrier>::value,
              "public direct actor sends must not bypass the producer gate");

static void check(bool value, const char* failure) {
    if (!value) throw std::runtime_error(failure);
}
template<class Pred>
static bool until(Pred pred, std::chrono::milliseconds duration) {
    const auto deadline = std::chrono::steady_clock::now() + duration;
    while (std::chrono::steady_clock::now() < deadline) {
        if (pred()) return true;
        std::this_thread::sleep_for(1ms);
    }
    return pred();
}
struct State {
    std::atomic<std::uint64_t> first_thread{0};
    std::atomic<int> initial{0}, resumed{0}, completed{0}, violations{0};
    std::atomic<bool> io_pending{false};
    bool await_io = false;
    std::uint32_t delay_ms = 0;
};
static ActorStep handle(void* raw, ActorMessage msg, bool resumed, std::int64_t result) noexcept {
    auto& st = *static_cast<State*>(raw);
    const auto tag = static_cast<std::uint64_t>(
        std::hash<std::thread::id>{}(std::this_thread::get_id()));
    std::uint64_t expected = 0;
    (void)st.first_thread.compare_exchange_strong(expected, tag);
    if (st.first_thread.load() != tag) st.violations.fetch_add(1);
    if (!resumed) {
        if (st.io_pending.load(std::memory_order_acquire)) st.violations.fetch_add(1);
        st.initial.fetch_add(1);
        if (st.await_io) {
            st.io_pending.store(true, std::memory_order_release);
            return {StepKind::await_io, msg.input, st.delay_ms};
        }
    } else {
        if (!st.io_pending.exchange(false)) st.violations.fetch_add(1);
        if (result != msg.input * 2) st.violations.fetch_add(1);
        st.resumed.fetch_add(1);
    }
    st.completed.fetch_add(1);
    return {StepKind::done, 0, 0};
}
static std::int64_t io_double(void*, std::int64_t value, const std::atomic<bool>&) noexcept { return value * 2; }
static void test_150ms_await_serial_and_same_carrier() {
    State st; st.await_io = true; st.delay_ms = 150;
    HungryCarrier actor(&st, handle, nullptr, io_double, {});
    actor.start();
    check(actor.producer_handle().try_send({1, 21}), "first message should be accepted");
    check(until([&] { return st.initial.load() == 1; }, 1s),
          "initial handler not executed");
    // Queue another user message while the first turn is waiting for IO.
    check(actor.producer_handle().try_send({2, 22}), "second message should queue");
    std::this_thread::sleep_for(40ms);
    check(st.initial.load() == 1, "user handler reentered during pending IO");
    check(until([&] { return st.completed.load() == 2; }, 2s),
          "two messages did not finish");
    const auto stats = actor.stats();
    actor.request_stop();
    actor.join();
    check(st.resumed.load() == 2, "await continuation did not resume once each");
    check(st.violations.load() == 0, "single-carrier/serial or result violation");
    check(stats.io_completions == 2 && stats.parked > 0, "IO completion or parking absent");
    check(st.first_thread.load() == stats.actor_thread_tag, "wrong carrier thread tag");
    std::cout << "PASS 150ms async/serial dedicated carrier\n";
}
static void test_full_mailbox_does_not_block_stop() {
    State st; st.await_io = true; st.delay_ms = 300;
    HungryCarrier actor(&st, handle, nullptr, io_double, {});
    actor.start();
    check(actor.producer_handle().try_send({1, 4}), "failed initial IO send");
    check(until([&]{return st.initial.load() == 1;}, 1s), "IO did not start");
    int full = 0;
    for (int i = 0; i < 400; ++i)
        if (!actor.producer_handle().try_send({static_cast<std::uint64_t>(i + 2), i})) ++full;
    check(full > 0, "bounded queue did not reject excess work");
    const auto begin = std::chrono::steady_clock::now();
    actor.request_stop(); // reserved control independent of saturated queue
    actor.join();
    check(std::chrono::steady_clock::now() - begin < 250ms,
          "cancellable IO wait did not wake promptly");
    const auto stats = actor.stats();
    check(stats.canceled_inflight >= 1 && stats.protocol_failures == 0,
          "active operation not canceled cleanly");
    check(st.resumed.load() == 0, "canceled operation resumed user handler");
    std::cout << "PASS bounded mailbox + out-of-band stop/cancel\n";
}
static void test_mpsc_producers_serial_actor() {
    State st;
    HungryCarrier actor(&st, handle, nullptr, io_double, {});
    actor.start();
    constexpr int n_producers = 4, per_producer = 800;
    std::vector<std::thread> writers;
    std::atomic<int> rejections{0};
    for (int i = 0; i < n_producers; ++i) writers.emplace_back([&, i] {
        for (int j = 0; j < per_producer; ++j) {
            ActorMessage m{static_cast<std::uint64_t>(i * per_producer + j + 1), j};
            while (!actor.producer_handle().try_send(m)) {
                rejections.fetch_add(1);
                std::this_thread::yield();
            }
        }
    });
    for (auto& w : writers) w.join();
    check(until([&] {return st.completed.load() == n_producers * per_producer;}, 3s),
          "MPSC actor lost user messages");
    const auto stats = actor.stats();
    actor.request_stop();
    actor.join();
    check(stats.accepted == n_producers * per_producer &&
          stats.completed == n_producers * per_producer, "MPSC count mismatch");
    check(st.violations.load() == 0 && stats.protocol_failures == 0,
          "parallel producers caused serial-handler violation");
    std::cout << "PASS four producers, 3200 messages, rejections=" << rejections.load() << "\n";
}
static void test_cpu_affinity_fails_closed() {
    State st;
    HungryCarrier::Options options{};
    options.pin_to_logical_cpu = 1000000;
    HungryCarrier actor(&st, handle, nullptr, io_double, options);
    bool rejected = false;
    try {actor.start();} catch (const std::runtime_error&) {rejected = true;}
    check(rejected, "invalid CPU affinity must reject actor startup");
    actor.request_stop();
    actor.join();
    check(!actor.producer_handle().try_send({1, 1}), "failed admission must not accept messages");
}
static void test_fixed_arena_bounds_and_alignment() {
    FixedArena<128> arena;
    auto* number = arena.emplace<std::uint64_t>(42);
    check(number && *number == 42, "arena POD construction failed");
    check(reinterpret_cast<std::uintptr_t>(number) % alignof(std::uint64_t) == 0,
          "actor arena misaligned storage");
    check(arena.allocate(200, 8) == nullptr, "arena exceeded fixed capacity");
    check(arena.allocate(1, 3) == nullptr, "arena accepted invalid alignment");
    check(arena.used() == sizeof(std::uint64_t),
          "failed allocations changed arena accounting");
}
struct SocketState {int socket = -1;};
static std::int64_t recv_broker(void* context, std::int64_t,
                                const std::atomic<bool>& cancel) noexcept {
    auto& state = *static_cast<SocketState*>(context);
    const auto deadline = std::chrono::steady_clock::now() + 500ms;
    std::int64_t value = 0;
    std::size_t received = 0;
    while (!cancel.load(std::memory_order_acquire) &&
           std::chrono::steady_clock::now() < deadline) {
        pollfd descriptor{state.socket, POLLIN, 0};
        const int rc = ::poll(&descriptor, 1, 10);
        if (rc == 0 || (rc < 0 && errno == EINTR)) continue;
        if (rc < 0 || (descriptor.revents & (POLLNVAL | POLLERR | POLLHUP)))
            return -3;
        if (!(descriptor.revents & POLLIN)) continue;
        const auto size = ::recv(state.socket, reinterpret_cast<char*>(&value) + received,
                                 sizeof(value) - received, MSG_DONTWAIT);
        if (size > 0) {
            received += static_cast<std::size_t>(size);
            if (received == sizeof(value)) return value;
        } else if (size == 0) {
            return -4; // peer closed, no unbounded blocking
        } else if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
            return -5;
        }
    }
    return cancel.load(std::memory_order_acquire) ? -6 : -7;
}
static void test_actual_socket_io_off_carrier() {
    int fds[2]{-1, -1};
    check(::socketpair(AF_UNIX, SOCK_STREAM, 0, fds) == 0, "socketpair failed");
    State st; st.await_io = true;
    SocketState broker{fds[0]};
    HungryCarrier actor(&st, handle, &broker, recv_broker, {});
    actor.start();
    check(actor.producer_handle().try_send({1, 42}), "socket IO enqueue failed");
    check(until([&]{return st.initial.load() == 1;}, 1s),
          "socket request handler did not start");
    std::thread remote([fd=fds[1]] {
        std::this_thread::sleep_for(150ms);
        const std::int64_t result = 84;
        (void)::send(fd, &result, sizeof(result), MSG_NOSIGNAL);
    });
    check(until([&]{return st.completed.load() == 1;}, 2s),
          "socket callback did not return into actor continuation");
    const auto s = actor.stats();
    actor.request_stop();
    actor.join();
    remote.join();
    ::close(fds[0]); ::close(fds[1]);
    check(st.resumed.load() == 1 && st.violations.load() == 0,
          "I/O worker failed to deliver single safe continuation");
    check(s.parked > 0 && s.io_completions == 1,
          "actor carrier did not park during real socket I/O");
    std::cout << "PASS bounded socket broker and parked carrier\n";
}

static void test_strict_realtime_profiles_fail_closed() {
    State st;
    for (int policy = 0; policy < 3; ++policy) {
        HungryCarrier::Options options{};
        options.require_exclusive_core = (policy == 0);
        options.require_strict_no_gc = (policy == 1);
        options.require_hard_realtime = (policy == 2);
        bool rejected = false;
        try {
            HungryCarrier actor(&st, handle, nullptr, io_double, options);
        } catch (const std::invalid_argument&) { rejected = true; }
        check(rejected, "unimplemented strict profile silently admitted");
    }
}
static void test_stop_completion_races() {
    for (int i = 0; i < 60; ++i) {
        State st;
        st.await_io = true;
        st.delay_ms = static_cast<std::uint32_t>(i % 4);
        HungryCarrier actor(&st, handle, nullptr, io_double, {});
        actor.start();
        check(actor.producer_handle().try_send({1, 9}), "race test message failed");
        check(until([&] {return st.initial.load() == 1;}, 1s),
              "race test handler not invoked");
        if ((i & 3) == 1) std::this_thread::yield();
        else if ((i & 3) == 2) std::this_thread::sleep_for(1ms);
        actor.request_stop();
        actor.join();
        const auto stats = actor.stats();
        check(st.resumed.load() <= 1 && st.completed.load() <= 1,
              "completion/cancellation resumed a handler twice");
        check(st.violations.load() == 0 && stats.protocol_failures == 0,
              "completion/cancellation terminal race corrupted actor state");
        check(actor.stopped(), "stopped flag not published");
    }
    std::cout << "PASS 60 cancellation/completion interleavings\n";
}

static void test_pending_socket_cancel_does_not_block_join() {
    int sockets[2]{-1, -1};
    check(::socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0,
          "cancel socketpair failed");
    State state; state.await_io = true;
    SocketState broker{sockets[0]};
    HungryCarrier actor(&state, handle, &broker, recv_broker, {});
    actor.start();
    check(actor.producer_handle().try_send({1, 5}), "cancel test IO request failed");
    check(until([&]{return state.initial.load() == 1;}, 1s),
          "cancel test handler never ran");
    const auto start = std::chrono::steady_clock::now();
    actor.request_stop(); // no remote peer ever writes; broker must observe stop
    actor.join();
    const auto elapsed = std::chrono::steady_clock::now() - start;
    ::close(sockets[0]); ::close(sockets[1]);
    check(elapsed < 250ms, "broker cancellation blocked on an idle socket");
    check(state.resumed.load() == 0 && actor.stats().protocol_failures == 0,
          "canceled socket operation incorrectly resumed user code");
    std::cout << "PASS cooperative socket broker cancel\n";
}


static void test_join_initiates_stop() {
    State state;
    HungryCarrier actor(&state, handle, nullptr, io_double, {});
    actor.start();
    // An idle worker has no natural completion; explicit join must not hang.
    const auto started = std::chrono::steady_clock::now();
    actor.join();
    check(actor.stopped(), "join failed to stop idle actor");
    check(std::chrono::steady_clock::now() - started < 500ms,
          "joining idle actor blocked");
    check(!actor.producer_handle().try_send({1, 1}), "joined actor accepted new work");
    check(actor.stats().abandoned_on_stop == 0,
          "idle actor spuriously recorded abandoned requests");
}
static void test_stop_while_producers_are_publishing() {
    // All producers start before stop, then we race them against join.
    // The caller retains actor lifetime until producer threads are joined;
    // no new calls may be initiated after object destruction begins.
    for (int round = 0; round < 16; ++round) {
        State state;
        HungryCarrier actor(&state, handle, nullptr, io_double, {});
        actor.start();
        std::atomic<bool> begin{false}, stopping{false};
        std::atomic<int> finished{0};
        std::vector<std::thread> senders;
        constexpr int producers = 6;
        for (int p = 0; p < producers; ++p) {
            senders.emplace_back([&, p] {
                while (!begin.load(std::memory_order_acquire))
                    std::this_thread::yield();
                for (int n = 0; n < 4000; ++n) {
                    if (stopping.load(std::memory_order_acquire)) break;
                    (void)actor.producer_handle().try_send({static_cast<std::uint64_t>(p*4000+n+1), n});
                    if ((n & 31) == 0) std::this_thread::yield();
                }
                finished.fetch_add(1, std::memory_order_release);
            });
        }
        begin.store(true, std::memory_order_release);
        std::this_thread::sleep_for(1ms);
        actor.request_stop();
        stopping.store(true, std::memory_order_release);
        // Join races with already-entered send calls. The actor object
        // intentionally remains alive until every producer has exited.
        actor.join();
        for (auto& sender : senders) sender.join();
        const auto stats = actor.stats();
        check(finished.load(std::memory_order_acquire) == producers,
              "one of the producers did not finish");
        check(actor.stopped(), "actor failed to stop during producers race");
        check(stats.completed <= stats.accepted,
              "completed more messages than accepted");
        check(stats.abandoned_on_stop == stats.accepted - stats.completed,
              "shutdown lost accounting of queued/inflight accepted messages");
        check(stats.protocol_failures == 0, "shutdown raced into protocol fault");
        check(!actor.producer_handle().try_send({1, 1}), "stopped actor took another message");
    }
    std::cout << "PASS producer/stop quiescence and abandoned-turn accounting\n";
}


static void test_bounded_queue_head_readiness() {
    // Readiness is about the published next sequence, not producer claims.
    BoundedMpscQueue<ActorMessage, 2> ring;
    check(!ring.has_ready(), "fresh queue should be empty");
    ActorMessage v{};
    check(!ring.try_pop(v), "empty queue returned a value");
    check(ring.try_push({1, 21}) && ring.has_ready(), "published head invisible");
    check(ring.try_push({2, 22}), "second slot could not be published");
    check(!ring.try_push({3, 23}), "bounded queue overflowed");
    check(ring.try_pop(v) && v.id == 1 && v.input == 21, "head FIFO violation");
    check(ring.has_ready(), "second published item should be ready");
    check(ring.try_push({3, 23}), "ring slot failed to recycle");
    check(ring.try_pop(v) && v.id == 2, "second FIFO violation");
    check(ring.try_pop(v) && v.id == 3, "recycled FIFO violation");
    check(!ring.has_ready() && !ring.try_pop(v), "drained queue still reports ready");
    for (std::uint64_t i = 0; i < 100000; ++i) {
        check(ring.try_push({i+4, static_cast<std::int64_t>(i)}),
              "ring slot generation wrap test push failed");
        check(ring.has_ready() && ring.try_pop(v) && v.id == i+4,
              "ring generation after recycling is inconsistent");
    }
}


static void test_producer_handle_survives_destroy() {
    HungryCarrier::ProducerHandle outside;
    State st;
    {
        auto actor = std::make_unique<HungryCarrier>(&st, handle, nullptr, io_double,
                                                     HungryCarrier::Options{});
        outside = actor->producer_handle();
        check(static_cast<bool>(outside), "missing independent producer handle");
        actor->start();
        check(outside.try_send({1, 7}), "valid producer handle rejected work");
        check(until([&]{return st.completed.load() == 1;}, 1s),
              "handle-submitted message not processed");
        actor.reset(); // gate closes before storage is freed
    }
    for (int i = 0; i < 1000; ++i)
        check(!outside.try_send({static_cast<std::uint64_t>(i), i}),
              "closed producer handle accessed destroyed carrier");
}
static void test_producer_handles_concurrent_destructor() {
    for (int round = 0; round < 12; ++round) {
        State st;
        auto actor = std::make_unique<HungryCarrier>(&st, handle, nullptr, io_double,
                                                     HungryCarrier::Options{});
        actor->start();
        const auto admission = actor->producer_handle();
        std::atomic<bool> go{false};
        std::atomic<int> done{0}, accepted{0};
        std::vector<std::thread> producers;
        for (int p = 0; p < 5; ++p) {
            auto copy = admission; // each thread owns an independent shared handle
            producers.emplace_back([copy, &go, &done, &accepted, p] {
                while (!go.load(std::memory_order_acquire))
                    std::this_thread::yield();
                for (int i = 0; i < 3000; ++i) {
                    if (copy.try_send({static_cast<std::uint64_t>(p * 3000 + i + 1), i}))
                        accepted.fetch_add(1, std::memory_order_relaxed);
                    if ((i & 15) == 0) std::this_thread::yield();
                }
                // This checks the control block AFTER the actor is gone.
                done.fetch_add(1, std::memory_order_release);
            });
        }
        go.store(true, std::memory_order_release);
        std::this_thread::sleep_for(1ms);
        actor.reset(); // producer threads can keep calling their safe handles
        for (auto& producer : producers) producer.join();
        check(done.load(std::memory_order_acquire) == 5, "producer did not exit");
        check(!admission.try_send({1, 1}), "expired actor accepted message");
        check(st.violations.load(std::memory_order_acquire) == 0,
              "actor-local state accessed concurrently during teardown");
    }
    std::cout << "PASS independent producer handles across destruction\n";
}


static void test_spsc_stalled_publisher_does_not_block_another_lane() {
    BoundedSpscQueue<ActorMessage, 2> slow, fast;
    std::atomic<bool> entered{false}, release{false}, slow_ok{false};
    std::thread interrupted([&] {
        const bool success = slow.try_push_instrumented({1, 11}, [&]() noexcept {
            entered.store(true, std::memory_order_release);
            while (!release.load(std::memory_order_acquire))
                std::this_thread::yield();
        });
        slow_ok.store(success, std::memory_order_release);
    });
    const bool reached = until([&] {return entered.load(std::memory_order_acquire);}, 2s);
    const bool slow_unpublished = !slow.has_ready();
    const bool fast_published = fast.try_push({2, 22});
    ActorMessage message{};
    const bool fast_consumed = fast.try_pop(message);
    const bool correct_second = message.id == 2 && message.input == 22;
    release.store(true, std::memory_order_release);
    interrupted.join();
    check(reached, "slow producer did not reach prepublish barrier");
    check(slow_unpublished, "unpublished SPSC item was consumed");
    check(fast_published && fast_consumed && correct_second,
          "stalled producer on lane A blocked independent lane B");
    check(slow_ok.load(std::memory_order_acquire) && slow.try_pop(message) &&
          message.id == 1, "lane A did not resume correctly");
}
static void test_dedicated_lane_registration_and_fairness() {
    State st;
    HungryCarrier actor(&st, handle, nullptr, io_double, {});
    auto lane_a = actor.register_spsc_producer();
    auto lane_b = actor.register_spsc_producer();
    actor.start();
    bool rejected_late = false;
    try {
        auto late = actor.register_spsc_producer();
        (void)late;
    } catch (const std::logic_error&) { rejected_late = true; }
    check(rejected_late, "SPSC registration after actor start was accepted");

    std::atomic<bool> ok_a{true}, ok_b{true};
    std::thread producer_a([lane=std::move(lane_a), &ok_a]() mutable {
        std::this_thread::sleep_for(35ms); // a deliberately delayed producer
        for (int i = 0; i < 300; ++i) {
            while (!lane.try_send({static_cast<std::uint64_t>(i+1), i})) {
                if (i > 0 && !ok_a.load()) return;
                std::this_thread::yield();
            }
        }
    });
    std::thread producer_b([lane=std::move(lane_b), &ok_b]() mutable {
        for (int i = 0; i < 300; ++i) {
            while (!lane.try_send({static_cast<std::uint64_t>(i+1001), i})) {
                if (i > 0 && !ok_b.load()) return;
                std::this_thread::yield();
            }
        }
    });
    const bool early_progress = until([&] {return st.completed.load() >= 100;}, 500ms);
    producer_a.join();
    producer_b.join();
    const bool finished = until([&] {return st.completed.load() == 600;}, 3s);
    const auto stats = actor.stats();
    actor.join();
    check(early_progress, "dedicated lane B did not progress while A delayed");
    check(finished && stats.completed == 600 && stats.accepted == 600,
          "SPSC ingress lost or duplicated messages");
    check(st.violations.load() == 0 && stats.protocol_failures == 0,
          "dedicated lanes violated single actor handler invariant");
    std::cout << "PASS independent SPSC lanes and fair carrier polling\n";
}
static void test_spsc_lifetime_and_registration_limits() {
    State st;
    std::optional<HungryCarrier::DedicatedProducer> survived;
    {
        auto actor = std::make_unique<HungryCarrier>(&st, handle, nullptr, io_double,
                                                     HungryCarrier::Options{});
        survived.emplace(actor->register_spsc_producer());
        auto b = actor->register_spsc_producer();
        auto c = actor->register_spsc_producer();
        auto d = actor->register_spsc_producer();
        bool full = false;
        try { auto e = actor->register_spsc_producer(); (void)e; }
        catch (const std::length_error&) { full = true; }
        check(full, "more than four SPSC lanes were admitted");
        actor->start();
        check(survived->try_send({1, 1}), "valid dedicated lane rejected request");
        check(until([&] {return st.completed.load() == 1;}, 1s),
              "dedicated lane message failed to execute");
        actor.reset();
    }
    check(!survived->try_send({2, 2}),
          "dedicated producer dereferenced destroyed actor");
}

int main() {
    try {
        test_bounded_queue_head_readiness();
        test_fixed_arena_bounds_and_alignment();
        test_150ms_await_serial_and_same_carrier();
        test_actual_socket_io_off_carrier();
        test_pending_socket_cancel_does_not_block_join();
        test_full_mailbox_does_not_block_stop();
        for (int i = 0; i < 4; ++i) test_mpsc_producers_serial_actor();
        test_cpu_affinity_fails_closed();
        test_strict_realtime_profiles_fail_closed();
        test_stop_completion_races();
        test_join_initiates_stop();
        test_stop_while_producers_are_publishing();
        test_producer_handle_survives_destroy();
        test_producer_handles_concurrent_destructor();
        test_spsc_stalled_publisher_does_not_block_another_lane();
        test_dedicated_lane_registration_and_fairness();
        test_spsc_lifetime_and_registration_limits();
        std::cout << "PASS hungry carrier end-to-end tests\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL hungry carrier: " << error.what() << "\n";
        return EXIT_FAILURE;
    }
}
