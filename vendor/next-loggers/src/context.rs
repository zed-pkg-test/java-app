//! Explicit thread/task context for the Rust SDK.
//!
//! Synchronous scopes use a guarded thread-local stack. Async code uses
//! [`ContextFuture`], which installs one immutable snapshot for each poll so a
//! task remains correlated when an executor moves it between threads. Nothing
//! patches an executor, future, runtime, or OpenTelemetry global.

use crate::{Event, JsonObject, Logger, LoggerError, Value};
use std::cell::RefCell;
use std::collections::BTreeMap;
use std::future::Future;
use std::marker::PhantomData;
use std::pin::Pin;
use std::rc::Rc;
use std::task::{Context as TaskContext, Poll};
use std::thread::{self, ThreadId};

#[derive(Clone, Debug, Default, PartialEq)]
pub struct LogContext {
    pub logged_in_user: JsonObject,
    pub users: Vec<JsonObject>,
    pub fields: JsonObject,
    pub trace_id: Option<String>,
    pub trace_ids: Vec<String>,
    pub span_id: Option<String>,
    pub trace_flags: u8,
    pub trace_state: Option<String>,
    pub remote: Option<bool>,
    pub baggage: BTreeMap<String, String>,
    pub routine_id: Option<String>,
    pub tags: Vec<String>,
    pub context: Vec<Value>,
    pub meta: Vec<Value>,
}

fn clean(value: Option<String>, maximum: usize) -> Option<String> {
    let value = value.unwrap_or_default().trim().to_string();
    if value.is_empty() {
        None
    } else if value.len() <= maximum {
        Some(value)
    } else {
        Some(value.chars().take(maximum).collect())
    }
}

fn unique_strings(values: impl IntoIterator<Item = String>) -> Vec<String> {
    values.into_iter().fold(Vec::new(), |unique, value| {
        let value = value.trim().to_string();
        if value.is_empty() || unique.contains(&value) {
            unique
        } else {
            unique.into_iter().chain(std::iter::once(value)).collect()
        }
    })
}

impl LogContext {
    /// A new context with every identifier trimmed, bounded and de-duplicated.
    /// Untouched fields move through struct-update syntax.
    pub fn normalized(self) -> Self {
        let trace_id = clean(self.trace_id, 256);
        Self {
            trace_ids: unique_strings(self.trace_ids.into_iter().chain(trace_id.clone())),
            trace_id,
            span_id: clean(self.span_id, 256),
            trace_state: clean(self.trace_state, 512),
            routine_id: clean(self.routine_id, 256),
            tags: unique_strings(self.tags),
            ..self
        }
    }
}

pub fn merge_log_context(outer: &LogContext, inner: &LogContext) -> LogContext {
    let outer = outer.clone().normalized();
    let inner = inner.clone().normalized();

    let logged_in_user = outer
        .logged_in_user
        .into_iter()
        .chain(inner.logged_in_user.clone())
        .collect();
    let fields = outer
        .fields
        .into_iter()
        .chain(inner.fields.clone())
        .collect();
    let baggage = outer
        .baggage
        .into_iter()
        .chain(inner.baggage.clone())
        .collect();

    let trace_id = inner.trace_id.clone().or(outer.trace_id.clone());
    let trace_ids = unique_strings(
        outer
            .trace_ids
            .into_iter()
            .chain(inner.trace_ids)
            .chain(trace_id.clone()),
    );
    let tags = unique_strings(outer.tags.into_iter().chain(inner.tags));
    let inner_has_span_context = inner.trace_id.is_some()
        || inner.span_id.is_some()
        || inner.trace_state.is_some()
        || inner.remote.is_some()
        || inner.trace_flags != 0;

    LogContext {
        logged_in_user,
        users: outer.users.into_iter().chain(inner.users.clone()).collect(),
        fields,
        trace_id,
        trace_ids,
        span_id: inner.span_id.clone().or(outer.span_id),
        trace_flags: if inner_has_span_context {
            inner.trace_flags
        } else {
            outer.trace_flags
        },
        trace_state: inner.trace_state.clone().or(outer.trace_state),
        remote: inner.remote.or(outer.remote),
        baggage,
        routine_id: inner.routine_id.clone().or(outer.routine_id),
        tags,
        context: outer
            .context
            .into_iter()
            .chain(inner.context.clone())
            .collect(),
        meta: outer.meta.into_iter().chain(inner.meta.clone()).collect(),
    }
    .normalized()
}

thread_local! {
    static CONTEXT_STACK: RefCell<Vec<LogContext>> = const { RefCell::new(Vec::new()) };
}

pub fn current_log_context() -> LogContext {
    CONTEXT_STACK.with(|stack| stack.borrow().last().cloned().unwrap_or_default())
}

fn push_exact(value: LogContext) -> LogContextGuard {
    let thread_id = thread::current().id();
    let depth = CONTEXT_STACK.with(|stack| {
        let mut stack = stack.borrow_mut();
        let depth = stack.len();
        stack.push(value.normalized());
        depth
    });
    LogContextGuard {
        depth,
        thread_id,
        active: true,
        _not_send: PhantomData,
    }
}

pub fn enter_log_context(value: LogContext) -> LogContextGuard {
    let merged = merge_log_context(&current_log_context(), &value);
    push_exact(merged)
}

#[must_use = "dropping the guard exits the logging context"]
pub struct LogContextGuard {
    depth: usize,
    thread_id: ThreadId,
    active: bool,
    // A scope cannot be moved to or closed from another thread.
    _not_send: PhantomData<Rc<()>>,
}

impl LogContextGuard {
    pub fn close(mut self) {
        self.restore();
    }

    fn restore(&mut self) {
        if !self.active {
            return;
        }
        debug_assert_eq!(self.thread_id, thread::current().id());
        CONTEXT_STACK.with(|stack| stack.borrow_mut().truncate(self.depth));
        self.active = false;
    }
}

impl Drop for LogContextGuard {
    fn drop(&mut self) {
        self.restore();
    }
}

pub fn with_log_context<T>(value: LogContext, callback: impl FnOnce() -> T) -> T {
    let _scope = enter_log_context(value);
    callback()
}

pub fn capture_log_context() -> LogContext {
    current_log_context()
}

pub fn with_captured_log_context<T>(captured: &LogContext, callback: impl FnOnce() -> T) -> T {
    let _scope = push_exact(captured.clone());
    callback()
}

pub fn update_log_context(
    update: impl FnOnce(LogContext) -> LogContext,
) -> Result<LogContext, LoggerError> {
    CONTEXT_STACK.with(|stack| {
        let mut stack = stack.borrow_mut();
        let current = stack
            .last()
            .cloned()
            .ok_or_else(|| LoggerError("next_loggers: no active log context to update".into()))?;
        let updated = update(current).normalized();
        *stack
            .last_mut()
            .expect("active context disappeared while updating") = updated.clone();
        Ok(updated)
    })
}

pub struct ContextFuture<F> {
    context: LogContext,
    future: Pin<Box<F>>,
}

impl<F> ContextFuture<F> {
    pub fn new(context: LogContext, future: F) -> Self {
        Self {
            context: merge_log_context(&current_log_context(), &context),
            future: Box::pin(future),
        }
    }
}

impl<F: Future> Future for ContextFuture<F> {
    type Output = F::Output;

    fn poll(mut self: Pin<&mut Self>, task: &mut TaskContext<'_>) -> Poll<Self::Output> {
        let context = self.context.clone();
        with_captured_log_context(&context, || self.future.as_mut().poll(task))
    }
}

pub fn with_log_context_async<F: Future>(context: LogContext, future: F) -> ContextFuture<F> {
    ContextFuture::new(context, future)
}

pub fn contextualize_future<F: Future>(context: LogContext, future: F) -> ContextFuture<F> {
    with_log_context_async(context, future)
}

pub fn apply_log_context(event: Event, context: &LogContext) -> Event {
    let context = context.clone().normalized();
    let event = match context.trace_id {
        Some(trace_id) => event.add_trace(trace_id, true),
        None => event,
    };
    let event = context
        .trace_ids
        .into_iter()
        .fold(event, |event, trace_id| event.add_trace(trace_id, false));

    let span_fields = [
        context
            .span_id
            .map(|span_id| ("otel.span_id".into(), Value::String(span_id))),
        Some((
            "otel.trace_flags".into(),
            Value::Number(context.trace_flags.into()),
        )),
        context
            .trace_state
            .map(|trace_state| ("otel.trace_state".into(), Value::String(trace_state))),
        context
            .remote
            .map(|remote| ("otel.remote".into(), Value::Bool(remote))),
        (!context.baggage.is_empty()).then(|| {
            (
                "otel.baggage".into(),
                Value::Object(
                    context
                        .baggage
                        .into_iter()
                        .map(|(key, value)| (key, Value::String(value)))
                        .collect(),
                ),
            )
        }),
    ];
    // Span attributes are appended after the context fields so they win on a
    // key collision, as successive inserts did.
    let fields: JsonObject = context
        .fields
        .into_iter()
        .chain(span_fields.into_iter().flatten())
        .collect();
    let event = event.add_fields(fields);
    let event = if context.logged_in_user.is_empty() {
        event
    } else {
        event.add_logged_in_user_info(context.logged_in_user)
    };
    let event = context.users.into_iter().fold(event, Event::add_user_info);
    let event = match context.routine_id {
        Some(routine_id) => event.add_routine_id(routine_id),
        None => event,
    };
    let event = event.add_tags(std::iter::once("otel".to_string()).chain(context.tags));
    let event = context.context.into_iter().fold(event, Event::add_context);
    context.meta.into_iter().fold(event, Event::add_meta)
}

pub fn apply_current_log_context(event: Event) -> Event {
    apply_log_context(event, &current_log_context())
}

impl Logger {
    pub fn trace_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.trace(values))
    }

    pub fn debug_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.debug(values))
    }

    pub fn info_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.info(values))
    }

    pub fn warn_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.warn(values))
    }

    pub fn error_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.error(values))
    }

    pub fn fatal_context(&self, values: Vec<Value>) -> Event {
        apply_current_log_context(self.fatal(values))
    }
}
