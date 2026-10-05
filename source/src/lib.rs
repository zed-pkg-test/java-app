use base64::{
    Engine as _,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use hmac::{Hmac, Mac};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

type HmacSha256 = Hmac<Sha256>;

pub mod service;

pub const PRIVATE_HOP_ROUTING_HEADER: &str = "x-ores-ingress-routing-key";
pub const PRIVATE_HOP_TIMESTAMP_HEADER: &str = "x-ores-ingress-timestamp";
pub const PRIVATE_HOP_SIGNATURE_HEADER: &str = "x-ores-ingress-signature";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VerificationError {
    MissingOrMalformedHeader,
    StaleRequest,
    InvalidSignature,
    InvalidIssuer,
    InvalidAudience,
    InvalidServiceAccount,
    InvalidTokenTime,
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
#[serde(untagged)]
pub enum Audience {
    One(String),
    Many(Vec<String>),
}

impl Audience {
    fn exactly_matches(&self, expected: &str) -> bool {
        match self {
            Self::One(value) => value == expected,
            Self::Many(values) => {
                values.len() == 1 && values.first().is_some_and(|v| v == expected)
            }
        }
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq, Eq)]
pub struct VerifiedGoogleOidcClaims {
    #[serde(rename = "iss")]
    pub issuer: String,
    #[serde(rename = "aud")]
    pub audience: Audience,
    pub email: String,
    #[serde(default)]
    pub email_verified: bool,
    #[serde(rename = "exp")]
    pub expires_at: i64,
    #[serde(rename = "iat")]
    pub issued_at: i64,
    #[serde(default)]
    pub sub: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GoogleOidcPolicy<'a> {
    pub expected_audience: &'a str,
    pub expected_service_account_email: &'a str,
    pub max_clock_skew_seconds: u64,
    pub max_token_lifetime_seconds: u64,
}

pub fn verify_slack_request(
    signing_secret: &[u8],
    timestamp_header: &str,
    signature_header: &str,
    raw_body: &[u8],
    now_unix_seconds: i64,
    replay_window_seconds: u64,
) -> Result<(), VerificationError> {
    let timestamp = timestamp_header
        .parse::<i64>()
        .map_err(|_| VerificationError::MissingOrMalformedHeader)?;
    if now_unix_seconds.abs_diff(timestamp) > replay_window_seconds {
        return Err(VerificationError::StaleRequest);
    }

    let encoded = signature_header
        .strip_prefix("v0=")
        .ok_or(VerificationError::MissingOrMalformedHeader)?;
    let signature = hex::decode(encoded).map_err(|_| VerificationError::InvalidSignature)?;

    let mut mac = HmacSha256::new_from_slice(signing_secret)
        .map_err(|_| VerificationError::InvalidSignature)?;
    mac.update(b"v0:");
    mac.update(timestamp_header.as_bytes());
    mac.update(b":");
    mac.update(raw_body);
    mac.verify_slice(&signature)
        .map_err(|_| VerificationError::InvalidSignature)
}

pub fn verify_zendesk_request(
    signing_secret: &[u8],
    signature_timestamp_header: &str,
    signature_header: &str,
    raw_body: &[u8],
    now_unix_seconds: i64,
    replay_window_seconds: u64,
) -> Result<(), VerificationError> {
    let timestamp = OffsetDateTime::parse(signature_timestamp_header, &Rfc3339)
        .map_err(|_| VerificationError::MissingOrMalformedHeader)?
        .unix_timestamp();
    if now_unix_seconds.abs_diff(timestamp) > replay_window_seconds {
        return Err(VerificationError::StaleRequest);
    }

    let signature = STANDARD
        .decode(signature_header.as_bytes())
        .map_err(|_| VerificationError::InvalidSignature)?;
    let mut mac = HmacSha256::new_from_slice(signing_secret)
        .map_err(|_| VerificationError::InvalidSignature)?;
    mac.update(signature_timestamp_header.as_bytes());
    mac.update(raw_body);
    mac.verify_slice(&signature)
        .map_err(|_| VerificationError::InvalidSignature)
}

/// Validate claims from a Google OIDC token *after* cryptographic JWT signature
/// verification. The caller must verify the token against current Google keys
/// before constructing this value; this function intentionally does not decode
/// or trust an unsigned JWT.
pub fn validate_verified_google_oidc_claims(
    claims: &VerifiedGoogleOidcClaims,
    policy: &GoogleOidcPolicy<'_>,
    now_unix_seconds: i64,
) -> Result<(), VerificationError> {
    if !matches!(
        claims.issuer.as_str(),
        "accounts.google.com" | "https://accounts.google.com"
    ) {
        return Err(VerificationError::InvalidIssuer);
    }
    if !claims.audience.exactly_matches(policy.expected_audience) {
        return Err(VerificationError::InvalidAudience);
    }
    if !claims.email_verified || claims.email != policy.expected_service_account_email {
        return Err(VerificationError::InvalidServiceAccount);
    }

    let skew = policy.max_clock_skew_seconds;
    if claims.expires_at <= claims.issued_at
        || claims.expires_at.saturating_add_unsigned(skew) < now_unix_seconds
        || claims.issued_at > now_unix_seconds.saturating_add_unsigned(skew)
        || claims.expires_at.abs_diff(claims.issued_at)
            > policy.max_token_lifetime_seconds.saturating_add(skew)
    {
        return Err(VerificationError::InvalidTokenTime);
    }
    Ok(())
}

#[must_use]
pub fn digest_hex(raw_body: &[u8]) -> String {
    format!("{:x}", Sha256::digest(raw_body))
}

#[must_use]
pub fn mint_private_hop_v2(
    provider: &str,
    routing_key: &str,
    timestamp: i64,
    raw_body: &[u8],
    private_hop_key: &[u8],
) -> String {
    let canonical = format!(
        "v2\n{provider}\n{routing_key}\n{timestamp}\n{}",
        digest_hex(raw_body)
    );
    let mut mac =
        HmacSha256::new_from_slice(private_hop_key).expect("HMAC accepts arbitrary key lengths");
    mac.update(canonical.as_bytes());
    URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
}

#[must_use]
pub fn sanitize_public_headers<'a, I>(headers: I) -> Vec<(String, String)>
where
    I: IntoIterator<Item = (&'a str, &'a str)>,
{
    headers
        .into_iter()
        .filter_map(|(name, value)| {
            let normalized = name.trim().to_ascii_lowercase();
            if normalized.starts_with("x-ores-") {
                None
            } else {
                Some((normalized, value.to_string()))
            }
        })
        .collect()
}

#[must_use]
pub fn private_assertion_headers_are_canonical<'a, I>(headers: I) -> bool
where
    I: IntoIterator<Item = &'a str>,
{
    let mut routing = 0_u8;
    let mut timestamp = 0_u8;
    let mut signature = 0_u8;

    for name in headers {
        let normalized = name.trim().to_ascii_lowercase();
        if !normalized.starts_with("x-ores-") {
            continue;
        }
        match normalized.as_str() {
            PRIVATE_HOP_ROUTING_HEADER => routing = routing.saturating_add(1),
            PRIVATE_HOP_TIMESTAMP_HEADER => timestamp = timestamp.saturating_add(1),
            PRIVATE_HOP_SIGNATURE_HEADER => signature = signature.saturating_add(1),
            _ => return false,
        }
    }

    routing == 1 && timestamp == 1 && signature == 1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slack_matches_documented_vector_and_rejects_replay() {
        let secret = b"8f742231b10e8888abcd99yyyzzz85a5";
        let timestamp = "1531420618";
        let body = b"token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
        let signature = "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503";

        assert_eq!(
            verify_slack_request(secret, timestamp, signature, body, 1_531_420_618, 300),
            Ok(())
        );
        assert_eq!(
            verify_slack_request(secret, timestamp, signature, body, 1_531_420_919, 300),
            Err(VerificationError::StaleRequest)
        );
        assert_eq!(
            verify_slack_request(secret, timestamp, signature, b"changed", 1_531_420_618, 300),
            Err(VerificationError::InvalidSignature)
        );
    }

    #[test]
    fn zendesk_uses_timestamp_plus_raw_body_and_rejects_stale_requests() {
        let secret = b"dGhpc19zZWNyZXRfaXNfZm9yX3Rlc3Rpbmdfb25seQ==";
        let timestamp = "2021-03-25T05:09:27Z";
        let body = br#"{"event":"ticket.updated","id":123}"#;
        let signature = "S/RDdU2ERmj7LHDDoWJ2onPYH+t7/f2+LTM4dL7UiLM=";
        let now = OffsetDateTime::parse(timestamp, &Rfc3339)
            .expect("valid fixture timestamp")
            .unix_timestamp();

        assert_eq!(
            verify_zendesk_request(secret, timestamp, signature, body, now, 300),
            Ok(())
        );
        assert_eq!(
            verify_zendesk_request(secret, timestamp, signature, body, now + 301, 300),
            Err(VerificationError::StaleRequest)
        );
        assert_eq!(
            verify_zendesk_request(secret, timestamp, signature, b"changed", now, 300),
            Err(VerificationError::InvalidSignature)
        );
    }

    #[test]
    fn google_claim_policy_is_exact_and_bounded() {
        let policy = GoogleOidcPolicy {
            expected_audience: "https://support.example.com/pubsub",
            expected_service_account_email: "pubsub-push@example.iam.gserviceaccount.com",
            max_clock_skew_seconds: 30,
            max_token_lifetime_seconds: 3600,
        };
        let claims = VerifiedGoogleOidcClaims {
            issuer: "https://accounts.google.com".to_string(),
            audience: Audience::One(policy.expected_audience.to_string()),
            email: policy.expected_service_account_email.to_string(),
            email_verified: true,
            issued_at: 1_800_000_000,
            expires_at: 1_800_003_600,
            sub: Some("123".to_string()),
        };

        assert_eq!(
            validate_verified_google_oidc_claims(&claims, &policy, 1_800_000_100),
            Ok(())
        );

        let mut wrong_audience = claims.clone();
        wrong_audience.audience = Audience::Many(vec![
            policy.expected_audience.to_string(),
            "https://other.example.com".to_string(),
        ]);
        assert_eq!(
            validate_verified_google_oidc_claims(&wrong_audience, &policy, 1_800_000_100),
            Err(VerificationError::InvalidAudience)
        );

        let mut wrong_account = claims.clone();
        wrong_account.email = "other@example.iam.gserviceaccount.com".to_string();
        assert_eq!(
            validate_verified_google_oidc_claims(&wrong_account, &policy, 1_800_000_100),
            Err(VerificationError::InvalidServiceAccount)
        );
    }

    #[test]
    fn private_hop_v2_has_stable_known_vector() {
        let signature = mint_private_hop_v2(
            "zendesk",
            "tenant_123-zd",
            1_800_000_000,
            br#"{"event":"x"}"#,
            b"0123456789abcdef0123456789abcdef",
        );
        assert_eq!(signature, "3tvsCJk670G3yqHu5QFsLllTGvXBU5Y8uMKqjmBUxv0");
    }

    #[test]
    fn public_edge_strips_internal_headers_and_private_side_rejects_smuggling() {
        let sanitized = sanitize_public_headers([
            ("Authorization", "Bearer provider-token"),
            ("X-Ores-Ingress-Signature", "forged"),
            ("x-ores-admin", "forged"),
            ("X-Slack-Signature", "v0=abc"),
        ]);
        assert_eq!(
            sanitized,
            vec![
                (
                    "authorization".to_string(),
                    "Bearer provider-token".to_string()
                ),
                ("x-slack-signature".to_string(), "v0=abc".to_string()),
            ]
        );

        assert!(private_assertion_headers_are_canonical([
            PRIVATE_HOP_ROUTING_HEADER,
            PRIVATE_HOP_TIMESTAMP_HEADER,
            PRIVATE_HOP_SIGNATURE_HEADER,
            "content-type",
        ]));
        assert!(!private_assertion_headers_are_canonical([
            PRIVATE_HOP_ROUTING_HEADER,
            PRIVATE_HOP_TIMESTAMP_HEADER,
            PRIVATE_HOP_SIGNATURE_HEADER,
            PRIVATE_HOP_SIGNATURE_HEADER,
        ]));
        assert!(!private_assertion_headers_are_canonical([
            PRIVATE_HOP_ROUTING_HEADER,
            PRIVATE_HOP_TIMESTAMP_HEADER,
            PRIVATE_HOP_SIGNATURE_HEADER,
            "x-ores-tenant-id",
        ]));
    }
}
