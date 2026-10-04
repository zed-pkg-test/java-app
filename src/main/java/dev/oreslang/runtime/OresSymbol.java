package dev.oreslang.runtime;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Immutable Symbol identity.
 *
 * <p>Every Oreslang Symbol is a globally comparable immutable identity across
 * every memory domain in one OS process. Physical wrapper objects may differ
 * across isolated heaps, but identity never depends on an object pointer.</p>
 *
 * <ul>
 *   <li>PROCESS: process-lifetime identity keyed by canonical text.</li>
 *   <li>STABLE: UUID identity whose equality/wire identity also survives process restart.</li>
 * </ul>
 *
 * <p>Untrusted/adversarial code may receive, compare, hash, pattern-match, and
 * decode already-admitted Symbols. It may not create new process-global
 * registry entries from arbitrary text. That split keeps protocol identities
 * universal without exposing an atom-table exhaustion capability.</p>
 */
public final class OresSymbol {
    public enum Scope { PROCESS, STABLE }

    public static final int MAX_KEY_LENGTH = 256;
    public static final int MAX_PROCESS_SYMBOLS = 65_536;
    /**
     * Dynamic Symbol.process(...) calls may not consume the whole process table.
     * The remainder is reserved for compiler-known :literal protocol tags so
     * trusted hot-loaded code cannot be starved by earlier dynamic interning.
     */
    public static final int MAX_DYNAMIC_PROCESS_SYMBOLS = 57_344;
    public static final int RESERVED_LITERAL_PROCESS_SYMBOLS =
            MAX_PROCESS_SYMBOLS - MAX_DYNAMIC_PROCESS_SYMBOLS;
    public static final int MAX_STABLE_SYMBOLS = 65_536;
    public static final int MAX_LITERAL_NAME_LENGTH = 128;

    /** Backward-compatible name for the process-table bound. */
    public static final int MAX_LIVE_SYMBOLS = MAX_PROCESS_SYMBOLS;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Object PROCESS_INSERT_LOCK = new Object();
    private static final Object STABLE_INSERT_LOCK = new Object();
    private static final UUID PROCESS_DOMAIN = UUID.randomUUID();
    private static final ConcurrentHashMap<String, OresSymbol> PROCESS_SYMBOLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, OresSymbol> STABLE_SYMBOLS = new ConcurrentHashMap<>();
    /**
     * Explicit protocol allowlist for the untrusted boundary. Identity remains
     * process-global; visibility is a separate capability decision.
     */
    private static final Set<Wire> UNTRUSTED_EXPORTS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong NEXT_PROCESS_ID = new AtomicLong(1L);
    private static final AtomicLong NEXT_STABLE_ID = new AtomicLong(1L);
    private static int dynamicProcessSymbols;

    private final Scope scope;
    private final String key;
    private final long id;
    private final UUID domain;

    private OresSymbol(Scope scope, String key, long id, UUID domain) {
        this.scope = Objects.requireNonNull(scope);
        this.key = Objects.requireNonNull(key);
        this.id = id;
        this.domain = Objects.requireNonNull(domain);
    }

    /**
     * Compatibility entry point. Explicit APIs should prefer process(...).
     */
    public static OresSymbol of(String key) {
        return process(key);
    }

    /**
     * Compatibility entry point. Explicit APIs should prefer process(...).
     */
    public static OresSymbol of(String key, IsolatePolicy policy) {
        return process(key, policy);
    }

    /** Strongly canonical process-lifetime identity for trusted execution. */
    public static OresSymbol process(String key) {
        return process(key, IsolatePolicy.developer());
    }

    public static OresSymbol process(String key, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String normalized = validateKey(key);
        if (effectiveAdversarial(policy)) {
            OresSymbol known = PROCESS_SYMBOLS.get(normalized);
            if (known == null || !known.visibleToUntrusted()) {
                throw new SecurityException(
                        "untrusted code may use only explicitly exported process Symbols: " + normalized);
            }
            return known;
        }
        return internProcess(normalized, false);
    }

    /**
     * Compiler/runtime entry point for a statically known :literal.
     *
     * Dynamic callers intentionally cannot consume the reserved literal slice
     * of the process table. Existing dynamic symbols are still reused so
     * :ready and Symbol.process("ready") are exactly the same identity.
     */
    public static OresSymbol processLiteral(String key, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String literal = validateLiteralName(key);
        if (effectiveAdversarial(policy)) {
            OresSymbol known = PROCESS_SYMBOLS.get(literal);
            if (known == null || !known.visibleToUntrusted()) {
                throw new SecurityException(
                        "untrusted source may reference only explicitly exported Symbol literals: :" + literal);
            }
            return known;
        }
        return internProcess(literal, true);
    }

    /**
     * Atomically admit all compiler/source literals for a successfully checked
     * code unit. Capacity is preflighted under the insertion lock, so a failed
     * code load cannot leave a partially installed permanent identity set.
     */
    public static void registerProcessLiterals(
            Collection<String> keys,
            IsolatePolicy policy) {
        Objects.requireNonNull(keys, "keys");
        requireInternAuthority(policy, "source Symbol literal registration");

        LinkedHashSet<String> literals = new LinkedHashSet<>();
        for (String key : keys) literals.add(validateLiteralName(key));
        if (literals.isEmpty()) return;

        synchronized (PROCESS_INSERT_LOCK) {
            int missing = 0;
            for (String literal : literals) {
                if (!PROCESS_SYMBOLS.containsKey(literal)) missing++;
            }
            if (missing > MAX_PROCESS_SYMBOLS - PROCESS_SYMBOLS.size()) {
                throw new IllegalStateException(
                        "process Symbol table lacks capacity for " + missing
                                + " new source literal(s); limit=" + MAX_PROCESS_SYMBOLS);
            }
            long nextReservedId = reserveMonotonicIds(
                    NEXT_PROCESS_ID,
                    missing,
                    "process Symbol");
            for (String literal : literals) {
                if (PROCESS_SYMBOLS.containsKey(literal)) continue;
                PROCESS_SYMBOLS.put(
                        literal,
                        new OresSymbol(
                                Scope.PROCESS,
                                literal,
                                nextReservedId++,
                                PROCESS_DOMAIN));
            }
        }
    }

    /** Built-in protocol identity. Currently Symbol.iterator is defined. */
    public static OresSymbol wellKnown(String name, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        String validated = validateLiteralName(name);
        String key = switch (validated) {
            case "iterator" -> "Symbol.iterator";
            default -> throw new IllegalArgumentException(
                    "unknown well-known Symbol." + validated);
        };
        OresSymbol known = PROCESS_SYMBOLS.get(key);
        if (known != null) {
            if (effectiveAdversarial(policy) && !known.visibleToUntrusted()) {
                throw new SecurityException(
                        "well-known Symbol is not exported to untrusted execution: " + key);
            }
            return known;
        }
        if (effectiveAdversarial(policy)) {
            throw new SecurityException(
                    "well-known Symbol has not been admitted/exported by the trusted runtime: " + key);
        }
        return internProcess(key, true);
    }

    private static OresSymbol internProcess(String key, boolean compilerLiteral) {
        String normalized = validateKey(key);
        OresSymbol existing = PROCESS_SYMBOLS.get(normalized);
        if (existing != null) return existing;

        // Misses are rare compared with protocol-tag reads. Serialize only
        // insertion/capacity reservation so the common canonical lookup stays
        // monitor-free across actor carrier threads.
        synchronized (PROCESS_INSERT_LOCK) {
            existing = PROCESS_SYMBOLS.get(normalized);
            if (existing != null) return existing;
            if (PROCESS_SYMBOLS.size() >= MAX_PROCESS_SYMBOLS) {
                throw new IllegalStateException(
                        "process Symbol table limit exceeded: " + MAX_PROCESS_SYMBOLS
                                + "; use actor-local/string data for unbounded dynamic values");
            }
            if (!compilerLiteral && dynamicProcessSymbols >= MAX_DYNAMIC_PROCESS_SYMBOLS) {
                throw new IllegalStateException(
                        "dynamic process Symbol table limit exceeded: " + MAX_DYNAMIC_PROCESS_SYMBOLS
                                + "; " + RESERVED_LITERAL_PROCESS_SYMBOLS
                                + " slots are reserved for compiler-known :literals");
            }
            OresSymbol created = new OresSymbol(
                    Scope.PROCESS, normalized, nextProcessId(), PROCESS_DOMAIN);
            PROCESS_SYMBOLS.put(normalized, created);
            if (!compilerLiteral) dynamicProcessSymbols++;
            return created;
        }
    }

    /**
     * Persistence-safe UUID identity. Stable equality is the UUID itself; the
     * process-local id is only a fast/debug handle and is never serialized.
     */
    public static OresSymbol stable(String uuidKey) {
        return stable(uuidKey, IsolatePolicy.developer());
    }

    public static OresSymbol stable(String uuidKey, IsolatePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        UUID stableId = validateStableKey(uuidKey);
        OresSymbol existing = STABLE_SYMBOLS.get(stableId);
        if (existing != null) {
            if (effectiveAdversarial(policy) && !existing.visibleToUntrusted()) {
                throw new SecurityException(
                        "stable Symbol is not exported to untrusted execution: " + stableId);
            }
            return existing;
        }
        if (effectiveAdversarial(policy)) {
            throw new SecurityException(
                    "untrusted code may use only explicitly exported stable Symbols: " + stableId);
        }

        synchronized (STABLE_INSERT_LOCK) {
            existing = STABLE_SYMBOLS.get(stableId);
            if (existing != null) return existing;
            if (STABLE_SYMBOLS.size() >= MAX_STABLE_SYMBOLS) {
                throw new IllegalStateException(
                        "stable Symbol table limit exceeded: " + MAX_STABLE_SYMBOLS);
            }
            String canonical = stableId.toString();
            OresSymbol created = new OresSymbol(
                    Scope.STABLE, canonical, nextStableId(), stableId);
            STABLE_SYMBOLS.put(stableId, created);
            return created;
        }
    }

    public Scope scope() {
        return scope;
    }

    public String key() {
        return key;
    }

    /** Process-local monotonic handle. Never serialize this value. */
    public long id() {
        return id;
    }

    /** Identity-domain id; useful for diagnostics, never as a wire format. */
    public UUID domainId() {
        return domain;
    }

    /** Compatibility/readability alias for identifier-style protocol tags. */
    public String name() {
        return key;
    }

    public boolean sendable() {
        return true;
    }

    public boolean processStable() {
        return true;
    }

    public boolean visibleToUntrusted() {
        return UNTRUSTED_EXPORTS.contains(toWire());
    }

    /**
     * Trusted supervisor/runtime operation. Export is visibility-only: it does
     * not create a second identity and does not grant interning authority.
     */
    public static void exportToUntrusted(OresSymbol symbol, IsolatePolicy policy) {
        Objects.requireNonNull(symbol, "symbol");
        requireExportAuthority(policy, "export Symbol to untrusted");
        UNTRUSTED_EXPORTS.add(symbol.toWire());
    }

    public static void revokeFromUntrusted(OresSymbol symbol, IsolatePolicy policy) {
        Objects.requireNonNull(symbol, "symbol");
        requireExportAuthority(policy, "revoke Symbol from untrusted");
        UNTRUSTED_EXPORTS.remove(symbol.toWire());
    }

    public Wire toWire() {
        return new Wire(scope, key);
    }

    /**
     * Safe wire decode: resolve only identities already admitted to this
     * process. This deliberately does not intern attacker-controlled wire text.
     */
    public static OresSymbol fromWire(Wire wire, IsolatePolicy policy) {
        Objects.requireNonNull(wire, "wire");
        Objects.requireNonNull(policy, "policy");
        OresSymbol known = switch (wire.scope()) {
            case PROCESS -> PROCESS_SYMBOLS.get(validateKey(wire.key()));
            case STABLE -> STABLE_SYMBOLS.get(validateStableKey(wire.key()));
        };
        if (known == null) {
            throw new IllegalArgumentException(
                    "unknown " + wire.scope().name().toLowerCase(Locale.ROOT)
                            + " Symbol wire key; pre-register it or use fromTrustedWire(...) "
                            + "only for authenticated/trusted symbol dictionaries");
        }
        if (effectiveAdversarial(policy) && !known.visibleToUntrusted()) {
            throw new SecurityException(
                    "Symbol is not exported to the untrusted boundary: " + known);
        }
        return known;
    }

    /**
     * Explicitly trusted wire admission. This may create a permanent bounded
     * PROCESS/STABLE registry entry and therefore must never be used directly
     * on unauthenticated or attacker-controlled text.
     */
    public static OresSymbol fromTrustedWire(Wire wire, IsolatePolicy policy) {
        Objects.requireNonNull(wire, "wire");
        requireInternAuthority(policy, "trusted Symbol wire decode");
        return switch (wire.scope()) {
            case PROCESS -> internProcess(wire.key(), false);
            case STABLE -> stable(wire.key(), policy);
        };
    }

    public static int processRegistryEntries() {
        return PROCESS_SYMBOLS.size();
    }

    public static int stableRegistryEntries() {
        return STABLE_SYMBOLS.size();
    }

    public static int liveRegistryEntries() {
        return Math.addExact(PROCESS_SYMBOLS.size(), STABLE_SYMBOLS.size());
    }

    private static long nextProcessId() {
        return nextMonotonicId(NEXT_PROCESS_ID, "process Symbol");
    }

    private static long nextStableId() {
        return nextMonotonicId(NEXT_STABLE_ID, "stable Symbol");
    }

    private static long nextMonotonicId(AtomicLong counter, String domain) {
        return reserveMonotonicIds(counter, 1, domain);
    }

    private static long reserveMonotonicIds(
            AtomicLong counter,
            int count,
            String domain) {
        if (count < 0) throw new IllegalArgumentException("id reservation count cannot be negative");
        if (count == 0) return counter.get();
        while (true) {
            long current = counter.get();
            if (current <= 0 || current > Long.MAX_VALUE - count) {
                throw new IllegalStateException(domain + " id space exhausted");
            }
            long next = current + count;
            if (counter.compareAndSet(current, next)) return current;
        }
    }

    private static boolean effectiveAdversarial(IsolatePolicy policy) {
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        return policy.adversarial() || (actorPolicy != null && actorPolicy.adversarial());
    }

    private static void requireInternAuthority(IsolatePolicy policy, String api) {
        Objects.requireNonNull(policy, "policy");
        if (effectiveAdversarial(policy)) {
            throw new SecurityException(
                    "untrusted/adversarial execution has no global Symbol interning authority: " + api);
        }
    }

    private static void requireExportAuthority(IsolatePolicy policy, String api) {
        requireInternAuthority(policy, api);
        policy.require(IsolatePolicy.Capability.SYMBOL_EXPORT, api);
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) {
            actorPolicy.require(IsolatePolicy.Capability.SYMBOL_EXPORT, api);
        }
    }

    private static String validateLiteralName(String raw) {
        String literal = validateKey(raw);
        if (literal.length() > MAX_LITERAL_NAME_LENGTH || !IDENTIFIER.matcher(literal).matches()) {
            throw new IllegalArgumentException(
                    "Symbol literal key must be an identifier of at most "
                            + MAX_LITERAL_NAME_LENGTH + " characters");
        }
        return literal;
    }

    private static String validateKey(String key) {
        Objects.requireNonNull(key, "symbol key");
        if (key.isBlank()) throw new IllegalArgumentException("symbol key cannot be blank");
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "symbol key exceeds " + MAX_KEY_LENGTH + " characters");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= key.length() || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "symbol key cannot contain unpaired surrogate characters");
                }
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "symbol key cannot contain unpaired surrogate characters");
            }
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        "symbol key cannot contain control characters");
            }
        }
        return key;
    }

    private static UUID validateStableKey(String key) {
        String validated = validateKey(key);
        if (validated.length() != 36) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID");
        }
        final UUID uuid;
        try {
            uuid = UUID.fromString(validated);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID", invalid);
        }
        String canonical = uuid.toString();
        if (!canonical.equals(validated.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "stable Symbol key must be a canonical UUID");
        }
        return uuid;
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OresSymbol symbol) || symbol.scope != scope) return false;
        // Logical identity must survive isolated heaps. Never use pointer,
        // process-local numeric handle, or heap-local domain for equality.
        return key.equals(symbol.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, key);
    }

    @Override
    public String toString() {
        return switch (scope) {
            case PROCESS -> key.equals("Symbol.iterator")
                    ? key
                    : IDENTIFIER.matcher(key).matches()
                            ? ":" + key
                            : "Symbol.process(\"" + escaped(key) + "\")";
            case STABLE -> "Symbol.stable(\"" + escaped(key) + "\")";
        };
    }

    public record Wire(Scope scope, String key) {
        public Wire {
            Objects.requireNonNull(scope, "scope");
            key = scope == Scope.STABLE
                    ? validateStableKey(key).toString()
                    : validateKey(key);
        }
    }
}
