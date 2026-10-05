use r2g_subject::{json, Logger, MemoryTransport, Options, SCHEMA};
use std::sync::Arc;

fn main() {
    let transport = Arc::new(MemoryTransport::default());
    let mut options = Options::default().with_transport(transport.clone());
    options.app_name = "r2g-rust".into();
    options.console = false;
    let logger = Logger::new(options);

    logger
        .info(vec![json!("installed crate")])
        .add_tags(["r2g", "rust"])
        .send()
        .expect("send installed-crate record");
    logger.close().expect("close logger");

    assert_eq!(SCHEMA, "next-loggers/v1");
    assert_eq!(transport.records().len(), 1);
    assert_eq!(transport.records()[0].message, "installed crate");
    println!("rust downstream consumer passed");
}
