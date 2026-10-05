use next_loggers::{transition_shutdown_state, ShutdownAction, ShutdownEvent, ShutdownPhase};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransitionVectors {
    schema: String,
    machine: String,
    cases: Vec<TransitionCase>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TransitionCase {
    id: String,
    phase: String,
    event: String,
    expected_phase: String,
    expected_action: String,
}

fn phase(value: &str) -> ShutdownPhase {
    match value {
        "running" => ShutdownPhase::Running,
        "draining" => ShutdownPhase::Draining,
        "forced" => ShutdownPhase::Forced,
        "closed" => ShutdownPhase::Closed,
        other => panic!("unknown phase {other}"),
    }
}

fn event(value: &str) -> ShutdownEvent {
    match value {
        "trigger" => ShutdownEvent::Trigger,
        "force-now" => ShutdownEvent::ForceNow,
        "mark-closed" => ShutdownEvent::MarkClosed,
        other => panic!("unknown event {other}"),
    }
}

fn action(value: &str) -> ShutdownAction {
    match value {
        "begin-graceful" => ShutdownAction::BeginGraceful,
        "force" => ShutdownAction::Force,
        "close" => ShutdownAction::Close,
        "ignore" => ShutdownAction::Ignore,
        other => panic!("unknown action {other}"),
    }
}

#[test]
fn rust_shutdown_relation_refines_every_shared_vector() {
    let vectors: TransitionVectors =
        serde_json::from_str(include_str!("../../../formal/shutdown-transitions.v1.json"))
            .expect("valid shared shutdown vectors");

    assert_eq!(
        vectors.schema,
        "ores.otel.log/shutdown-transition-vectors/v1"
    );
    assert_eq!(vectors.machine, "server-shutdown/v1");
    assert_eq!(vectors.cases.len(), 12);

    for vector in vectors.cases {
        let actual = transition_shutdown_state(phase(&vector.phase), event(&vector.event));
        assert_eq!(
            actual.phase,
            phase(&vector.expected_phase),
            "{} phase",
            vector.id
        );
        assert_eq!(
            actual.action,
            action(&vector.expected_action),
            "{} action",
            vector.id
        );
    }
}
