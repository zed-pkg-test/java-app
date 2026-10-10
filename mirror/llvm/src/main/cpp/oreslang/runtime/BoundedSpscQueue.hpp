#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>
#include <utility>

namespace oreslang::runtime {

// Fixed-size SPSC ring. One producer thread, one consumer thread for life.
// Head and tail are written independently; an interrupted producer cannot
// block consumption from another producer's *separate* SPSC lane.
// Exactly one release-store publishes an item. No dynamic allocation,
// mutex or syscall in try_push/try_pop. Does NOT claim bounded OS scheduling.
template <typename T, std::size_t N>
class BoundedSpscQueue final {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "SPSC capacity power of two");
    static_assert(sizeof(std::uint64_t) == 8, "64-bit counters required");
    static_assert(std::is_trivially_copyable<T>::value,
                  "bounded messages must be trivially copyable");
    static_assert(std::atomic<std::uint64_t>::is_always_lock_free,
                  "SPSC indexes must use lock-free hardware atomics");

    alignas(64) std::array<T, N> slots_{};
    alignas(64) std::atomic<std::uint64_t> head_{0};
    alignas(64) std::atomic<std::uint64_t> tail_{0};

public:
    BoundedSpscQueue() = default;
    BoundedSpscQueue(const BoundedSpscQueue&) = delete;
    BoundedSpscQueue& operator=(const BoundedSpscQueue&) = delete;

    bool try_push(const T& item) noexcept {
        return try_push_instrumented(item, []() noexcept {});
    }

    // Deterministic race-testing hook. The hook is invoked after the producer
    // writes its *private* slot but before release-publishing the tail.
    // The hook may yield to make a stalled-publisher scenario reproducible.
    // Production calls use the zero-cost empty hook above.
    template <class BeforePublish>
    bool try_push_instrumented(const T& item, BeforePublish&& before_publish)
        noexcept(noexcept(std::declval<BeforePublish&>()())) {
        const auto tail = tail_.load(std::memory_order_relaxed);
        const auto head = head_.load(std::memory_order_acquire);
        if (tail - head >= N) return false;
        slots_[tail & (N - 1)] = item;
        std::forward<BeforePublish>(before_publish)();
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    // Consumer-only readiness, no producer writes to head_.
    bool has_ready() const noexcept {
        return tail_.load(std::memory_order_acquire) !=
               head_.load(std::memory_order_relaxed);
    }

    bool try_pop(T& item) noexcept {
        const auto head = head_.load(std::memory_order_relaxed);
        if (tail_.load(std::memory_order_acquire) == head) return false;
        item = slots_[head & (N - 1)];
        head_.store(head + 1, std::memory_order_release);
        return true;
    }
    static constexpr std::size_t capacity = N;
};
} // namespace oreslang::runtime
