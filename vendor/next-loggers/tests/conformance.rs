use next_loggers::{
    Event, JsonObject, LogLevel, Logger, MemoryTransport, OpenTelemetryLogRecord,
    OpenTelemetryTransport, Options, SupabaseTransport, Transport,
};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

fn object(value: Value) -> JsonObject {
    value.as_object().expect("expected JSON object").clone()
}

#[test]
fn matches_shared_record_fixture() {
    let expected: Value = serde_json::from_str(include_str!(
        "../../../contracts/fixtures/conformance-record.json"
    ))
    .expect("valid shared fixture");

    let transport = Arc::new(MemoryTransport::default());
    let options = Options {
        app_name: "payments".into(),
        name: Some("audit".into()),
        runtime: "contract-test".into(),
        fields: object(json!({"environment": "test"})),
        console: false,
        transports: vec![transport.clone() as Arc<dyn Transport>],
        id_factory: Arc::new(|| "contract-record-1".into()),
        clock: Arc::new(|| "2026-01-02T03:04:05.000Z".into()),
        ..Options::default()
    };
    let logger = Logger::new(options);

    let event = logger
        .error(vec![json!("payment failed"), json!(42)])
        .add_fields(object(json!({"orderId": "order-42"})))
        .add_logged_in_user_id("user-1")
        .add_user_info(object(json!({"id": "user-2"})))
        .add_trace("trace-1", false)
        .add_trace("trace-2", false)
        .add_routine_id("charge-card")
        .add_tags(["payments", "critical", "payments"])
        .add_context(json!({"attempt": 2}))
        .add_meta(json!({"source": "fixture"}));

    let first = event.send().expect("first send").expect("record");
    let second = event.send().expect("second send").expect("cached record");
    assert_eq!(first, second);
    assert_eq!(transport.records().len(), 1);
    assert_eq!(
        serde_json::to_value(&transport.records()[0]).unwrap(),
        expected
    );
    logger.close().expect("close logger");
}

#[test]
fn shutdown_recovers_unsent_events() {
    let transport = Arc::new(MemoryTransport::default());
    let logger = Logger::new(Options::default().with_transport(transport.clone()));
    // Intentionally unsent: shutdown must recover the event.
    // ores-lint-disable-next-line require-send
    let _ = logger.warn(vec![json!("created but not explicitly sent")]);
    logger.close().expect("close logger");

    assert_eq!(transport.records().len(), 1);
    assert_eq!(
        transport.records()[0].message,
        "created but not explicitly sent"
    );
    assert_eq!(transport.exit_records().len(), 1);
    assert!(transport.is_closed());
}

trait AuditEventExt {
    fn with_actor(self, actor: &str) -> Self;
}

impl AuditEventExt for Event {
    fn with_actor(self, actor: &str) -> Self {
        self.add_fields(object(json!({ "actor": actor })))
    }
}

#[test]
fn levels_send_false_and_extension_traits_work() {
    let transport = Arc::new(MemoryTransport::default());
    let options = Options {
        max_level: LogLevel::Warn,
        console: false,
        transports: vec![transport.clone() as Arc<dyn Transport>],
        ..Options::default()
    };
    let logger = Logger::new(options);

    logger.info(vec![json!("filtered")]).send().unwrap();
    logger
        .error(vec![json!("local")])
        .with_actor("user-9")
        .send_with_store(false)
        .unwrap();
    logger
        .fatal(vec![json!("stored")])
        .with_actor("user-9")
        .send()
        .unwrap();

    assert_eq!(transport.records().len(), 1);
    assert_eq!(transport.records()[0].level, LogLevel::Fatal);
    assert_eq!(transport.records()[0].fields["actor"], json!("user-9"));
}

#[test]
fn explicit_opentelemetry_and_supabase_transports_work() {
    let otel = Arc::new(Mutex::new(Vec::<OpenTelemetryLogRecord>::new()));
    let supabase = Arc::new(Mutex::new(Vec::new()));
    let otel_sink = otel.clone();
    let supabase_sink = supabase.clone();

    let options = Options {
        app_name: "checkout".into(),
        runtime: "rust".into(),
        console: false,
        id_factory: Arc::new(|| "otel-record-1".into()),
        clock: Arc::new(|| "2026-01-02T03:04:05.000Z".into()),
        transports: vec![
            Arc::new(OpenTelemetryTransport::new(move |record| {
                otel_sink.lock().unwrap().push(record);
                Ok(())
            })) as Arc<dyn Transport>,
            Arc::new(SupabaseTransport::new(move |record| {
                supabase_sink.lock().unwrap().push(record);
                Ok(())
            })) as Arc<dyn Transport>,
        ],
        ..Options::default()
    };
    let logger = Logger::new(options);

    logger
        .error(vec![json!("payment failed")])
        .add_trace("0123456789abcdef0123456789abcdef", false)
        .add_fields(object(json!({
            "otel.span_id": "0123456789abcdef",
            "region": "us-east-1"
        })))
        .send()
        .expect("send through OTEL and Supabase");

    let otel = otel.lock().unwrap();
    assert_eq!(otel.len(), 1);
    assert_eq!(otel[0].severity_text, "ERROR");
    assert_eq!(otel[0].severity_number, 17);
    assert_eq!(
        otel[0].attributes["trace.id"],
        json!("0123456789abcdef0123456789abcdef")
    );
    assert_eq!(otel[0].attributes["service.name"], json!("checkout"));
    drop(otel);

    let supabase = supabase.lock().unwrap();
    assert_eq!(supabase.len(), 1);
    assert_eq!(supabase[0].schema, "next-loggers/v1");
    assert_eq!(supabase[0].message, "payment failed");
}

#[test]
fn per_event_otel_routing_preserves_normal_logging() {
    let memory = Arc::new(MemoryTransport::default());
    let otel = Arc::new(Mutex::new(Vec::<OpenTelemetryLogRecord>::new()));
    let sink = otel.clone();
    let logger = Logger::new(Options {
        console: false,
        transports: vec![
            memory.clone() as Arc<dyn Transport>,
            Arc::new(OpenTelemetryTransport::new(move |record| {
                sink.lock().unwrap().push(record);
                Ok(())
            })) as Arc<dyn Transport>,
        ],
        ..Options::default()
    });

    logger.info(vec![json!("default")]).send().unwrap();
    logger
        .info(vec![json!("ordinary-only")])
        .not_otel()
        .send()
        .unwrap();
    logger.not_otel();
    logger.info(vec![json!("logger-off")]).send().unwrap();
    logger
        .info(vec![json!("override")])
        .use_otel()
        .send()
        .unwrap();

    assert_eq!(memory.records().len(), 4);
    let otel = otel.lock().unwrap();
    assert_eq!(
        otel.iter()
            .map(|record| record.body.as_str())
            .collect::<Vec<_>>(),
        vec!["default", "override"]
    );
}
