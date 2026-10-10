#include "oreslang/runtime/FixedArena.hpp"
#include <cstdio>
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

// Must remain active with CMAKE_BUILD_TYPE=Release (-DNDEBUG).
// Standard assert() would remove these checks and can hide arena defects.
#define CHECK(condition) do { \
    if (!(condition)) { \
        std::fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #condition); \
        return 1; \
    } \
} while (false)

int main() {
    FixedArena<64> region;
    CHECK(region.used() == 0);
    CHECK(region.allocate(0, 1) == nullptr);
    CHECK(region.allocate(1, 0) == nullptr);
    CHECK(region.allocate(1, 3) == nullptr);
    CHECK(region.allocate(1, alignof(std::max_align_t) * 2) == nullptr);
    CHECK(region.used() == 0);

    auto* first = region.emplace<PlainValue>(42);
    CHECK(first != nullptr && first->value == 42);
    CHECK(reinterpret_cast<std::uintptr_t>(first) % alignof(PlainValue) == 0);
    const auto beforeFail = region.used();
    CHECK(region.allocate(65, 1) == nullptr);
    CHECK(region.allocate(std::numeric_limits<std::size_t>::max(), 1) == nullptr);
    CHECK(region.used() == beforeFail);
    auto* second = region.emplace<PlainValue>(99);
    CHECK(second != nullptr && second != first && second->value == 99);
    CHECK(first->value == 42);

    // An exhausted region cannot wrap or silently reset while pointers exist.
    while (region.allocate(1, 1)) {}
    CHECK(region.used() == 64);
    CHECK(region.allocate(1, 1) == nullptr);
    CHECK(region.allocate(0, 1) == nullptr);
    CHECK(first->value == 42);
    CHECK(second->value == 99);
    return 0;
}
