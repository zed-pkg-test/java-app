//! Direct execve with explicit PATH lookup: never execvp's ENOEXEC shell fallback.

use std::ffi::{CString, OsStr, OsString};
use std::io;
use std::mem::MaybeUninit;
use std::os::unix::ffi::OsStrExt;
use std::path::PathBuf;
use std::ptr;

pub(super) struct Failure {
    pub error: io::Error,
    // If restoring SIGPIPE failed, another stderr write could terminate us.
    // Preserve the execution error's exit code rather than risk that write.
    pub can_log: bool,
}

fn c_string(bytes: &[u8]) -> io::Result<CString> {
    CString::new(bytes)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "NUL in execution data"))
}

fn candidates(program: &OsStr, path: Option<OsString>) -> Vec<PathBuf> {
    if program.as_bytes().contains(&b'/') {
        return vec![PathBuf::from(program)];
    }
    // Explicit container contract: an unset PATH never implicitly searches cwd.
    // An explicitly empty PATH component retains its ordinary cwd meaning.
    let path = path.unwrap_or_else(|| OsString::from("/bin:/usr/bin"));
    std::env::split_paths(&path)
        .map(|directory| directory.join(program))
        .collect()
}

struct Prepared {
    arguments: Vec<CString>,
    environment: Vec<CString>,
    candidates: Vec<CString>,
}

impl Prepared {
    fn new(argv: &[OsString]) -> io::Result<Self> {
        let program = argv
            .first()
            .filter(|program| !program.is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "empty executable"))?;
        let arguments = argv
            .iter()
            .map(|argument| c_string(argument.as_bytes()))
            .collect::<io::Result<Vec<_>>>()?;
        // Snapshot through Rust's environment API, not a borrowed libc environ
        // pointer. Native bytes are preserved and never added to log fields.
        let environment = std::env::vars_os()
            .map(|(key, value)| {
                let mut bytes = key.as_bytes().to_vec();
                bytes.push(b'=');
                bytes.extend_from_slice(value.as_bytes());
                c_string(&bytes)
            })
            .collect::<io::Result<Vec<_>>>()?;
        let candidates = candidates(program, std::env::var_os("PATH"))
            .iter()
            .map(|path| c_string(path.as_os_str().as_bytes()))
            .collect::<io::Result<Vec<_>>>()?;
        Ok(Self {
            arguments,
            environment,
            candidates,
        })
    }

    fn execute(self) -> Failure {
        let mut argv: Vec<_> = self.arguments.iter().map(|value| value.as_ptr()).collect();
        let mut envp: Vec<_> = self
            .environment
            .iter()
            .map(|value| value.as_ptr())
            .collect();
        argv.push(ptr::null());
        envp.push(ptr::null());

        // Match Rust Command's successful-exec SIGPIPE behavior without leaving
        // SIG_DFL installed when execution fails. The launcher is single-threaded.
        // SAFETY: zero is valid for these C integer/pointer fields; sigemptyset
        // initializes the signal mask before sigaction reads the structure.
        let mut action: libc::sigaction = unsafe { std::mem::zeroed() };
        action.sa_sigaction = libc::SIG_DFL;
        let mut previous = MaybeUninit::<libc::sigaction>::uninit();
        // SAFETY: both pointers refer to live, correctly sized sigaction storage.
        let installed = unsafe {
            libc::sigemptyset(&mut action.sa_mask) == 0
                && libc::sigaction(libc::SIGPIPE, &action, previous.as_mut_ptr()) == 0
        };
        if !installed {
            return Failure {
                error: io::Error::last_os_error(),
                can_log: true,
            };
        }

        let mut error = io::Error::from_raw_os_error(libc::ENOENT);
        let mut denied = false;
        for candidate in &self.candidates {
            // SAFETY: all CStrings and null-terminated pointer arrays stay alive
            // and unmodified until this call returns, or the process is replaced.
            // Unlike execvp, execve never interprets unrecognized text via a shell.
            unsafe { libc::execve(candidate.as_ptr(), argv.as_ptr(), envp.as_ptr()) };
            error = io::Error::last_os_error();
            match error.raw_os_error() {
                Some(libc::EACCES) => denied = true,
                Some(libc::ENOENT | libc::ENOTDIR) => {}
                _ => {
                    denied = false;
                    break;
                }
            }
        }
        if denied {
            error = io::Error::from_raw_os_error(libc::EACCES);
        }
        // SAFETY: the first sigaction succeeded and initialized previous. Restore
        // the whole disposition before any logging; no signal mask is changed.
        let can_log =
            unsafe { libc::sigaction(libc::SIGPIPE, previous.as_ptr(), ptr::null_mut()) == 0 };
        Failure { error, can_log }
    }
}

pub(super) fn replace(argv: &[OsString]) -> Failure {
    match Prepared::new(argv) {
        Ok(prepared) => prepared.execute(),
        Err(error) => Failure {
            error,
            can_log: true,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::ffi::OsStringExt;

    #[test]
    fn paths_are_literal_and_only_basenames_are_searched() {
        assert_eq!(
            candidates(OsStr::new("./app"), Some("/ignored".into())),
            vec![PathBuf::from("./app")]
        );
        assert_eq!(
            candidates(OsStr::new("app"), Some(":/one::/two:".into())),
            ["app", "/one/app", "app", "/two/app", "app"].map(PathBuf::from)
        );
        assert_eq!(
            candidates(OsStr::new("app"), None),
            ["/bin/app", "/usr/bin/app"].map(PathBuf::from)
        );
    }

    #[test]
    fn embedded_nul_is_rejected_before_signal_changes() {
        let argv = [OsString::from("/app"), OsString::from_vec(b"a\0b".to_vec())];
        assert!(matches!(
            Prepared::new(&argv),
            Err(error) if error.kind() == io::ErrorKind::InvalidInput
        ));
    }
}
