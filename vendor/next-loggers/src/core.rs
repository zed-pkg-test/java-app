//! Rust implementation of the language-neutral `next-loggers/v1` contract.

use serde::{Deserialize, Serialize};
pub use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

pub const SCHEMA: &str = "next-loggers/v1";
pub type JsonObject = Map<String, Value>;

#[derive(Clone, Copy, Debug, Deserialize, Eq, Hash, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
}

impl LogLevel {
    fn index(self) -> u8 {
        match self {
            Self::Trace => 0,
            Self::Debug => 1,
            Self::Info => 2,
            Self::Warn => 3,
            Self::Error => 4,
            Self::Fatal => 5,
        }
    }

    pub fn otel_severity_number(self) -> u8 {
        match self {
            Self::Trace => 1,
            Self::Debug => 5,
            Self::Info => 9,
            Self::Warn => 13,
            Self::Error => 17,
            Self::Fatal => 21,
        }
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct LogRecord {
    pub schema: String,
    pub id: String,
    pub timestamp: String,
    pub level: LogLevel,
    pub runtime: String,
    #[serde(rename = "appName")]
    pub app_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub message: String,
    pub values: Vec<Value>,
    pub fields: JsonObject,
    #[serde(rename = "loggedInUser", skip_serializing_if = "Option::is_none")]
    pub logged_in_user: Option<JsonObject>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub users: Vec<JsonObject>,
    #[serde(rename = "traceId", skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<String>,
    #[serde(rename = "traceIds", default, skip_serializing_if = "Vec::is_empty")]
    pub trace_ids: Vec<String>,
    #[serde(rename = "routineId", skip_serializing_if = "Option::is_none")]
    pub routine_id: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub context: Vec<Value>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub meta: Vec<Value>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<Value>,
    #[serde(rename = "stackTrace", default, skip_serializing_if = "Vec::is_empty")]
    pub stack_trace: Vec<String>,
}

impl LogRecord {
    pub fn to_json(&self) -> Result<String, LoggerError> {
        serde_json::to_string(self).map_err(LoggerError::from)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LoggerError(pub String);

impl Display for LoggerError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl Error for LoggerError {}

impl From<serde_json::Error> for LoggerError {
    fn from(error: serde_json::Error) -> Self {
        Self(error.to_string())
    }
}

pub trait Transport: Send + Sync {
    fn write(&self, record: &LogRecord) -> Result<(), LoggerError>;

    fn is_open_telemetry(&self) -> bool {
        false
    }

    fn flush(&self) -> Result<(), LoggerError> {
        Ok(())
    }

    fn flush_on_exit(&self, _records: &[LogRecord]) -> Result<(), LoggerError> {
        Ok(())
    }

    fn close(&self) -> Result<(), LoggerError> {
        Ok(())
    }
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct OpenTelemetryLogRecord {
    pub body: String,
    #[serde(rename = "severityText")]
    pub severity_text: String,
    #[serde(rename = "severityNumber")]
    pub severity_number: u8,
    pub timestamp: String,
    pub attributes: JsonObject,
}

/// Dependency-free adapter for an application-owned OpenTelemetry logger.
/// It installs no global provider, context manager, or instrumentation.
pub struct OpenTelemetryTransport {
    emit: Arc<dyn Fn(OpenTelemetryLogRecord) -> Result<(), LoggerError> + Send + Sync>,
}

impl OpenTelemetryTransport {
    pub fn new<F>(emit: F) -> Self
    where
        F: Fn(OpenTelemetryLogRecord) -> Result<(), LoggerError> + Send + Sync + 'static,
    {
        Self {
            emit: Arc::new(emit),
        }
    }
}

impl Transport for OpenTelemetryTransport {
    fn is_open_telemetry(&self) -> bool {
        true
    }

    fn write(&self, record: &LogRecord) -> Result<(), LoggerError> {
        (self.emit)(OpenTelemetryLogRecord {
            body: record.message.clone(),
            severity_text: format!("{:?}", record.level).to_uppercase(),
            severity_number: record.level.otel_severity_number(),
            timestamp: record.timestamp.clone(),
            attributes: otel_attributes(record),
        })
    }
}

/// The OpenTelemetry attribute map for a record, assembled from its parts:
/// the fixed identity attributes, the optional trace id, then every record
/// field under the `next_logger.field.` prefix (later entries win on a key
/// collision, exactly as successive inserts did).
fn otel_attributes(record: &LogRecord) -> JsonObject {
    let identity = [
        (
            "service.name".to_string(),
            Value::String(record.app_name.clone()),
        ),
        (
            "next_logger.schema".to_string(),
            Value::String(record.schema.clone()),
        ),
        (
            "next_logger.runtime".to_string(),
            Value::String(record.runtime.clone()),
        ),
        (
            "log.record.uid".to_string(),
            Value::String(record.id.clone()),
        ),
    ];
    let trace = record
        .trace_id
        .as_ref()
        .map(|trace_id| ("trace.id".to_string(), Value::String(trace_id.clone())));
    let fields = record
        .fields
        .iter()
        .map(|(key, value)| (format!("next_logger.field.{key}"), value.clone()));
    identity.into_iter().chain(trace).chain(fields).collect()
}

/// Adapter for an application-owned authenticated Supabase sender.
pub struct SupabaseTransport {
    send: Arc<dyn Fn(LogRecord) -> Result<(), LoggerError> + Send + Sync>,
}

impl SupabaseTransport {
    pub fn new<F>(send: F) -> Self
    where
        F: Fn(LogRecord) -> Result<(), LoggerError> + Send + Sync + 'static,
    {
        Self {
            send: Arc::new(send),
        }
    }
}

impl Transport for SupabaseTransport {
    fn write(&self, record: &LogRecord) -> Result<(), LoggerError> {
        (self.send)(record.clone())
    }
}

#[derive(Clone, Debug, Default)]
struct MemoryState {
    records: Vec<LogRecord>,
    exit_records: Vec<LogRecord>,
    flush_count: usize,
    closed: bool,
}

#[derive(Debug, Default)]
pub struct MemoryTransport {
    state: Mutex<MemoryState>,
}

impl MemoryTransport {
    pub fn records(&self) -> Vec<LogRecord> {
        self.state
            .lock()
            .expect("memory transport poisoned")
            .records
            .clone()
    }

    pub fn exit_records(&self) -> Vec<LogRecord> {
        self.state
            .lock()
            .expect("memory transport poisoned")
            .exit_records
            .clone()
    }

    pub fn flush_count(&self) -> usize {
        self.state
            .lock()
            .expect("memory transport poisoned")
            .flush_count
    }

    pub fn is_closed(&self) -> bool {
        self.state.lock().expect("memory transport poisoned").closed
    }
}

impl Transport for MemoryTransport {
    fn write(&self, record: &LogRecord) -> Result<(), LoggerError> {
        let mut state = self
            .state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?;
        if state.closed {
            return Err(LoggerError("transport is closed".into()));
        }
        state.records.push(record.clone());
        Ok(())
    }

    fn flush(&self) -> Result<(), LoggerError> {
        self.state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .flush_count += 1;
        Ok(())
    }

    fn flush_on_exit(&self, records: &[LogRecord]) -> Result<(), LoggerError> {
        self.state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .exit_records
            .extend_from_slice(records);
        Ok(())
    }

    fn close(&self) -> Result<(), LoggerError> {
        self.state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .closed = true;
        Ok(())
    }
}

pub struct Options {
    pub app_name: String,
    pub name: Option<String>,
    pub runtime: String,
    pub max_level: LogLevel,
    pub fields: JsonObject,
    pub logged_in_user: JsonObject,
    pub transports: Vec<Arc<dyn Transport>>,
    pub console: bool,
    pub otel_enabled: bool,
    pub id_factory: Arc<dyn Fn() -> String + Send + Sync>,
    pub clock: Arc<dyn Fn() -> String + Send + Sync>,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            app_name: "app".into(),
            name: None,
            runtime: "rust".into(),
            max_level: LogLevel::Info,
            fields: JsonObject::new(),
            logged_in_user: JsonObject::new(),
            transports: Vec::new(),
            console: true,
            otel_enabled: true,
            id_factory: Arc::new(default_id),
            clock: Arc::new(default_clock),
        }
    }
}

impl Options {
    /// Return new options with `transport` appended; the receiver is consumed,
    /// never edited in place.
    pub fn with_transport<T: Transport + 'static>(self, transport: Arc<T>) -> Self {
        let transport: Arc<dyn Transport> = transport;
        Self {
            transports: self.transports.into_iter().chain([transport]).collect(),
            ..self
        }
    }
}

fn default_id() -> String {
    static NEXT_ID: AtomicU64 = AtomicU64::new(1);
    let sequence = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    format!("rust-{}-{sequence}", current_unix_timestamp_nanos())
}

fn default_clock() -> String {
    let (seconds, nanoseconds) = current_unix_time_parts();
    format_rfc3339_utc(seconds, nanoseconds)
}

fn current_unix_timestamp_nanos() -> i128 {
    let (seconds, nanoseconds) = current_unix_time_parts();
    i128::from(seconds) * 1_000_000_000 + i128::from(nanoseconds)
}

fn current_unix_time_parts() -> (i64, u32) {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => (
            i64::try_from(duration.as_secs()).unwrap_or(i64::MAX),
            duration.subsec_nanos(),
        ),
        Err(error) => {
            let duration = error.duration();
            let seconds = i64::try_from(duration.as_secs()).unwrap_or(i64::MAX);
            if duration.subsec_nanos() == 0 {
                (-seconds, 0)
            } else {
                (
                    -seconds.saturating_add(1),
                    1_000_000_000 - duration.subsec_nanos(),
                )
            }
        }
    }
}

fn format_rfc3339_utc(seconds: i64, nanoseconds: u32) -> String {
    debug_assert!(nanoseconds < 1_000_000_000);

    let days = seconds.div_euclid(86_400);
    let seconds_in_day = seconds.rem_euclid(86_400);
    let (year, month, day) = civil_date_from_unix_days(days);
    let hour = seconds_in_day / 3_600;
    let minute = (seconds_in_day % 3_600) / 60;
    let second = seconds_in_day % 60;

    let fractional = if nanoseconds == 0 {
        String::new()
    } else {
        format!(".{nanoseconds:09}")
            .trim_end_matches('0')
            .to_owned()
    };

    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}{fractional}Z")
}

// Howard Hinnant's civil-from-days algorithm, with day zero at 1970-01-01.
fn civil_date_from_unix_days(days: i64) -> (i64, i64, i64) {
    let shifted = days + 719_468;
    let era = if shifted >= 0 {
        shifted
    } else {
        shifted - 146_096
    } / 146_097;
    let day_of_era = shifted - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let mut year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_prime + 2) / 5 + 1;
    let month = month_prime + if month_prime < 10 { 3 } else { -9 };
    year += i64::from(month <= 2);
    (year, month, day)
}

#[derive(Clone)]
pub struct Logger {
    inner: Arc<LoggerInner>,
}

struct LoggerInner {
    app_name: String,
    name: Option<String>,
    runtime: String,
    max_level: LogLevel,
    fields: Mutex<JsonObject>,
    current_user: Mutex<JsonObject>,
    transports: Vec<Arc<dyn Transport>>,
    console: bool,
    id_factory: Arc<dyn Fn() -> String + Send + Sync>,
    clock: Arc<dyn Fn() -> String + Send + Sync>,
    unsent: Mutex<HashMap<u64, Arc<Mutex<EventState>>>>,
    next_event: AtomicU64,
    closed: AtomicBool,
    otel_enabled: AtomicBool,
}

impl Logger {
    pub fn new(options: Options) -> Self {
        Self {
            inner: Arc::new(LoggerInner {
                app_name: options.app_name,
                name: options.name,
                runtime: options.runtime,
                max_level: options.max_level,
                fields: Mutex::new(options.fields),
                current_user: Mutex::new(options.logged_in_user),
                transports: options.transports,
                console: options.console,
                id_factory: options.id_factory,
                clock: options.clock,
                unsent: Mutex::new(HashMap::new()),
                next_event: AtomicU64::new(1),
                closed: AtomicBool::new(false),
                otel_enabled: AtomicBool::new(options.otel_enabled),
            }),
        }
    }

    fn create_event(&self, level: LogLevel, values: Vec<Value>) -> Event {
        assert!(
            !self.inner.closed.load(Ordering::Acquire),
            "next_loggers: logger is closed"
        );
        let id = self.inner.next_event.fetch_add(1, Ordering::Relaxed);
        let state = Arc::new(Mutex::new(EventState::new(level, values)));
        self.inner
            .unsent
            .lock()
            .expect("unsent event registry poisoned")
            .insert(id, state.clone());
        Event {
            logger: self.clone(),
            id,
            state,
        }
    }

    pub fn trace(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Trace, values)
    }

    pub fn debug(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Debug, values)
    }

    pub fn info(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Info, values)
    }

    pub fn log(&self, values: Vec<Value>) -> Event {
        self.info(values)
    }

    pub fn warn(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Warn, values)
    }

    pub fn error(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Error, values)
    }

    pub fn fatal(&self, values: Vec<Value>) -> Event {
        self.create_event(LogLevel::Fatal, values)
    }

    pub fn add_fields(&self, fields: JsonObject) -> &Self {
        self.inner
            .fields
            .lock()
            .expect("logger fields poisoned")
            .extend(fields);
        self
    }

    pub fn set_current_user(&self, user: JsonObject) -> &Self {
        self.inner
            .current_user
            .lock()
            .expect("current user poisoned")
            .extend(user);
        self
    }

    pub fn use_otel(&self) -> &Self {
        self.inner.otel_enabled.store(true, Ordering::Release);
        self
    }

    pub fn not_otel(&self) -> &Self {
        self.inner.otel_enabled.store(false, Ordering::Release);
        self
    }

    fn emit(&self, event: &Event, store: bool) -> Result<Option<LogRecord>, LoggerError> {
        self.inner
            .unsent
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .remove(&event.id);
        let level = event
            .state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .level;
        if level.index() < self.inner.max_level.index() {
            return Ok(None);
        }
        let record = event.to_record()?;
        if self.inner.console {
            println!(
                "[{}] [{:?}] [{}] {}",
                record.timestamp, record.level, record.app_name, record.message
            );
        }
        if store {
            let default_otel = self.inner.otel_enabled.load(Ordering::Acquire);
            let event_otel = event.is_otel_enabled(default_otel)?;
            self.inner
                .transports
                .iter()
                .filter(|transport| event_otel || !transport.is_open_telemetry())
                .try_for_each(|transport| transport.write(&record))?;
        }
        Ok(Some(record))
    }

    pub fn flush(&self, send_unsent: bool) -> Result<(), LoggerError> {
        if send_unsent {
            self.unsent_events()?
                .iter()
                .try_for_each(|event| event.send().map(|_| ()))?;
        }
        self.inner
            .transports
            .iter()
            .try_for_each(|transport| transport.flush())
    }

    pub fn flush_on_exit(&self) -> Result<(), LoggerError> {
        // Sending is lazy and stops at the first error, exactly like the
        // early-returning loop it replaces; events that were filtered by level
        // (`Ok(None)`) contribute nothing to the recovered records.
        let recovered = self
            .unsent_events()?
            .iter()
            .filter_map(|event| event.send().transpose())
            .collect::<Result<Vec<_>, _>>()?;
        self.inner
            .transports
            .iter()
            .try_for_each(|transport| transport.flush_on_exit(&recovered))?;
        self.flush(false)
    }

    pub fn close(&self) -> Result<(), LoggerError> {
        if self.inner.closed.load(Ordering::Acquire) {
            return Ok(());
        }
        self.flush_on_exit()?;
        self.inner
            .transports
            .iter()
            .try_for_each(|transport| transport.close())?;
        self.inner.closed.store(true, Ordering::Release);
        Ok(())
    }

    fn unsent_events(&self) -> Result<Vec<Event>, LoggerError> {
        let unsent = self
            .inner
            .unsent
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?;
        Ok(unsent
            .iter()
            .map(|(id, state)| Event {
                logger: self.clone(),
                id: *id,
                state: state.clone(),
            })
            .collect())
    }
}

struct EventState {
    level: LogLevel,
    values: Vec<Value>,
    fields: JsonObject,
    logged_in_user: JsonObject,
    users: Vec<JsonObject>,
    trace_id: Option<String>,
    trace_ids: Vec<String>,
    routine_id: Option<String>,
    tags: Vec<String>,
    context: Vec<Value>,
    meta: Vec<Value>,
    errors: Vec<Value>,
    stack_trace: Vec<String>,
    sent: bool,
    record: Option<LogRecord>,
    otel_enabled: Option<bool>,
}

impl EventState {
    fn new(level: LogLevel, values: Vec<Value>) -> Self {
        Self {
            level,
            values,
            fields: JsonObject::new(),
            logged_in_user: JsonObject::new(),
            users: Vec::new(),
            trace_id: None,
            trace_ids: Vec::new(),
            routine_id: None,
            tags: Vec::new(),
            context: Vec::new(),
            meta: Vec::new(),
            errors: Vec::new(),
            stack_trace: Vec::new(),
            sent: false,
            record: None,
            otel_enabled: None,
        }
    }
}

#[must_use = "call .send() or .send_with_store(store) to deliver this log event"]
#[derive(Clone)]
pub struct Event {
    logger: Logger,
    id: u64,
    state: Arc<Mutex<EventState>>,
}

/// `values` with `value` appended unless it is already present. Ownership
/// moves through the call, so the list is a new value and no `&mut` is needed.
fn with_unique(values: Vec<String>, value: String) -> Vec<String> {
    if values.contains(&value) {
        values
    } else {
        values.into_iter().chain([value]).collect()
    }
}

// `EventState` lives behind an `Arc<Mutex<_>>` because the logger's unsent
// registry shares it with every clone of the `Event` handle: it is the one
// stateful holder for a pending event. Builder methods below therefore swap
// whole field values under the lock (`mem::take` out, new value in) instead of
// editing the lists element by element; the record that leaves `to_record` is
// an immutable `LogRecord` value.
impl Event {
    pub fn add_fields(self, fields: JsonObject) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .fields
            .extend(fields);
        self
    }

    pub fn with_otel(self, enabled: bool) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .otel_enabled = Some(enabled);
        self
    }

    pub fn use_otel(self) -> Self {
        self.with_otel(true)
    }

    pub fn not_otel(self) -> Self {
        self.with_otel(false)
    }

    pub fn reset_otel(self) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .otel_enabled = None;
        self
    }

    pub fn is_otel_enabled(&self, fallback: bool) -> Result<bool, LoggerError> {
        Ok(self
            .state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .otel_enabled
            .unwrap_or(fallback))
    }

    pub fn add_trace(self, trace_id: impl Into<String>, make_first: bool) -> Self {
        let value = trace_id.into().trim().to_string();
        if value.is_empty() {
            return self;
        }
        let mut state = self.state.lock().expect("event state poisoned");
        if state.trace_id.is_none() || make_first {
            state.trace_id = Some(value.clone());
        }
        state.trace_ids = with_unique(std::mem::take(&mut state.trace_ids), value);
        drop(state);
        self
    }

    pub fn add_trace_id(self, trace_id: impl Into<String>, make_first: bool) -> Self {
        self.add_trace(trace_id, make_first)
    }

    pub fn add_routine_id(self, routine_id: impl Into<String>) -> Self {
        self.state.lock().expect("event state poisoned").routine_id = Some(routine_id.into());
        self
    }

    pub fn add_tags<I, S>(self, tags: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        let cleaned = tags
            .into_iter()
            .map(|tag| tag.into().trim().to_string())
            .filter(|value| !value.is_empty());
        let mut state = self.state.lock().expect("event state poisoned");
        state.tags = cleaned.fold(std::mem::take(&mut state.tags), with_unique);
        drop(state);
        self
    }

    pub fn add_context(self, value: Value) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .context
            .push(value);
        self
    }

    pub fn add_meta(self, value: Value) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .meta
            .push(value);
        self
    }

    pub fn add_error(self, value: Value) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .errors
            .push(value);
        self
    }

    pub fn add_logged_in_user_info(self, user: JsonObject) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .logged_in_user
            .extend(user);
        self
    }

    pub fn add_logged_in_user_id(self, id: impl Into<String>) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .logged_in_user
            .insert("id".into(), Value::String(id.into()));
        self
    }

    pub fn add_user_info(self, user: JsonObject) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .users
            .push(user);
        self
    }

    pub fn add_stack_trace(self, line: impl Into<String>) -> Self {
        self.state
            .lock()
            .expect("event state poisoned")
            .stack_trace
            .push(line.into());
        self
    }

    pub fn to_record(&self) -> Result<LogRecord, LoggerError> {
        let mut state = self
            .state
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?;
        if let Some(record) = &state.record {
            return Ok(record.clone());
        }
        // Logger-level maps are snapshotted under their own locks and merged
        // with the event-level maps into new values; event entries win.
        let fields: JsonObject = self
            .logger
            .inner
            .fields
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .clone()
            .into_iter()
            .chain(state.fields.clone())
            .collect();
        let user: JsonObject = self
            .logger
            .inner
            .current_user
            .lock()
            .map_err(|error| LoggerError(error.to_string()))?
            .clone()
            .into_iter()
            .chain(state.logged_in_user.clone())
            .collect();
        let message = state
            .values
            .iter()
            .map(|value| match value {
                Value::String(text) => text.clone(),
                other => other.to_string(),
            })
            .collect::<Vec<_>>()
            .join(" ");
        let record = LogRecord {
            schema: SCHEMA.into(),
            id: (self.logger.inner.id_factory)(),
            timestamp: (self.logger.inner.clock)(),
            level: state.level,
            runtime: self.logger.inner.runtime.clone(),
            app_name: self.logger.inner.app_name.clone(),
            name: self.logger.inner.name.clone(),
            message,
            values: state.values.clone(),
            fields,
            logged_in_user: (!user.is_empty()).then_some(user),
            users: state.users.clone(),
            trace_id: state.trace_id.clone(),
            trace_ids: state.trace_ids.clone(),
            routine_id: state.routine_id.clone(),
            tags: state.tags.clone(),
            context: state.context.clone(),
            meta: state.meta.clone(),
            errors: state.errors.clone(),
            stack_trace: state.stack_trace.clone(),
        };
        state.record = Some(record.clone());
        Ok(record)
    }

    pub fn send(&self) -> Result<Option<LogRecord>, LoggerError> {
        self.send_with_store(true)
    }

    pub fn send_with_store(&self, store: bool) -> Result<Option<LogRecord>, LoggerError> {
        {
            let mut state = self
                .state
                .lock()
                .map_err(|error| LoggerError(error.to_string()))?;
            if state.sent {
                return Ok(state.record.clone());
            }
            state.sent = true;
        }
        self.logger.emit(self, store)
    }
}

#[cfg(test)]
mod clock_tests {
    use super::{civil_date_from_unix_days, format_rfc3339_utc};

    #[test]
    fn formats_epoch_and_pre_epoch_timestamps() {
        assert_eq!(format_rfc3339_utc(0, 0), "1970-01-01T00:00:00Z");
        assert_eq!(format_rfc3339_utc(-1, 0), "1969-12-31T23:59:59Z");
    }

    #[test]
    fn formats_leap_days_and_trims_fractional_zeroes() {
        assert_eq!(
            format_rfc3339_utc(951_782_400, 123_400_000),
            "2000-02-29T00:00:00.1234Z"
        );
        assert_eq!(civil_date_from_unix_days(19_782), (2024, 2, 29));
    }
}
