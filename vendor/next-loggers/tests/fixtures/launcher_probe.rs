// Standalone test executable; never included in production runtime images.
use std::io::Write;
use std::os::unix::ffi::OsStrExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn terminate(_: i32) {
    STOP.store(true, Ordering::Relaxed);
}

unsafe extern "C" {
    fn signal(number: i32, handler: extern "C" fn(i32)) -> usize;
    fn getuid() -> u32;
    fn getgid() -> u32;
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn main() {
    let waiting = std::env::var("ORES_LAUNCHER_TEST_MODE").as_deref() == Ok("wait");
    if waiting {
        // Install the application's handler, including when it becomes PID 1.
        assert_ne!(unsafe { signal(15, terminate) }, usize::MAX);
    }
    println!("pid={}", std::process::id());
    println!("uid={}", unsafe { getuid() });
    println!("gid={}", unsafe { getgid() });
    println!("cwd={}", hex(std::env::current_dir().unwrap().as_os_str().as_bytes()));
    println!("shell={}", std::path::Path::new("/bin/sh").exists());
    let inherited = std::env::var_os("ORES_LAUNCHER_TEST_VALUE").unwrap_or_default();
    println!("env={}", hex(inherited.as_bytes()));
    for (index, argument) in std::env::args_os().skip(1).enumerate() {
        println!("arg{index}={}", hex(argument.as_bytes()));
    }
    println!("ready");
    std::io::stdout().flush().unwrap();
    if waiting {
        while !STOP.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(10));
        }
        std::process::exit(42);
    }
    let code = std::env::var("ORES_LAUNCHER_TEST_EXIT")
        .ok()
        .map(|value| value.parse::<i32>().unwrap())
        .unwrap_or(0);
    std::process::exit(code);
}
