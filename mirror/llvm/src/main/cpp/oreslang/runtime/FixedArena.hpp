#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <type_traits>
#include <utility>

namespace oreslang::runtime {

// Fixed storage owned by a SINGLE actor carrier; no concurrent access.
// No heap allocation and no tracing GC. Allocate once; reclaim only at an
// explicit quiescent epoch when all derived references are proved dead.
// This is a minimal POD arena, NOT a general-purpose Oreslang object heap,
// cycle collector, ownership checker or a hard-realtime allocation guarantee.
template<std::size_t Capacity>
class FixedArena final {
    static_assert(Capacity > 0, "arena must have positive capacity");
    alignas(std::max_align_t) std::array<std::byte, Capacity> storage_{};
    std::size_t used_ = 0;
public:
    FixedArena() = default;
    FixedArena(const FixedArena&) = delete;
    FixedArena& operator=(const FixedArena&) = delete;
    static constexpr std::size_t capacity = Capacity;
    std::size_t used() const noexcept { return used_; }

    void* allocate(std::size_t count, std::size_t align) noexcept {
        // A zero-byte result would alias the next live allocation without
        // acquiring any storage or a unique lifetime. Reject it outright.
        if (count == 0 || !align || (align & (align - 1)) || align > alignof(std::max_align_t))
            return nullptr;
        const auto address = reinterpret_cast<std::uintptr_t>(storage_.data());
        if (count > Capacity || used_ > Capacity) return nullptr;
        const auto current = address + used_;
        if (current < address) return nullptr;
        const auto mask = static_cast<std::uintptr_t>(align - 1);
        if (current > std::numeric_limits<std::uintptr_t>::max() - mask) return nullptr;
        const auto aligned = (current + mask) & ~mask;
        const auto padding = static_cast<std::size_t>(aligned - current);
        if (padding > Capacity - used_ || count > Capacity - used_ - padding)
            return nullptr;
        used_ += padding + count;
        return reinterpret_cast<void*>(aligned);
    }
    // Only trivial lifetime types permitted until deterministic destructor
    // ownership and cancellation cleanup are implemented and verified.
    template<class T, class... Args>
    T* emplace(Args&&... args) noexcept {
        static_assert(std::is_trivially_destructible<T>::value,
                      "nontrivial drop requires verified actor-local cleanup");
        static_assert(alignof(T) <= alignof(std::max_align_t),
                      "overaligned types require a separate allocator");
        static_assert(std::is_nothrow_constructible<T, Args...>::value,
                      "strict arena construction must not throw");
        void* ptr = allocate(sizeof(T), alignof(T));
        if (!ptr) return nullptr;
        return ::new (ptr) T(std::forward<Args>(args)...);
    }
    // Do not add reset()/rewind() until the compiler/lifetime verifier proves
    // that no continuation or in-flight I/O owns a reference to old storage.
};
} // namespace oreslang::runtime
