#![forbid(unsafe_code)]

use std::sync::Arc;

use axum::{Json, Router, routing::get};
use next_loggers::{Logger, LoggerError, OpenTelemetryTransport, Options};
use serde_json::{Value, json};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    const ROUTINE_ID: &str = "ores-routine-bmDU-xSeYi6koydiUSsmN";
    let log = logger();
    let listener = TcpListener::bind("127.0.0.1:8080").await?;

    let _ = log
        .info(vec!["ores forms read server started".into()])
        .add_trace("ores-trace-X9L4qBdWaiF4V23TS52IY", false)
        .add_routine_id(ROUTINE_ID)
        .send();

    let app = Router::new()
        .route("/healthz", get(health))
        .merge(ores_forms_web_server::router(Default::default()));
    axum::serve(listener, app).await?;
    let _ = log.close();
    Ok(())
}

async fn health() -> Json<Value> {
    Json(json!({"ok": true, "service": "ores-forms-web-server", "mode": "read-only"}))
}

fn logger() -> Logger {
    let transport = Arc::new(OpenTelemetryTransport::new(|record| {
        let encoded = serde_json::to_string(&record)
            .map_err(|error| LoggerError(format!("cannot encode log record: {error}")))?;
        eprintln!("{encoded}");
        Ok(())
    }));
    let mut options = Options::default().with_transport(transport);
    options.console = false;
    options.app_name = "ores-forms-web-server".into();
    Logger::new(options)
}
