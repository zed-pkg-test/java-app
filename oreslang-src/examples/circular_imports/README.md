# Circular imports

Oreslang intentionally permits import cycles. Run:

```bash
mvn -q -DskipTests exec:java -Dexec.args="examples/circular_imports/a.ores"
```

`a.ores` imports declarations from `b.ores`, while `b.ores` imports
`a_value` from `a.ores`.

The host loader parses, validates, and links the complete reachable graph
without executing Oreslang user code. Once the graph is linked, only the entry
unit's `main` runs automatically. Any startup sequence is therefore explicit
application code; this example calls `startup_a()` and `startup_b()` from
`main`.

Expected output:

```text
startup-a:B|startup-b:A|main:AB
```

This keeps circular dependency linking deterministic without introducing
import-time side effects or hidden initialization order.
