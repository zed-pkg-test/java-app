#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <type_traits>

namespace oreslang::runtime {

// Preallocated, bounded, multi-producer/single-consumer ring.
// Based on per-slot generation sequences; no mutex, heap or syscalls on push/pop.
// Correctness requires: N power of two; one consumer; queue lives longer than
// ALL producers; operation counters must never reach integer wrap-around.
// Full => explicit false. The queue NEVER silently overwrites an event.
// Atomic operations are not universally hardware lock-free (verify at admission).
template <typename T, std::size_t N>
class BoundedMpscQueue final {
    static_assert(N >= 2 && (N & (N - 1)) == 0, "capacity must be power of two");
    static_assert(sizeof(std::size_t) == 8, "64-bit atomic indexes required");
    static_assert(std::is_trivially_copyable<T>::value, "messages must be POD");
    struct Slot {
        std::atomic<std::size_t> sequence{0};
        T value{};
    };
    alignas(64) std::array<Slot, N> slots_{};
    alignas(64) std::atomic<std::size_t> write_{0};
    alignas(64) std::size_t read_ = 0;  // only on owner/carrier thread
public:
    BoundedMpscQueue() {
        for (std::size_t i = 0; i < N; ++i)
            slots_[i].sequence.store(i, std::memory_order_relaxed);
    }
    BoundedMpscQueue(const BoundedMpscQueue&) = delete;
    BoundedMpscQueue& operator=(const BoundedMpscQueue&) = delete;

    bool try_push(const T& value) noexcept {
        auto pos = write_.load(std::memory_order_relaxed);
        for (;;) {
            auto& slot = slots_[pos & (N - 1)];
            const auto seq = slot.sequence.load(std::memory_order_acquire);
            const auto diff = static_cast<std::int64_t>(seq - pos);
            if (diff == 0) {
                if (write_.compare_exchange_weak(pos, pos + 1,
                                                 std::memory_order_relaxed,
                                                 std::memory_order_relaxed)) {
                    slot.value = value;
                    slot.sequence.store(pos + 1, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) {
                return false;
            } else {
                pos = write_.load(std::memory_order_relaxed);
            }
        }
    }
    // Consumer-only readiness. Unlike a count of *all* published slots,
    // checking the head sequence cannot report ready when an earlier
    // producer reserved a slot but was preempted before publishing it.
    // Call only from this queue's single consumer thread.
    bool has_ready() const noexcept {
        return slots_[read_ & (N - 1)].sequence.load(std::memory_order_acquire)
               == read_ + 1;
    }
    bool try_pop(T& value) noexcept {
        auto& slot = slots_[read_ & (N - 1)];
        if (slot.sequence.load(std::memory_order_acquire) != read_ + 1)
            return false;
        value = slot.value;
        slot.sequence.store(read_ + N, std::memory_order_release);
        ++read_;
        return true;
    }
    static constexpr std::size_t capacity = N;
};
} // namespace oreslang::runtime
