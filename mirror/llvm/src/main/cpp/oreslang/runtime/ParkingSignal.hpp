#pragma once

// Linux-only, actor-neutral notification primitive. Not an actor or mailbox.
// Exactly one waiter, zero or more concurrent notifiers.
// The caller must publish its ready/stop predicate BEFORE notify().
// Destruction requires that the waiter and every notifier have stopped.

#include <atomic>
#include <chrono>
#include <cstdint>
#include <system_error>
#include <stdexcept>
#include <thread>

namespace oreslang::runtime {

enum class WaitResult { ready, stopped, timed_out };

class ParkingSignal final {
public:
    ParkingSignal();
    ~ParkingSignal() noexcept;

    ParkingSignal(const ParkingSignal&) = delete;
    ParkingSignal& operator=(const ParkingSignal&) = delete;
    ParkingSignal(ParkingSignal&&) = delete;
    ParkingSignal& operator=(ParkingSignal&&) = delete;

    // Notification carries no payload or semantic completion identity.
    // Publish a queue entry (or set an atomic predicate with release) first.
    // A nonblocking eventfd overflow is safe: the fd is already readable.
    void notify();

    // ready() must be safe to invoke on the waiter with producer(s) active.
    // Its reads must acquire the published state. Shutdown wins over ready.
    // The thread parks via poll(2) rather than busy-spinning.
    // Deadline is monotonic; milliseconds::max means wait indefinitely.
    template <class Ready>
    WaitResult wait_until(Ready&& ready, const std::atomic<bool>& stop,
                          std::chrono::milliseconds timeout) {
        // An eventfd wakeup belongs to exactly one dedicated carrier. Two
        // waiters could steal each other's signals. Fail closed even if the
        // second waiter arrives after the first has already parked/woken.
        if (claimed_.test_and_set(std::memory_order_acquire))
            throw std::logic_error("multiple concurrent parking waiters");
        struct ClaimGuard {
            std::atomic_flag& flag;
            ~ClaimGuard() { flag.clear(std::memory_order_release); }
        } guard{claimed_};
        if (!owner_set_) {
            owner_ = std::this_thread::get_id();
            owner_set_ = true;
        } else if (owner_ != std::this_thread::get_id()) {
            throw std::logic_error("carrier thread identity changed");
        }
        using clock = std::chrono::steady_clock;
        const bool infinite = timeout == std::chrono::milliseconds::max();
        const auto deadline = infinite ? clock::time_point::max() : clock::now() + timeout;
        for (;;) {
            if (stop.load(std::memory_order_acquire)) return WaitResult::stopped;
            if (ready()) return WaitResult::ready;
            if (!infinite && clock::now() >= deadline) return WaitResult::timed_out;

            // Eventfd stays readable across the check -> poll race.
            // Consume the token(s) and *always* recheck both predicates.
            wait_for_signal(infinite ? -1 : remaining_ms(deadline));
        }
    }

private:
    int fd_ = -1;
    std::atomic_flag claimed_ = ATOMIC_FLAG_INIT;
    // Protected by claimed_ acquire/release; thread migration prohibited.
    std::thread::id owner_;
    bool owner_set_ = false;
    void wait_for_signal(int timeout_ms);
    static int remaining_ms(std::chrono::steady_clock::time_point deadline);
};

}  // namespace oreslang::runtime
