//! Polyglot structured logging and explicit OpenTelemetry context adapters.

#[path = "core.rs"]
mod logger_core;

pub use logger_core::*;

pub mod apm;
pub mod context;
#[cfg(all(unix, feature = "launcher"))]
pub mod launcher;
pub mod shutdown;
pub mod span;

pub use apm::{
    evaluate_resource_snapshot, measure, sample_filesystem, sample_process, sample_resources,
    ApmError, FilesystemSnapshot, LatencyHistogram, LatencyHistogramConfig,
    LatencyHistogramSnapshot, ProcessSnapshot, ResourceHealth, ResourcePressure,
    ResourcePressureKind, ResourceSnapshot, ResourceThresholds, Timed,
};
pub use context::{
    apply_log_context, capture_log_context, contextualize_future, current_log_context,
    enter_log_context, merge_log_context, update_log_context, with_captured_log_context,
    with_log_context, with_log_context_async, ContextFuture, LogContext, LogContextGuard,
};
pub use shutdown::{
    transition_shutdown_state, ShutdownAction, ShutdownEvent, ShutdownPhase, ShutdownStateMachine,
    ShutdownTransition,
};
pub use span::{with_span, with_span_async, Span, Tracer, OTEL_STATUS_ERROR, OTEL_STATUS_OK};
