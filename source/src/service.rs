use std::fmt;

use crate::{
    GoogleOidcPolicy, VerificationError, VerifiedGoogleOidcClaims, mint_private_hop_v2,
    verify_slack_request, verify_zendesk_request, validate_verified_google_oidc_claims,
};

const MAX_ROUTE_KEY_BYTES: usize = 320;
const MIN_PRIVATE_HOP_KEY_BYTES: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackendError {
    Unavailable,
    InvalidData,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ServiceError {
    BackendUnavailable,
    BackendInvalidData,
    InvalidRouteIdentity,
    RouteNotFound,
    RouteDisabled,
    IntegrationDisabled,
    RouteAuthorityMismatch,
    RouteConflict,
    MissingSecretReference,
    MissingGoogleOidcPolicy,
    InvalidSecretMaterial,
    InvalidPrivateHopKey,
    Verification(VerificationError),
}

impl ServiceError {
    #[must_use]
    pub fn is_retryable(&self) -> bool {
        matches!(self, Self::BackendUnavailable)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IntegrationRoute {
    pub tenant_id: String,
    pub integration_id: String,
    pub provider: String,
    pub route_kind: String,
    pub route_key: String,
    pub route_enabled: bool,
    pub integration_enabled: bool,
    pub provider_secret_ref: Option<String>,
    pub google_oidc_audience: Option<String>,
    pub google_service_account_email: Option<String>,
}

pub trait IntegrationRouteAuthority {
    fn resolve_route(
        &self,
        provider: &str,
        route_kind: &str,
        route_key: &str,
    ) -> Result<Option<IntegrationRoute>, BackendError>;
}

#[derive(Clone, PartialEq, Eq)]
struct PreviousSecret {
    value: Vec<u8>,
    valid_until: i64,
}

#[derive(Clone, PartialEq, Eq)]
pub struct RotatingSecret {
    current: Vec<u8>,
    previous: Option<PreviousSecret>,
}

impl fmt::Debug for RotatingSecret {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("RotatingSecret")
            .field("current", &"<redacted>")
            .field(
                "previous",
                &self.previous.as_ref().map(|previous| {
                    ("<redacted>", previous.valid_until)
                }),
            )
            .finish()
    }
}

impl RotatingSecret {
    pub fn new(
        current: Vec<u8>,
        previous: Option<(Vec<u8>, i64)>,
    ) -> Result<Self, ServiceError> {
        if current.is_empty()
            || previous
                .as_ref()
                .is_some_and(|(value, _)| value.is_empty())
        {
            return Err(ServiceError::InvalidSecretMaterial);
        }
        Ok(Self {
            current,
            previous: previous.map(|(value, valid_until)| PreviousSecret {
                value,
                valid_until,
            }),
        })
    }

    fn current(&self) -> &[u8] {
        &self.current
    }

    fn previous(&self, now_unix_seconds: i64) -> Option<&[u8]> {
        self.previous
            .as_ref()
            .filter(|previous| now_unix_seconds <= previous.valid_until)
            .map(|previous| previous.value.as_slice())
    }
}

pub trait ProviderSecretStore {
    fn resolve_secret(&self, secret_ref: &str) -> Result<RotatingSecret, BackendError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedIngress {
    pub tenant_id: String,
    pub integration_id: String,
    pub provider: String,
    pub routing_key: String,
    pub timestamp: i64,
    pub signature: String,
}

impl VerifiedIngress {
    #[must_use]
    pub fn private_headers(&self) -> [(String, String); 3] {
        [
            (
                crate::PRIVATE_HOP_ROUTING_HEADER.to_owned(),
                self.routing_key.clone(),
            ),
            (
                crate::PRIVATE_HOP_TIMESTAMP_HEADER.to_owned(),
                self.timestamp.to_string(),
            ),
            (
                crate::PRIVATE_HOP_SIGNATURE_HEADER.to_owned(),
                self.signature.clone(),
            ),
        ]
    }
}

#[derive(Debug, Clone, Copy)]
pub struct SlackIngress<'a> {
    pub team_id: Option<&'a str>,
    pub enterprise_id: Option<&'a str>,
    pub timestamp_header: &'a str,
    pub signature_header: &'a str,
    pub raw_body: &'a [u8],
}

#[derive(Debug, Clone, Copy)]
pub struct ZendeskIngress<'a> {
    pub integration_key: &'a str,
    pub timestamp_header: &'a str,
    pub signature_header: &'a str,
    pub raw_body: &'a [u8],
}

#[derive(Debug, Clone, Copy)]
pub struct GmailIngress<'a> {
    pub mailbox: &'a str,
    pub verified_claims: &'a VerifiedGoogleOidcClaims,
    pub raw_body: &'a [u8],
}

pub fn verify_slack_and_mint<A, S>(
    authority: &A,
    secrets: &S,
    ingress: SlackIngress<'_>,
    now_unix_seconds: i64,
    replay_window_seconds: u64,
    private_hop_key: &[u8],
) -> Result<VerifiedIngress, ServiceError>
where
    A: IntegrationRouteAuthority,
    S: ProviderSecretStore,
{
    let mut saw_identity = false;
    let mut selected: Option<IntegrationRoute> = None;

    for (route_kind, route_key) in [
        ("slack_team", ingress.team_id),
        ("slack_enterprise", ingress.enterprise_id),
    ] {
        let Some(route_key) = route_key else {
            continue;
        };
        saw_identity = true;
        validate_route_identity(route_key)?;

        let Some(route) = resolve_route(authority, "slack", route_kind, route_key)? else {
            continue;
        };

        if let Some(existing) = &selected {
            if !same_integration_authority(existing, &route) {
                return Err(ServiceError::RouteConflict);
            }
        } else {
            selected = Some(route);
        }
    }

    if !saw_identity {
        return Err(ServiceError::InvalidRouteIdentity);
    }

    let route = selected.ok_or(ServiceError::RouteNotFound)?;
    let secret = resolve_provider_secret(secrets, &route)?;

    verify_with_rotation(&secret, now_unix_seconds, |candidate| {
        verify_slack_request(
            candidate,
            ingress.timestamp_header,
            ingress.signature_header,
            ingress.raw_body,
            now_unix_seconds,
            replay_window_seconds,
        )
    })?;

    finish_ingress(
        route,
        now_unix_seconds,
        ingress.raw_body,
        private_hop_key,
    )
}

pub fn verify_zendesk_and_mint<A, S>(
    authority: &A,
    secrets: &S,
    ingress: ZendeskIngress<'_>,
    now_unix_seconds: i64,
    replay_window_seconds: u64,
    private_hop_key: &[u8],
) -> Result<VerifiedIngress, ServiceError>
where
    A: IntegrationRouteAuthority,
    S: ProviderSecretStore,
{
    validate_route_identity(ingress.integration_key)?;
    let route = resolve_route(
        authority,
        "zendesk",
        "zendesk_integration_key",
        ingress.integration_key,
    )?
    .ok_or(ServiceError::RouteNotFound)?;
    let secret = resolve_provider_secret(secrets, &route)?;

    verify_with_rotation(&secret, now_unix_seconds, |candidate| {
        verify_zendesk_request(
            candidate,
            ingress.timestamp_header,
            ingress.signature_header,
            ingress.raw_body,
            now_unix_seconds,
            replay_window_seconds,
        )
    })?;

    finish_ingress(
        route,
        now_unix_seconds,
        ingress.raw_body,
        private_hop_key,
    )
}

pub fn verify_gmail_and_mint<A>(
    authority: &A,
    ingress: GmailIngress<'_>,
    now_unix_seconds: i64,
    max_clock_skew_seconds: u64,
    max_token_lifetime_seconds: u64,
    private_hop_key: &[u8],
) -> Result<VerifiedIngress, ServiceError>
where
    A: IntegrationRouteAuthority,
{
    validate_gmail_mailbox(ingress.mailbox)?;
    let route = resolve_route(authority, "gmail", "gmail_mailbox", ingress.mailbox)?
        .ok_or(ServiceError::RouteNotFound)?;
    let audience = route
        .google_oidc_audience
        .as_deref()
        .ok_or(ServiceError::MissingGoogleOidcPolicy)?;
    let service_account = route
        .google_service_account_email
        .as_deref()
        .ok_or(ServiceError::MissingGoogleOidcPolicy)?;

    let policy = GoogleOidcPolicy {
        expected_audience: audience,
        expected_service_account_email: service_account,
        max_clock_skew_seconds,
        max_token_lifetime_seconds,
    };
    validate_verified_google_oidc_claims(ingress.verified_claims, &policy, now_unix_seconds)
        .map_err(ServiceError::Verification)?;

    finish_ingress(
        route,
        now_unix_seconds,
        ingress.raw_body,
        private_hop_key,
    )
}

fn map_backend(error: BackendError) -> ServiceError {
    match error {
        BackendError::Unavailable => ServiceError::BackendUnavailable,
        BackendError::InvalidData => ServiceError::BackendInvalidData,
    }
}

fn resolve_route<A>(
    authority: &A,
    provider: &str,
    route_kind: &str,
    route_key: &str,
) -> Result<Option<IntegrationRoute>, ServiceError>
where
    A: IntegrationRouteAuthority,
{
    let route = authority
        .resolve_route(provider, route_kind, route_key)
        .map_err(map_backend)?;
    let Some(route) = route else {
        return Ok(None);
    };

    if route.provider != provider
        || route.route_kind != route_kind
        || route.route_key != route_key
    {
        return Err(ServiceError::RouteAuthorityMismatch);
    }
    if !route.route_enabled {
        return Err(ServiceError::RouteDisabled);
    }
    if !route.integration_enabled {
        return Err(ServiceError::IntegrationDisabled);
    }

    Ok(Some(route))
}

fn resolve_provider_secret<S>(
    secrets: &S,
    route: &IntegrationRoute,
) -> Result<RotatingSecret, ServiceError>
where
    S: ProviderSecretStore,
{
    let secret_ref = route
        .provider_secret_ref
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .ok_or(ServiceError::MissingSecretReference)?;
    secrets.resolve_secret(secret_ref).map_err(map_backend)
}

fn same_integration_authority(left: &IntegrationRoute, right: &IntegrationRoute) -> bool {
    left.tenant_id == right.tenant_id
        && left.integration_id == right.integration_id
        && left.provider == right.provider
        && left.integration_enabled == right.integration_enabled
        && left.provider_secret_ref == right.provider_secret_ref
}

fn validate_route_identity(value: &str) -> Result<(), ServiceError> {
    if value.is_empty()
        || value.len() > MAX_ROUTE_KEY_BYTES
        || value.trim() != value
        || value.chars().any(char::is_control)
    {
        return Err(ServiceError::InvalidRouteIdentity);
    }
    Ok(())
}

fn validate_gmail_mailbox(value: &str) -> Result<(), ServiceError> {
    validate_route_identity(value)?;
    if value != value.to_ascii_lowercase()
        || value.chars().any(char::is_whitespace)
        || value.split('@').count() != 2
        || value.starts_with('@')
        || value.ends_with('@')
    {
        return Err(ServiceError::InvalidRouteIdentity);
    }
    Ok(())
}

fn verify_with_rotation<F>(
    secret: &RotatingSecret,
    now_unix_seconds: i64,
    verify: F,
) -> Result<(), ServiceError>
where
    F: Fn(&[u8]) -> Result<(), VerificationError>,
{
    match verify(secret.current()) {
        Ok(()) => Ok(()),
        Err(VerificationError::InvalidSignature) => {
            let Some(previous) = secret.previous(now_unix_seconds) else {
                return Err(ServiceError::Verification(
                    VerificationError::InvalidSignature,
                ));
            };
            verify(previous).map_err(ServiceError::Verification)
        }
        Err(error) => Err(ServiceError::Verification(error)),
    }
}

fn finish_ingress(
    route: IntegrationRoute,
    timestamp: i64,
    raw_body: &[u8],
    private_hop_key: &[u8],
) -> Result<VerifiedIngress, ServiceError> {
    if private_hop_key.len() < MIN_PRIVATE_HOP_KEY_BYTES {
        return Err(ServiceError::InvalidPrivateHopKey);
    }

    let signature = mint_private_hop_v2(
        &route.provider,
        &route.route_key,
        timestamp,
        raw_body,
        private_hop_key,
    );

    Ok(VerifiedIngress {
        tenant_id: route.tenant_id,
        integration_id: route.integration_id,
        provider: route.provider,
        routing_key: route.route_key,
        timestamp,
        signature,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Audience;

    #[derive(Default)]
    struct StaticAuthority {
        routes: Vec<IntegrationRoute>,
        fail: Option<BackendError>,
    }

    impl IntegrationRouteAuthority for StaticAuthority {
        fn resolve_route(
            &self,
            provider: &str,
            route_kind: &str,
            route_key: &str,
        ) -> Result<Option<IntegrationRoute>, BackendError> {
            if let Some(error) = self.fail {
                return Err(error);
            }
            Ok(self
                .routes
                .iter()
                .find(|route| {
                    route.provider == provider
                        && route.route_kind == route_kind
                        && route.route_key == route_key
                })
                .cloned())
        }
    }

    #[derive(Default)]
    struct StaticSecrets {
        values: Vec<(String, RotatingSecret)>,
        fail: Option<BackendError>,
    }

    impl ProviderSecretStore for StaticSecrets {
        fn resolve_secret(&self, secret_ref: &str) -> Result<RotatingSecret, BackendError> {
            if let Some(error) = self.fail {
                return Err(error);
            }
            self.values
                .iter()
                .find(|(candidate, _)| candidate == secret_ref)
                .map(|(_, secret)| secret.clone())
                .ok_or(BackendError::InvalidData)
        }
    }

    fn route(
        provider: &str,
        route_kind: &str,
        route_key: &str,
        tenant: &str,
        integration: &str,
    ) -> IntegrationRoute {
        IntegrationRoute {
            tenant_id: tenant.to_owned(),
            integration_id: integration.to_owned(),
            provider: provider.to_owned(),
            route_kind: route_kind.to_owned(),
            route_key: route_key.to_owned(),
            route_enabled: true,
            integration_enabled: true,
            provider_secret_ref: Some("secret://provider/current".to_owned()),
            google_oidc_audience: None,
            google_service_account_email: None,
        }
    }

    #[test]
    fn slack_accepts_previous_provider_secret_during_rotation_and_mints_v2() {
        let authority = StaticAuthority {
            routes: vec![
                route("slack", "slack_team", "T1DC2JH3J", "tenant-a", "integration-a"),
                route(
                    "slack",
                    "slack_enterprise",
                    "E1",
                    "tenant-a",
                    "integration-a",
                ),
            ],
            fail: None,
        };
        let secrets = StaticSecrets {
            values: vec![(
                "secret://provider/current".to_owned(),
                RotatingSecret::new(
                    b"new-secret-not-active-at-provider".to_vec(),
                    Some((
                        b"8f742231b10e8888abcd99yyyzzz85a5".to_vec(),
                        1_531_420_700,
                    )),
                )
                .expect("valid rotation"),
            )],
            fail: None,
        };
        let body = b"token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";

        let verified = verify_slack_and_mint(
            &authority,
            &secrets,
            SlackIngress {
                team_id: Some("T1DC2JH3J"),
                enterprise_id: Some("E1"),
                timestamp_header: "1531420618",
                signature_header:
                    "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503",
                raw_body: body,
            },
            1_531_420_618,
            300,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect("previous provider secret is accepted during bounded rotation");

        assert_eq!(verified.tenant_id, "tenant-a");
        assert_eq!(verified.integration_id, "integration-a");
        assert_eq!(verified.routing_key, "T1DC2JH3J");
        assert_eq!(
            verified.signature,
            mint_private_hop_v2(
                "slack",
                "T1DC2JH3J",
                1_531_420_618,
                body,
                b"0123456789abcdef0123456789abcdef",
            )
        );
        let headers = verified.private_headers();
        assert!(crate::private_assertion_headers_are_canonical(
            headers.iter().map(|(name, _)| name.as_str())
        ));
    }

    #[test]
    fn expired_previous_provider_secret_is_not_accepted() {
        let authority = StaticAuthority {
            routes: vec![route(
                "slack",
                "slack_team",
                "T1DC2JH3J",
                "tenant-a",
                "integration-a",
            )],
            fail: None,
        };
        let secrets = StaticSecrets {
            values: vec![(
                "secret://provider/current".to_owned(),
                RotatingSecret::new(
                    b"new-secret-not-active-at-provider".to_vec(),
                    Some((
                        b"8f742231b10e8888abcd99yyyzzz85a5".to_vec(),
                        1_531_420_617,
                    )),
                )
                .expect("valid bounded rotation"),
            )],
            fail: None,
        };
        let body = b"token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";

        let error = verify_slack_and_mint(
            &authority,
            &secrets,
            SlackIngress {
                team_id: Some("T1DC2JH3J"),
                enterprise_id: None,
                timestamp_header: "1531420618",
                signature_header:
                    "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503",
                raw_body: body,
            },
            1_531_420_618,
            300,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("expired previous secret must not remain an authentication key");

        assert_eq!(
            error,
            ServiceError::Verification(VerificationError::InvalidSignature)
        );
    }

    #[test]
    fn slack_conflicting_route_authorities_fail_closed() {
        let authority = StaticAuthority {
            routes: vec![
                route("slack", "slack_team", "T1", "tenant-a", "integration-a"),
                route(
                    "slack",
                    "slack_enterprise",
                    "E1",
                    "tenant-b",
                    "integration-b",
                ),
            ],
            fail: None,
        };
        let secrets = StaticSecrets::default();

        let error = verify_slack_and_mint(
            &authority,
            &secrets,
            SlackIngress {
                team_id: Some("T1"),
                enterprise_id: Some("E1"),
                timestamp_header: "1531420618",
                signature_header: "v0=00",
                raw_body: b"body",
            },
            1_531_420_618,
            300,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("conflicting route ownership must fail before secret verification");

        assert_eq!(error, ServiceError::RouteConflict);
    }

    #[test]
    fn disabled_route_and_integration_fail_closed() {
        let mut disabled_route = route(
            "zendesk",
            "zendesk_integration_key",
            "tenant123",
            "tenant-a",
            "integration-a",
        );
        disabled_route.route_enabled = false;
        let authority = StaticAuthority {
            routes: vec![disabled_route],
            fail: None,
        };

        let error = verify_zendesk_and_mint(
            &authority,
            &StaticSecrets::default(),
            ZendeskIngress {
                integration_key: "tenant123",
                timestamp_header: "2021-03-25T05:09:27Z",
                signature_header: "bad",
                raw_body: b"body",
            },
            1_616_648_967,
            300,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("disabled routes must fail closed");
        assert_eq!(error, ServiceError::RouteDisabled);

        let mut disabled_integration = route(
            "zendesk",
            "zendesk_integration_key",
            "tenant123",
            "tenant-a",
            "integration-a",
        );
        disabled_integration.integration_enabled = false;
        let authority = StaticAuthority {
            routes: vec![disabled_integration],
            fail: None,
        };
        let error = verify_zendesk_and_mint(
            &authority,
            &StaticSecrets::default(),
            ZendeskIngress {
                integration_key: "tenant123",
                timestamp_header: "2021-03-25T05:09:27Z",
                signature_header: "bad",
                raw_body: b"body",
            },
            1_616_648_967,
            300,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("disabled integrations must fail closed");
        assert_eq!(error, ServiceError::IntegrationDisabled);
    }

    #[test]
    fn gmail_requires_canonical_route_and_route_owned_verified_claim_policy() {
        let mut gmail = route(
            "gmail",
            "gmail_mailbox",
            "support@example.com",
            "tenant-a",
            "integration-a",
        );
        gmail.provider_secret_ref = None;
        gmail.google_oidc_audience = Some("https://support.example.com/pubsub".to_owned());
        gmail.google_service_account_email =
            Some("pubsub-push@example.iam.gserviceaccount.com".to_owned());
        let authority = StaticAuthority {
            routes: vec![gmail],
            fail: None,
        };
        let claims = VerifiedGoogleOidcClaims {
            issuer: "https://accounts.google.com".to_owned(),
            audience: Audience::One("https://support.example.com/pubsub".to_owned()),
            email: "pubsub-push@example.iam.gserviceaccount.com".to_owned(),
            email_verified: true,
            expires_at: 1_800_003_600,
            issued_at: 1_800_000_000,
            sub: Some("123".to_owned()),
        };

        let verified = verify_gmail_and_mint(
            &authority,
            GmailIngress {
                mailbox: "support@example.com",
                verified_claims: &claims,
                raw_body: b"pubsub",
            },
            1_800_000_100,
            30,
            3600,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect("route-owned policy accepts verified claims");
        assert_eq!(verified.routing_key, "support@example.com");

        let error = verify_gmail_and_mint(
            &authority,
            GmailIngress {
                mailbox: "Support@example.com",
                verified_claims: &claims,
                raw_body: b"pubsub",
            },
            1_800_000_100,
            30,
            3600,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("non-canonical mailbox must fail before route lookup");
        assert_eq!(error, ServiceError::InvalidRouteIdentity);
    }

    #[test]
    fn backend_outages_are_retryable_but_authentication_failures_are_not() {
        let authority = StaticAuthority {
            routes: Vec::new(),
            fail: Some(BackendError::Unavailable),
        };
        let error = verify_gmail_and_mint(
            &authority,
            GmailIngress {
                mailbox: "support@example.com",
                verified_claims: &VerifiedGoogleOidcClaims {
                    issuer: "https://accounts.google.com".to_owned(),
                    audience: Audience::One("aud".to_owned()),
                    email: "service@example.com".to_owned(),
                    email_verified: true,
                    expires_at: 200,
                    issued_at: 100,
                    sub: None,
                },
                raw_body: b"body",
            },
            150,
            30,
            3600,
            b"0123456789abcdef0123456789abcdef",
        )
        .expect_err("route backend outage is surfaced");
        assert_eq!(error, ServiceError::BackendUnavailable);
        assert!(error.is_retryable());

        let auth_error = ServiceError::Verification(VerificationError::InvalidSignature);
        assert!(!auth_error.is_retryable());
    }

    #[test]
    fn secret_material_and_private_hop_keys_fail_closed() {
        assert_eq!(
            RotatingSecret::new(Vec::new(), None),
            Err(ServiceError::InvalidSecretMaterial)
        );

        let route = route(
            "zendesk",
            "zendesk_integration_key",
            "tenant123",
            "tenant-a",
            "integration-a",
        );
        let authority = StaticAuthority {
            routes: vec![route],
            fail: None,
        };
        let secret = b"dGhpc19zZWNyZXRfaXNfZm9yX3Rlc3Rpbmdfb25seQ==";
        let secrets = StaticSecrets {
            values: vec![(
                "secret://provider/current".to_owned(),
                RotatingSecret::new(secret.to_vec(), None).expect("valid secret"),
            )],
            fail: None,
        };

        let error = verify_zendesk_and_mint(
            &authority,
            &secrets,
            ZendeskIngress {
                integration_key: "tenant123",
                timestamp_header: "2021-03-25T05:09:27Z",
                signature_header: "S/RDdU2ERmj7LHDDoWJ2onPYH+t7/f2+LTM4dL7UiLM=",
                raw_body: br#"{"event":"ticket.updated","id":123}"#,
            },
            1_616_648_967,
            300,
            b"too-short",
        )
        .expect_err("private-hop keys shorter than 256 bits fail closed");
        assert_eq!(error, ServiceError::InvalidPrivateHopKey);
    }
}
