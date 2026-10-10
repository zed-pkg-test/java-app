#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <optional>
#include <type_traits>
#include <utility>

namespace oreslang::runtime {

// An actor-confined native ownership domain. The arena is the ONLY strong
// owner of T. Graph links are non-owning (domain, slot, generation) identities:
// cycles cannot form reference-count ownership cycles.
//
// All calls must be serialized on the owning actor's execution lane. A lease
// blocks deletion/retirement even across a carrier migration; the runtime must
// ensure all suspended continuations and FFI leases have ended before teardown.
//
// This is an allocation-bounded C++ substrate, NOT source-to-native Oreslang
// lowering, an LLVM borrow checker, a cross-actor capability or proof that an
// arbitrary T never allocates in its constructor/destructor.
template<class T, std::size_t Capacity, std::size_t MaxEdges>
class DeterministicGraphArena final {
    static_assert(Capacity > 0 && MaxEdges > 0, "finite positive arena bounds required");
    static_assert(std::is_nothrow_destructible<T>::value,
                  "unwinding a native ownership domain must not throw");
    static_assert(Capacity <= std::numeric_limits<std::uint32_t>::max(),
                  "slot identities must fit a 32-bit index");

public:
    struct Handle {
        std::uint64_t domain = 0;
        std::uint32_t slot = 0;
        std::uint64_t generation = 0;
        bool operator==(const Handle& b) const noexcept {
            return domain == b.domain && slot == b.slot && generation == b.generation;
        }
        bool operator!=(const Handle& b) const noexcept { return !(*this == b); }
    };

private:
    struct Slot {
        std::optional<T> value;
        std::array<Handle, MaxEdges> edges{};
        std::size_t degree = 0;
        std::uint64_t generation = 1;
    };
    inline static std::atomic<std::uint64_t> next_domain_{1};
    std::array<Slot, Capacity> slots_{};
    const std::uint64_t domain_;
    std::size_t size_ = 0;
    std::size_t leases_ = 0;
    bool closed_ = false;
    bool retiring_ = false;

    static std::uint64_t unique_domain() noexcept {
        const auto id = next_domain_.fetch_add(1, std::memory_order_relaxed);
        if (id == 0 || id == std::numeric_limits<std::uint64_t>::max())
            std::terminate(); // never allow reused arena identities
        return id;
    }

    bool live(Handle h) const noexcept {
        return !closed_ && !retiring_ && h.domain == domain_
               && h.slot < Capacity && slots_[h.slot].value.has_value()
               && slots_[h.slot].generation == h.generation;
    }
    void prune_edges(Slot& slot) noexcept {
        std::size_t at = 0;
        while (at < slot.degree) {
            if (live(slot.edges[at])) { ++at; continue; }
            slot.edges[at] = slot.edges[--slot.degree];
            slot.edges[slot.degree] = Handle{};
        }
    }

public:
    class Lease final {
        DeterministicGraphArena* owner_ = nullptr;
        T* value_ = nullptr;
        Lease(DeterministicGraphArena& owner, T& value) noexcept
            : owner_(&owner), value_(&value) { ++owner.leases_; }
        friend class DeterministicGraphArena;
    public:
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept
            : owner_(std::exchange(other.owner_, nullptr)),
              value_(std::exchange(other.value_, nullptr)) {}
        Lease& operator=(Lease&&) = delete;
        ~Lease() noexcept { if (owner_) --owner_->leases_; }
        T& get() const noexcept { return *value_; }
        T* operator->() const noexcept { return value_; }
        T& operator*() const noexcept { return *value_; }
    };

    DeterministicGraphArena() noexcept : domain_(unique_domain()) {}
    DeterministicGraphArena(const DeterministicGraphArena&) = delete;
    DeterministicGraphArena& operator=(const DeterministicGraphArena&) = delete;
    DeterministicGraphArena(DeterministicGraphArena&&) = delete;
    DeterministicGraphArena& operator=(DeterministicGraphArena&&) = delete;
    ~DeterministicGraphArena() noexcept {
        // Destruction with a live async/FFI lease would otherwise be a UAF.
        // Fail closed rather than silently violating memory safety.
        if (!close()) std::terminate();
    }

    std::size_t size() const noexcept { return size_; }
    std::size_t active_leases() const noexcept { return leases_; }
    bool closed() const noexcept { return closed_; }

    template<class... Args>
    std::optional<Handle> emplace(Args&&... args) {
        if (closed_ || retiring_ || size_ == Capacity) return std::nullopt;
        for (std::size_t i = 0; i < Capacity; ++i) {
            auto& slot = slots_[i];
            if (slot.value || slot.generation == std::numeric_limits<std::uint64_t>::max())
                continue;
            // Construct before publishing the slot. A throwing constructor
            // cannot expose a partially initialized or double-owned value.
            slot.value.emplace(std::forward<Args>(args)...);
            slot.degree = 0;
            ++size_;
            return Handle{domain_, static_cast<std::uint32_t>(i), slot.generation};
        }
        return std::nullopt;
    }

    std::optional<Lease> borrow(Handle h) noexcept {
        if (!live(h)) return std::nullopt;
        return std::optional<Lease>(Lease(*this, *slots_[h.slot].value));
    }

    bool connect(Handle source, Handle target) noexcept {
        if (!live(source) || !live(target)) return false;
        auto& slot = slots_[source.slot];
        for (std::size_t i = 0; i < slot.degree; ++i)
            if (slot.edges[i] == target) return true;
        prune_edges(slot);
        if (slot.degree == MaxEdges) return false;
        slot.edges[slot.degree++] = target;
        return true;
    }

    std::size_t live_edges(Handle h) noexcept {
        if (!live(h)) return 0;
        auto& slot = slots_[h.slot];
        prune_edges(slot);
        return slot.degree;
    }

    std::optional<Handle> edge_at(Handle h, std::size_t index) noexcept {
        if (!live(h)) return std::nullopt;
        auto& slot = slots_[h.slot];
        prune_edges(slot);
        if (index >= slot.degree) return std::nullopt;
        return slot.edges[index];
    }

    bool erase(Handle h) noexcept {
        if (!live(h) || leases_ != 0) return false;
        auto& slot = slots_[h.slot];
        if (slot.generation == std::numeric_limits<std::uint64_t>::max())
            return false;
        retiring_ = true;
        ++slot.generation; // invalidate every external/linked handle first
        slot.degree = 0;
        slot.value.reset(); // deterministic T destructor, no graph recursion
        --size_;
        retiring_ = false;
        return true;
    }

    // An actor supervisor may retire this domain only after every active
    // continuation, I/O/FFI lease and actor turn has quiesced. The internal
    // lease count enforces one necessary condition, not the full scheduler proof.
    bool close() noexcept {
        if (closed_) return true;
        if (retiring_ || leases_ != 0) return false;
        retiring_ = true;
        // Reverse slot order gives deterministic teardown independent of
        // cycles in the non-owning adjacency graph.
        for (std::size_t i = Capacity; i != 0; --i) {
            auto& slot = slots_[i - 1];
            slot.degree = 0;
            slot.value.reset();
        }
        size_ = 0;
        closed_ = true;
        retiring_ = false;
        return true;
    }
};

} // namespace oreslang::runtime
