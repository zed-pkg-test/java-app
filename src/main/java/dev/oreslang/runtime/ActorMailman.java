package dev.oreslang.runtime;

/**
 * Serialized ActorGroup outbox handler.
 *
 * One logical Mailman belongs to one configured ActorGroup. The runtime owns
 * scheduling and fairness; implementations handle one admitted mail at a time
 * and must not create their own endless scheduler loop.\n *\n * <p>This compatibility hook is for trusted, non-suspending host callbacks. An\n * arbitrary Java callback can block on host primitives the runtime cannot\n * cooperatively unwind. Mailmen that may wait must use\n * {@link CooperativeActorMailman} via\n * {@code ActorGroup.installCooperativeMailman(...)} so waits release CONTROL\n * carriers and resume through runtime scheduling.</p>\n */
@FunctionalInterface
public interface ActorMailman<Out> {
    void receiveMail(ActorMail<Out> mail, ActorGroupContext context) throws Exception;
}
