# Oreslang AOT / GraalVM Native Image contract

Oreslang supports three deliberately different deployment modes.

## JVM JIT

The ordinary JVM launcher runs the Oreslang Truffle language on GraalVM with
the optimizing Truffle runtime available. The host Java implementation and
guest code may both be optimized at runtime.

## Native AOT

The Maven `native-aot` profile builds `target/ores-aot` with GraalVM Native
Image.

This profile is intentionally interpreter-only for guest Oreslang code:

- the Java/Truffle implementation of Oreslang is ahead-of-time compiled into
  the native executable;
- `-Dtruffle.UseFallbackRuntime=true` disables runtime guest-code compilation;
- no fallback JVM is permitted or required by current Native Image semantics;
- the executable must run without a JDK/GraalVM installation at runtime.

This is the strict closed-world/AOT deployment target.

## Native hybrid

The `native-hybrid` profile also AOT-compiles the Java host into a native
executable, but retains the optimizing Truffle runtime so hot guest code may be
compiled at runtime. It is not the same contract as pure AOT.

## Closed-world rules

Code reachable from the native AOT profile must not rely on undeclared dynamic
JVM behavior.

In particular:

- runtime reflection/resources/JNI/serialization must be statically discoverable
  or covered by Native Image reachability metadata;
- CI builds with `--exact-reachability-metadata=dev.oreslang`;
- CI runs the native executable with
  `-XX:MissingRegistrationReportingMode=Exit` so swallowed missing-registration
  errors still fail validation;
- OresVM and its lazy process holder are explicitly initialized at image runtime,
  never snapshotted during image generation;
- guest/actor code never receives JNI/FFI/native/reflection/thread/polyglot
  authority merely because it is co-resident with the native runtime;
- Truffle language resources must remain available to the native executable.

## Required CI proof

A change is AOT-safe only when the exact source tree can:

1. pass the JVM test suite;
2. build `target/ores-aot` with GraalVM Native Image;
3. execute ordinary Oreslang code from that binary;
4. execute shared-actor and isoactor spawn/await paths from that binary;
5. do all of the above with missing-registration reporting set to `Exit`.

The no-secret cross-org validation harness mirrors the exact private source tree
into a test repository and performs this proof using only that repository's own
GitHub Actions token/minutes.
