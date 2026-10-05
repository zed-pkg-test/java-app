use next_loggers::{
    apply_log_context, current_log_context, json, with_log_context, with_log_context_async,
    JsonObject, LogContext, LogLevel, MemoryTransport, Options,
};
use std::future::Future;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll, Waker};
use std::thread;

fn object(entries: &[(&str, serde_json::Value)]) -> JsonObject {
    entries
        .iter()
        .map(|(key, value)| ((*key).to_string(), value.clone()))
        .collect()
}

#[test]
fn nested_context_merges_applies_and_restores_after_panic() {
    let transport = Arc::new(MemoryTransport::default());
    let logger = next_loggers::Logger::new(
        Options {
            app_name: "rust-context".into(),
            max_level: LogLevel::Debug,
            console: false,
            ..Options::default()
        }
        .with_transport(transport.clone()),
    );
    let outer = LogContext {
        logged_in_user: object(&[("id", json!("user-1")), ("role", json!("viewer"))]),
        users: vec![object(&[("id", json!("outer"))])],
        fields: object(&[("request", json!("outer")), ("keep", json!(true))]),
        trace_id: Some("trace-outer".into()),
        trace_ids: vec!["trace-outer".into()],
        tags: vec!["outer".into()],
        ..LogContext::default()
    };
    let inner = LogContext {
        logged_in_user: object(&[("role", json!("admin"))]),
        users: vec![object(&[("id", json!("inner"))])],
        fields: object(&[("request", json!("inner"))]),
        trace_id: Some("trace-inner".into()),
        trace_ids: vec!["trace-outer".into(), "trace-inner".into()],
        span_id: Some("span-1".into()),
        trace_flags: 1,
        trace_state: Some("vendor=value".into()),
        routine_id: Some("checkout".into()),
        tags: vec!["inner".into(), "outer".into()],
        ..LogContext::default()
    };

    let panic = catch_unwind(AssertUnwindSafe(|| {
        with_log_context(outer.clone(), || {
            with_log_context(inner.clone(), || {
                apply_log_context(logger.info(vec![json!("inside")]), &current_log_context())
                    .send()
                    .unwrap();
                assert_eq!(
                    current_log_context().trace_id.as_deref(),
                    Some("trace-inner")
                );
                panic!("restore me");
            });
        });
    }));
    assert!(panic.is_err());
    assert_eq!(current_log_context(), LogContext::default());

    let records = transport.records();
    assert_eq!(records.len(), 1);
    let record = &records[0];
    assert_eq!(record.trace_id.as_deref(), Some("trace-inner"));
    assert_eq!(record.fields["otel.span_id"], json!("span-1"));
    assert_eq!(record.fields["request"], json!("inner"));
    assert_eq!(record.fields["keep"], json!(true));
    assert_eq!(
        record.logged_in_user.as_ref().unwrap()["role"],
        json!("admin")
    );
    assert_eq!(record.users.len(), 2);
    assert_eq!(record.routine_id.as_deref(), Some("checkout"));
    assert_eq!(record.tags, vec!["otel", "outer", "inner"]);
}

#[test]
fn thread_local_context_is_isolated() {
    let handles: Vec<_> = (0..64)
        .map(|index| {
            thread::spawn(move || {
                let expected = format!("trace-{index}");
                with_log_context(
                    LogContext {
                        trace_id: Some(expected.clone()),
                        fields: object(&[("index", json!(index))]),
                        ..LogContext::default()
                    },
                    || {
                        let context = current_log_context();
                        assert_eq!(context.trace_id.as_deref(), Some(expected.as_str()));
                        assert_eq!(context.fields["index"], json!(index));
                    },
                );
                assert_eq!(current_log_context(), LogContext::default());
            })
        })
        .collect();
    for handle in handles {
        handle.join().unwrap();
    }
}

fn poll_to_completion<F: Future>(future: F) -> F::Output {
    let mut task = Context::from_waker(Waker::noop());
    let mut future = Box::pin(future);
    loop {
        match future.as_mut().poll(&mut task) {
            Poll::Ready(value) => return value,
            Poll::Pending => thread::yield_now(),
        }
    }
}

struct ObserveAfterYield(bool);
impl Future for ObserveAfterYield {
    type Output = LogContext;

    fn poll(mut self: Pin<&mut Self>, task: &mut Context<'_>) -> Poll<Self::Output> {
        if !self.0 {
            self.0 = true;
            task.waker().wake_by_ref();
            Poll::Pending
        } else {
            Poll::Ready(current_log_context())
        }
    }
}

#[test]
fn task_context_is_installed_for_every_poll() {
    let observed = poll_to_completion(with_log_context_async(
        LogContext {
            trace_id: Some("trace-task".into()),
            fields: object(&[("task", json!(true))]),
            ..LogContext::default()
        },
        ObserveAfterYield(false),
    ));
    assert_eq!(observed.trace_id.as_deref(), Some("trace-task"));
    assert_eq!(observed.fields["task"], json!(true));
    assert_eq!(current_log_context(), LogContext::default());
}
