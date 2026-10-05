use next_loggers::{
    current_log_context, json, with_span, with_span_async, JsonObject, LogContext, LogLevel,
    Logger, LoggerError, MemoryTransport, Options, Span, Tracer, OTEL_STATUS_ERROR,
};
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::future::{pending, Future};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll, Waker};

#[derive(Clone, Debug)]
struct TestError(&'static str);
impl Display for TestError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.0)
    }
}
impl Error for TestError {}

#[derive(Clone, Debug)]
struct SpanState {
    context: LogContext,
    recording: bool,
    status: Option<(u8, String)>,
    errors: Vec<String>,
    events: Vec<String>,
    ended: usize,
}

#[derive(Clone)]
struct FakeSpan(Arc<Mutex<SpanState>>);
impl Span for FakeSpan {
    fn log_context(&self) -> Result<LogContext, LoggerError> {
        Ok(self.0.lock().unwrap().context.clone())
    }
    fn is_recording(&self) -> Result<bool, LoggerError> {
        Ok(self.0.lock().unwrap().recording)
    }
    fn add_event(&self, name: &str, _attributes: &JsonObject) -> Result<(), LoggerError> {
        self.0.lock().unwrap().events.push(name.into());
        Ok(())
    }
    fn record_error(&self, error: &(dyn Error + Send + Sync)) -> Result<(), LoggerError> {
        self.0.lock().unwrap().errors.push(error.to_string());
        Ok(())
    }
    fn set_status(&self, code: u8, description: &str) -> Result<(), LoggerError> {
        self.0.lock().unwrap().status = Some((code, description.into()));
        Ok(())
    }
    fn end(&self) -> Result<(), LoggerError> {
        self.0.lock().unwrap().ended += 1;
        Ok(())
    }
}

struct FakeTracer(FakeSpan);
impl Tracer for FakeTracer {
    fn start_span(
        &self,
        _name: &str,
        _attributes: &JsonObject,
    ) -> Result<Arc<dyn Span>, LoggerError> {
        Ok(Arc::new(self.0.clone()))
    }
}

fn logger() -> (Logger, Arc<MemoryTransport>) {
    let transport = Arc::new(MemoryTransport::default());
    let logger = Logger::new(
        Options {
            app_name: "rust-span".into(),
            max_level: LogLevel::Debug,
            console: false,
            ..Options::default()
        }
        .with_transport(transport.clone()),
    );
    (logger, transport)
}

fn fake_span(recording: bool) -> (FakeSpan, Arc<Mutex<SpanState>>) {
    let state = Arc::new(Mutex::new(SpanState {
        context: LogContext {
            trace_id: Some("0123456789abcdef0123456789abcdef".into()),
            span_id: Some("0123456789abcdef".into()),
            ..LogContext::default()
        },
        recording,
        status: None,
        errors: Vec::new(),
        events: Vec::new(),
        ended: 0,
    }));
    (FakeSpan(state.clone()), state)
}

#[test]
fn sampled_out_span_keeps_correlation_without_mutation() {
    let (logger, transport) = logger();
    let (span, state) = fake_span(false);
    let tracer = FakeTracer(span);
    let value =
        with_span::<_, TestError, _>(&logger, &tracer, "sampled-out", JsonObject::new(), |_| {
            Ok(current_log_context().trace_id.unwrap())
        })
        .unwrap();
    assert_eq!(value, "0123456789abcdef0123456789abcdef");
    let state = state.lock().unwrap();
    assert_eq!(state.status, None);
    assert!(state.errors.is_empty());
    assert!(state.events.is_empty());
    assert_eq!(state.ended, 1);
    assert_eq!(
        transport
            .records()
            .iter()
            .map(|record| record.trace_id.clone().unwrap())
            .collect::<Vec<_>>(),
        vec![
            "0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef"
        ]
    );
}

#[test]
fn recording_span_records_error_and_preserves_identity() {
    let (logger, _) = logger();
    let (span, state) = fake_span(true);
    let tracer = FakeTracer(span);
    let result = with_span(&logger, &tracer, "failure", JsonObject::new(), |_| {
        Err::<(), _>(TestError("boom"))
    });
    assert_eq!(result.unwrap_err().0, "boom");
    let state = state.lock().unwrap();
    assert_eq!(state.status, Some((OTEL_STATUS_ERROR, "boom".into())));
    assert_eq!(state.errors, vec!["boom"]);
    assert_eq!(state.ended, 1);
    assert_eq!(
        state.events,
        vec!["ores.otel.log.start", "ores.otel.log.error"]
    );
}

#[test]
fn dropping_async_span_future_ends_application_owned_span() {
    let (logger, _) = logger();
    let (span, state) = fake_span(false);
    let tracer = FakeTracer(span);
    let future = with_span_async::<(), TestError, _, _>(
        &logger,
        &tracer,
        "cancelled",
        JsonObject::from_iter([("test".into(), json!(true))]),
        |_| async { pending::<Result<(), TestError>>().await },
    );
    let mut task = Context::from_waker(Waker::noop());
    let mut future = Box::pin(future);
    assert!(matches!(future.as_mut().poll(&mut task), Poll::Pending));
    drop(future);
    assert_eq!(state.lock().unwrap().ended, 1);
}
