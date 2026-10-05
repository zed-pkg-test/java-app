//! Explicit adapters around application-owned OpenTelemetry spans.

use crate::context::{apply_log_context, enter_log_context, with_log_context_async, LogContext};
use crate::{JsonObject, Logger, LoggerError, Value};
use std::any::Any;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::future::Future;
use std::panic::{catch_unwind, resume_unwind, AssertUnwindSafe};
use std::sync::Arc;
use std::time::Instant;

pub const OTEL_STATUS_OK: u8 = 1;
pub const OTEL_STATUS_ERROR: u8 = 2;

/// Structural adapter implemented around an application-owned OTEL span.
pub trait Span: Send + Sync {
    fn log_context(&self) -> Result<LogContext, LoggerError>;
    fn is_recording(&self) -> Result<bool, LoggerError>;
    fn add_event(&self, _name: &str, _attributes: &JsonObject) -> Result<(), LoggerError> {
        Ok(())
    }
    fn record_error(&self, error: &(dyn Error + Send + Sync)) -> Result<(), LoggerError>;
    fn set_status(&self, code: u8, description: &str) -> Result<(), LoggerError>;
    fn end(&self) -> Result<(), LoggerError>;
}

/// Structural adapter implemented around an application-owned OTEL tracer.
pub trait Tracer: Send + Sync {
    fn start_span(&self, name: &str, attributes: &JsonObject)
        -> Result<Arc<dyn Span>, LoggerError>;
}

struct NoopSpan;

impl Span for NoopSpan {
    fn log_context(&self) -> Result<LogContext, LoggerError> {
        Ok(LogContext::default())
    }

    fn is_recording(&self) -> Result<bool, LoggerError> {
        Ok(false)
    }

    fn record_error(&self, _error: &(dyn Error + Send + Sync)) -> Result<(), LoggerError> {
        Ok(())
    }

    fn set_status(&self, _code: u8, _description: &str) -> Result<(), LoggerError> {
        Ok(())
    }

    fn end(&self) -> Result<(), LoggerError> {
        Ok(())
    }
}

#[derive(Debug)]
struct PanicError(String);

impl Display for PanicError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl Error for PanicError {}

fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "non-string panic payload".to_string()
    }
}

fn fields(operation: &str, name: &str, started: Instant) -> JsonObject {
    JsonObject::from_iter([
        (
            "otel.bridge_operation".to_string(),
            Value::String(operation.to_string()),
        ),
        (
            "otel.span_name".to_string(),
            Value::String(name.to_string()),
        ),
        (
            "otel.duration_ms".to_string(),
            serde_json::Number::from_f64(started.elapsed().as_secs_f64() * 1_000.0)
                .map(Value::Number)
                .unwrap_or(Value::Null),
        ),
    ])
}

fn send_bridge(event: crate::Event, context: &LogContext) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let _ = apply_log_context(event, context).send();
    }));
}

fn bridge_warn(
    logger: &Logger,
    context: &LogContext,
    operation: &str,
    name: &str,
    error: &LoggerError,
) {
    send_bridge(
        logger
            .warn(vec![
                Value::String("OpenTelemetry bridge operation failed".to_string()),
                Value::String(operation.to_string()),
                Value::String(error.to_string()),
            ])
            .add_fields(JsonObject::from_iter([
                (
                    "otel.bridge_operation".to_string(),
                    Value::String(operation.to_string()),
                ),
                (
                    "otel.span_name".to_string(),
                    Value::String(name.to_string()),
                ),
            ]))
            .add_tags(["otel-span", "otel-bridge-error"]),
        context,
    );
}

fn safe_span_call(
    logger: &Logger,
    context: &LogContext,
    operation: &str,
    name: &str,
    callback: impl FnOnce() -> Result<(), LoggerError>,
) {
    match catch_unwind(AssertUnwindSafe(callback)) {
        Ok(Ok(())) => {}
        Ok(Err(error)) => bridge_warn(logger, context, operation, name, &error),
        Err(payload) => bridge_warn(
            logger,
            context,
            operation,
            name,
            &LoggerError(format!("panic: {}", panic_message(payload.as_ref()))),
        ),
    }
}

fn recording(logger: &Logger, context: &LogContext, name: &str, span: &dyn Span) -> bool {
    match catch_unwind(AssertUnwindSafe(|| span.is_recording())) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            bridge_warn(logger, context, "is recording", name, &error);
            false
        }
        Err(payload) => {
            bridge_warn(
                logger,
                context,
                "is recording",
                name,
                &LoggerError(format!("panic: {}", panic_message(payload.as_ref()))),
            );
            false
        }
    }
}

fn start_span(
    logger: &Logger,
    tracer: &dyn Tracer,
    name: &str,
    attributes: &JsonObject,
) -> Arc<dyn Span> {
    match catch_unwind(AssertUnwindSafe(|| tracer.start_span(name, attributes))) {
        Ok(Ok(span)) => span,
        Ok(Err(error)) => {
            bridge_warn(logger, &LogContext::default(), "start span", name, &error);
            Arc::new(NoopSpan)
        }
        Err(payload) => {
            bridge_warn(
                logger,
                &LogContext::default(),
                "start span",
                name,
                &LoggerError(format!("panic: {}", panic_message(payload.as_ref()))),
            );
            Arc::new(NoopSpan)
        }
    }
}

fn span_context(logger: &Logger, name: &str, span: &dyn Span) -> LogContext {
    match catch_unwind(AssertUnwindSafe(|| span.log_context())) {
        Ok(Ok(context)) => context.normalized(),
        Ok(Err(error)) => {
            bridge_warn(
                logger,
                &LogContext::default(),
                "read span context",
                name,
                &error,
            );
            LogContext::default()
        }
        Err(payload) => {
            bridge_warn(
                logger,
                &LogContext::default(),
                "read span context",
                name,
                &LoggerError(format!("panic: {}", panic_message(payload.as_ref()))),
            );
            LogContext::default()
        }
    }
}

struct SpanEndGuard {
    logger: Logger,
    span: Arc<dyn Span>,
    context: LogContext,
    name: String,
}

impl Drop for SpanEndGuard {
    fn drop(&mut self) {
        safe_span_call(&self.logger, &self.context, "end span", &self.name, || {
            self.span.end()
        });
    }
}

fn record_start(logger: &Logger, context: &LogContext, name: &str, span: &dyn Span) {
    let attributes = JsonObject::from_iter([
        (
            "otel.span_name".to_string(),
            Value::String(name.to_string()),
        ),
        (
            "otel.span_phase".to_string(),
            Value::String("start".to_string()),
        ),
    ]);
    send_bridge(
        logger
            .debug(vec![
                Value::String("span started".to_string()),
                Value::String(name.to_string()),
            ])
            .add_fields(attributes.clone())
            .add_tags(["otel-span"]),
        context,
    );
    if recording(logger, context, name, span) {
        safe_span_call(logger, context, "record start event", name, || {
            span.add_event("ores.otel.log.start", &attributes)
        });
    }
}

fn record_success(
    logger: &Logger,
    context: &LogContext,
    name: &str,
    span: &dyn Span,
    started: Instant,
) {
    if recording(logger, context, name, span) {
        safe_span_call(logger, context, "set success status", name, || {
            span.set_status(OTEL_STATUS_OK, "")
        });
        safe_span_call(logger, context, "record end event", name, || {
            span.add_event("ores.otel.log.end", &fields("end", name, started))
        });
    }
    send_bridge(
        logger
            .debug(vec![
                Value::String("span completed".to_string()),
                Value::String(name.to_string()),
            ])
            .add_fields(fields("end", name, started))
            .add_tags(["otel-span"]),
        context,
    );
}

fn record_failure<E: Error + Send + Sync + 'static>(
    logger: &Logger,
    context: &LogContext,
    name: &str,
    span: &dyn Span,
    error: &E,
    started: Instant,
) {
    if recording(logger, context, name, span) {
        safe_span_call(logger, context, "record exception", name, || {
            span.record_error(error)
        });
        safe_span_call(logger, context, "set error status", name, || {
            span.set_status(OTEL_STATUS_ERROR, &error.to_string())
        });
        safe_span_call(logger, context, "record error event", name, || {
            span.add_event("ores.otel.log.error", &fields("error", name, started))
        });
    }
    send_bridge(
        logger
            .error(vec![
                Value::String("span failed".to_string()),
                Value::String(name.to_string()),
                Value::String(error.to_string()),
            ])
            .add_fields(fields("error", name, started))
            .add_tags(["otel-span"]),
        context,
    );
}

/// Run a synchronous callback inside one explicit application-owned span.
pub fn with_span<T, E, F>(
    logger: &Logger,
    tracer: &dyn Tracer,
    name: &str,
    attributes: JsonObject,
    callback: F,
) -> Result<T, E>
where
    E: Error + Send + Sync + 'static,
    F: FnOnce(Arc<dyn Span>) -> Result<T, E>,
{
    let span = start_span(logger, tracer, name, &attributes);
    let context = span_context(logger, name, span.as_ref());
    let _scope = enter_log_context(context.clone());
    let _end = SpanEndGuard {
        logger: logger.clone(),
        span: span.clone(),
        context: context.clone(),
        name: name.to_string(),
    };
    let started = Instant::now();
    record_start(logger, &context, name, span.as_ref());

    match catch_unwind(AssertUnwindSafe(|| callback(span.clone()))) {
        Ok(Ok(value)) => {
            record_success(logger, &context, name, span.as_ref(), started);
            Ok(value)
        }
        Ok(Err(error)) => {
            record_failure(logger, &context, name, span.as_ref(), &error, started);
            Err(error)
        }
        Err(payload) => {
            let error = PanicError(format!("panic: {}", panic_message(payload.as_ref())));
            record_failure(logger, &context, name, span.as_ref(), &error, started);
            resume_unwind(payload)
        }
    }
}

/// Run an async callback inside one explicit span. The context is installed for
/// every future poll, and dropping the returned future ends the span.
pub async fn with_span_async<T, E, F, Fut>(
    logger: &Logger,
    tracer: &dyn Tracer,
    name: &str,
    attributes: JsonObject,
    callback: F,
) -> Result<T, E>
where
    E: Error + Send + Sync + 'static,
    F: FnOnce(Arc<dyn Span>) -> Fut,
    Fut: Future<Output = Result<T, E>>,
{
    let span = start_span(logger, tracer, name, &attributes);
    let context = span_context(logger, name, span.as_ref());
    let _end = SpanEndGuard {
        logger: logger.clone(),
        span: span.clone(),
        context: context.clone(),
        name: name.to_string(),
    };
    let started = Instant::now();
    record_start(logger, &context, name, span.as_ref());

    match with_log_context_async(context.clone(), callback(span.clone())).await {
        Ok(value) => {
            record_success(logger, &context, name, span.as_ref(), started);
            Ok(value)
        }
        Err(error) => {
            record_failure(logger, &context, name, span.as_ref(), &error, started);
            Err(error)
        }
    }
}
