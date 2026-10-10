# Future LLVM Oreslang semantic analysis

Java authorities: `TypeChecker` and `OwnershipChecker`.
Do not lower arbitrary guest expressions until they pass equivalent source-level
lifetime, move, borrow, capture and actor-boundary checks. Pinned-frame layout and
in-place destructor/drop correctness must follow the compiled ownership proof.
See [issue #1](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/issues/1).
