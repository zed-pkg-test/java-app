#include "oreslang/runtime/FixedArena.hpp"
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <type_traits>

using oreslang::runtime::FixedArena;

struct PlainValue {
    std::uint64_t value;
    explicit PlainValue(std::uint64_t initial) noexcept : value(initial) {}
};
static_assert(std::is_trivially_destructible<PlainValue>::value,
              "native POD arena does not own runtime destructors");
static_assert(!std::is_copy_constructible<FixedArena<64>>::value,
              "copying arena storage would invalidate every derived address");

int main() {
    FixedArena<64> region;
    assert(region.used() == 0);
    assert(region.allocate(0, 1) == nullptr);
    assert(region.allocate(1, 0) == nullptr);
    assert(region.allocate(1, 3) == nullptr);
    assert(region.allocate(1, alignof(std::max_align_t) * 2) == nullptr);
    assert(region.used() == 0);

    auto* first = region.emplace<PlainValue>(42);
    assert(first != nullptr && first->value == 42);
    assert(reinterpret_cast<std::uintptr_t>(first) % alignof(PlainValue) == 0);
    const auto beforeFail = region.used();
    assert(region.allocate(65, 1) == nullptr);
    assert(region.allocate(std::numeric_limits<std::size_t>::max(), 1) == nullptr);
    assert(region.used() == beforeFail);
    auto* second = region.emplace<PlainValue>(99);
    assert(second != nullptr && second != first && second->value == 99);
    assert(first->value == 42);

    // An exhausted region cannot wrap or silently reset while pointers exist.
    while (region.allocate(1, 1)) {}
    assert(region.used() == 64);
    assert(region.allocate(1, 1) == nullptr);
    assert(region.allocate(0, 1) == nullptr);
    assert(first->value == 42);
    assert(second->value == 99);
    return 0;
}
