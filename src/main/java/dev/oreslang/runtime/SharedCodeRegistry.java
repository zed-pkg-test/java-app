package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-owned, process-local registry for immutable Oreslang code images.
 *
 * <p>Actor isolation is about guest <em>state</em> and capabilities, not about
 * copying read-only source/compiled metadata into every actor. A leased Source
 * may be reused across independently constructed Graal contexts, including
 * private/untrusted actor contexts. Nothing in this registry is a guest value,
 * a mutable module environment, an ActorRef, a closure, or a host capability.
 *
 * <p>This guarantees Source <em>object</em> identity reuse within this host
 * process. It does NOT by itself guarantee sharing of JIT machine code,
 * physical pages across OS processes, or compiled Truffle call targets; those
 * require separate backend-specific proofs.
 */
public final class SharedCodeRegistry {
    private record Key(String codeUnitId, String sha256) { }

    private static final SharedCodeRegistry PROCESS = new SharedCodeRegistry();

    private final Map<Key, Entry> images = new HashMap<>();
    private final Set<Key> adversarialShareAllowlist = new HashSet<>();

    /** This registry is owned by the supervisor, never exposed to guest code. */
    public static SharedCodeRegistry process() {
        return PROCESS;
    }

    /**
     * Trusted-supervisor admission for sharing an exact immutable image with
     * adversarial actors. Every code unit/import is approved independently.
     */
    public synchronized void approveForAdversarialSharing(
            IsolatePolicy supervisorPolicy, String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Objects.requireNonNull(supervisorPolicy, "supervisorPolicy");
        if (supervisorPolicy.adversarial()) {
            throw new SecurityException("adversarial policy cannot approve shared code");
        }
        supervisorPolicy.require(
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                "approve adversarial shared code");
        CapabilityChecker.check(
                OresCompiler.parseAndTypeCheck(sourceText),
                IsolatePolicy.untrustedActor());
        adversarialShareAllowlist.add(new Key(codeUnitId, digest(sourceText)));
    }

    public synchronized void revokeForAdversarialSharing(
            IsolatePolicy supervisorPolicy, String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Objects.requireNonNull(supervisorPolicy, "supervisorPolicy");
        if (supervisorPolicy.adversarial()) {
            throw new SecurityException("adversarial policy cannot revoke shared code");
        }
        supervisorPolicy.require(
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                "revoke adversarial shared code");
        adversarialShareAllowlist.remove(new Key(codeUnitId, digest(sourceText)));
    }

    public synchronized boolean approvedForAdversarialSharing(
            String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        return adversarialShareAllowlist.contains(
                new Key(codeUnitId, digest(sourceText)));
    }

    private static void requireIdentity(String codeUnitId, String sourceText) {
        Objects.requireNonNull(codeUnitId, "codeUnitId");
        Objects.requireNonNull(sourceText, "sourceText");
        if (codeUnitId.isBlank()) {
            throw new IllegalArgumentException("codeUnitId cannot be blank");
        }
    }

    /**
     * Acquire a pinned immutable image for one code-unit identity and version.
     * A source name is part of the key because diagnostics and import resolution
     * must not inherit another unit's source identity.
     */
    public synchronized Lease acquire(String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Key key = new Key(codeUnitId, digest(sourceText));
        Entry entry = images.get(key);
        if (entry == null) {
            Source source = Source.newBuilder(OresLanguage.ID, sourceText, codeUnitId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            entry = new Entry(key, source, sourceText);
            images.put(key, entry);
        } else if (!entry.originalText.equals(sourceText)) {
            // Never accept digest equivalence as authority to execute different
            // text. A cryptographic collision must fail closed.
            throw new SecurityException("different source text has the same code-image digest");
        }
        entry.references++;
        return new Lease(this, entry);
    }

    /**
     * Acquire a shared image for adversarial/untrusted execution only when the
     * supervisor has explicitly approved this exact code identity and digest.
     * This method is intentionally fail-closed; ordinary acquire() is for the
     * trusted/private side of the runtime boundary.
     */
    public Lease acquireUntrusted(
            String codeUnitId,
            String sourceText,
            UntrustedCodeShareAllowlist allowlist) {
        Objects.requireNonNull(allowlist, "allowlist");
        if (!allowlist.permits(codeUnitId, sourceText)) {
            throw new SecurityException(
                    "untrusted code image is not allowlisted for process-memory sharing: "
                            + codeUnitId);
        }
        return acquire(codeUnitId, sourceText);
    }

    /**
     * Return a cached immutable, checked definition tree for a currently pinned
     * image, or null if this source was not admitted through the host registry.
     * The caller still must perform its destination-specific capability checks.
     */
    public Ast.Program checkedProgramIfLive(String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Entry entry;
        synchronized (this) {
            entry = images.get(new Key(codeUnitId, digest(sourceText)));
            if (entry == null) return null;
            if (!entry.originalText.equals(sourceText)) {
                throw new SecurityException("different source text has the same code-image digest");
            }
        }
        return entry.checkedProgram();
    }

    /**
     * Called only from OresLanguage.parse. It is diagnostic, not an admission
     * decision: the trusted host chooses the Source and Engine before parse.
     * If a live shared image exists, count how often Graal actually calls the
     * parser for this exact code identity and digest.
     */
    public synchronized void recordParse(String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Entry entry = images.get(new Key(codeUnitId, digest(sourceText)));
        if (entry != null && entry.originalText.equals(sourceText)) {
            entry.parseInvocations++;
        }
    }

    public synchronized long parseInvocations(String codeUnitId, String sourceText) {
        requireIdentity(codeUnitId, sourceText);
        Entry entry = images.get(new Key(codeUnitId, digest(sourceText)));
        return entry == null ? 0L : entry.parseInvocations;
    }

    /** Supervisor-only diagnostic; no handles or source contents are exposed. */
    public synchronized int liveImages() {
        return images.size();
    }

    private synchronized void release(Entry entry) {
        Entry active = images.get(entry.key);
        if (active != entry || entry.references <= 0) {
            throw new IllegalStateException("code-image lease ownership mismatch");
        }
        if (--entry.references == 0) {
            images.remove(entry.key);
        }
    }

    private static String digest(String sourceText) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(sourceText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class Entry {
        private final Key key;
        private final Source source;
        private final String originalText;
        private Ast.Program checkedProgram;
        private int references;
        private long parseInvocations;

        private Entry(Key key, Source source, String originalText) {
            this.key = key;
            this.source = source;
            this.originalText = originalText;
        }

        // The typed AST is immutable data; there is no evaluator, closure or
        // actor/module runtime state in this object. Compiler state remains local.
        private synchronized Ast.Program checkedProgram() {
            if (checkedProgram == null) {
                checkedProgram = OresCompiler.parseAndTypeCheck(originalText);
            }
            return checkedProgram;
        }
    }

    /**
     * Pins an image while a code generation may still execute it. Closing the
     * final lease removes it from the strong cache; the JVM may then reclaim it.
     */
    public static final class Lease implements AutoCloseable {
        private final SharedCodeRegistry owner;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(SharedCodeRegistry owner, Entry entry) {
            this.owner = owner;
            this.entry = entry;
        }

        public Source source() {
            if (closed.get()) {
                throw new IllegalStateException("code-image lease is closed");
            }
            return entry.source;
        }

        public String sha256() {
            return entry.key.sha256();
        }

        public Ast.Program checkedProgram() {
            if (closed.get()) {
                throw new IllegalStateException("code-image lease is closed");
            }
            return entry.checkedProgram();
        }

        public long parseInvocations() {
            if (closed.get()) {
                throw new IllegalStateException("code-image lease is closed");
            }
            synchronized (owner) {
                return entry.parseInvocations;
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(entry);
            }
        }
    }
}
