#include "oreslang/runtime/DeterministicGraphArena.hpp"
#include <cstdio>
#include <type_traits>
struct DropTracked {
  int value;
  int* drops;
  DropTracked(int v, int* d) noexcept : value(v), drops(d) {}
  DropTracked(const DropTracked&) = delete;
  DropTracked& operator=(const DropTracked&) = delete;
  ~DropTracked() noexcept { ++*drops; }
};
using Graph = oreslang::runtime::DeterministicGraphArena<DropTracked, 3, 2>;
#define VERIFY(expr) do { if (!(expr)) { std::fprintf(stderr, "FAIL %d\n", __LINE__); return 1; } } while (false)
static_assert(!std::is_copy_constructible_v<Graph>, "unique arena owner");
static_assert(!std::is_move_constructible_v<Graph>, "stable arena");
int main() {
  int drops = 0;
  Graph graph;
  auto a = graph.emplace(1, &drops);
  auto b = graph.emplace(2, &drops);
  VERIFY(a.has_value() && b.has_value());
  VERIFY(graph.connect(*a, *a));
  VERIFY(graph.connect(*a, *b));
  VERIFY(graph.connect(*b, *a));
  auto loan = graph.borrow(*a);
  VERIFY(loan.has_value() && loan->get().value == 1);
  VERIFY(!graph.erase(*a));
  VERIFY(!graph.close());
  loan.reset();
  VERIFY(graph.erase(*a));
  VERIFY(drops == 1);
  VERIFY(!graph.borrow(*a).has_value());
  VERIFY(graph.live_edges(*b) == 0);
  auto replacement = graph.emplace(3, &drops);
  VERIFY(replacement.has_value());
  VERIFY(replacement->slot == a->slot && replacement->generation != a->generation);
  VERIFY(!graph.connect(*b, *a));
  VERIFY(graph.connect(*b, *replacement));
  Graph other;
  auto foreign = other.emplace(4, &drops);
  VERIFY(foreign.has_value());
  VERIFY(!graph.connect(*b, *foreign));
  VERIFY(!graph.borrow(*foreign).has_value());
  VERIFY(graph.close() && graph.close());
  VERIFY(!graph.borrow(*b).has_value());
  VERIFY(drops == 3);
  VERIFY(other.close());
  VERIFY(drops == 4);
  return 0;
}
