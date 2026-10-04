package dev.oreslang.runtime;

/**
 * Marker for live authority/capability values that must never cross into an
 * adversarial/untrusted mailbox. Implementations are authority, not data.
 */
public interface SandboxForbiddenCapability {
}
