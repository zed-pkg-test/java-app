//! Pure shutdown transition relation shared with the TypeScript and Dart SDKs.

/// Observable server-shutdown phase.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum ShutdownPhase {
    Running,
    Draining,
    Forced,
    Closed,
}

/// External or internal input applied to the shutdown state machine.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum ShutdownEvent {
    Trigger,
    ForceNow,
    MarkClosed,
}

/// Side effect requested by a transition.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum ShutdownAction {
    BeginGraceful,
    Force,
    Close,
    Ignore,
}

/// Result of applying one shutdown event.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ShutdownTransition {
    pub phase: ShutdownPhase,
    pub action: ShutdownAction,
}

/// Total, deterministic transition relation corresponding to the TLA+ model.
#[must_use]
pub const fn transition_shutdown_state(
    phase: ShutdownPhase,
    event: ShutdownEvent,
) -> ShutdownTransition {
    use ShutdownAction::{BeginGraceful, Close, Force, Ignore};
    use ShutdownEvent::{ForceNow, MarkClosed, Trigger};
    use ShutdownPhase::{Closed, Draining, Forced, Running};

    match (phase, event) {
        (Running, Trigger) => ShutdownTransition {
            phase: Draining,
            action: BeginGraceful,
        },
        (Draining, Trigger) | (Running | Draining, ForceNow) => ShutdownTransition {
            phase: Forced,
            action: Force,
        },
        (Draining, MarkClosed) => ShutdownTransition {
            phase: Closed,
            action: Close,
        },
        _ => ShutdownTransition {
            phase,
            action: Ignore,
        },
    }
}

/// Small stateful wrapper for runtime integrations.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ShutdownStateMachine {
    phase: ShutdownPhase,
    signal_count: u64,
}

impl Default for ShutdownStateMachine {
    fn default() -> Self {
        Self {
            phase: ShutdownPhase::Running,
            signal_count: 0,
        }
    }
}

impl ShutdownStateMachine {
    #[must_use]
    pub const fn phase(&self) -> ShutdownPhase {
        self.phase
    }

    #[must_use]
    pub const fn signal_count(&self) -> u64 {
        self.signal_count
    }

    pub fn trigger(&mut self) -> ShutdownAction {
        self.signal_count = self.signal_count.saturating_add(1);
        self.apply(ShutdownEvent::Trigger)
    }

    pub fn force_now(&mut self) -> ShutdownAction {
        self.apply(ShutdownEvent::ForceNow)
    }

    pub fn mark_closed(&mut self) -> bool {
        self.apply(ShutdownEvent::MarkClosed) == ShutdownAction::Close
    }

    fn apply(&mut self, event: ShutdownEvent) -> ShutdownAction {
        let transition = transition_shutdown_state(self.phase, event);
        self.phase = transition.phase;
        transition.action
    }
}

#[cfg(test)]
mod tests {
    use super::{ShutdownAction, ShutdownPhase, ShutdownStateMachine};

    #[test]
    fn graceful_then_force_is_monotonic() {
        let mut machine = ShutdownStateMachine::default();
        assert_eq!(machine.trigger(), ShutdownAction::BeginGraceful);
        assert_eq!(machine.phase(), ShutdownPhase::Draining);
        assert_eq!(machine.trigger(), ShutdownAction::Force);
        assert_eq!(machine.phase(), ShutdownPhase::Forced);
        assert_eq!(machine.trigger(), ShutdownAction::Ignore);
        assert_eq!(machine.phase(), ShutdownPhase::Forced);
        assert_eq!(machine.signal_count(), 3);
    }

    #[test]
    fn only_draining_can_close_gracefully() {
        let mut machine = ShutdownStateMachine::default();
        assert!(!machine.mark_closed());
        assert_eq!(machine.trigger(), ShutdownAction::BeginGraceful);
        assert!(machine.mark_closed());
        assert_eq!(machine.phase(), ShutdownPhase::Closed);
        assert_eq!(machine.force_now(), ShutdownAction::Ignore);
    }
}
