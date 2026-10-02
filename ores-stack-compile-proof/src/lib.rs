#![forbid(unsafe_code)]

pub mod lambda_artifact {
    use serde::{Deserialize, Serialize};

    #[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
    #[serde(rename_all = "snake_case")]
    pub enum LambdaBuildArchitecture {
        X86_64,
        Arm64,
    }
}

pub mod lambda_build;
pub mod lambda_compile;
