//! Shell-free, opt-in container entrypoint. All options belong to the child.

fn main() -> std::process::ExitCode {
    #[cfg(unix)]
    {
        next_loggers::launcher::run(std::env::args_os().skip(1).collect())
    }
    #[cfg(not(unix))]
    {
        // No spawn/wait fallback: it would violate the process-replacement contract.
        std::process::ExitCode::from(69)
    }
}
