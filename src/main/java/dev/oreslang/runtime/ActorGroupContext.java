package dev.oreslang.runtime;

/**
 * Narrow capability surface exposed to an ActorGroup Mailman.
 *
 * CONTROL-carrier identity does not confer host/supervisor authority. Mailmen
 * may inspect their own group identity/member count and send through ActorRef
 * mailboxes; they do not receive ActorRuntime lifecycle/control capabilities.
 */
public interface ActorGroupContext {
    ActorRuntime.ActorGroupId groupId();

    int memberCount();

    <M> void send(ActorRuntime.ActorRef<M> target, M message);
}
