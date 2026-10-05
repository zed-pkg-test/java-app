#![forbid(unsafe_code)]
#![allow(clippy::needless_return)]

use std::{collections::HashMap, sync::Arc};

use axum::{
    Json, Router,
    extract::{Path, Request, State},
    http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode, header},
    middleware::Next,
    response::{Html, IntoResponse, Response},
    routing::get,
};
use ores_forms_lib_core::{FormDefinition, QuestionDefinition, QuestionKind};
use serde_json::json;
use tokio::sync::RwLock;

const JSON_MEDIA_TYPE: &str = "application/json";
const TYPED_FORM_PATH_PREFIX: &str = "/v1/forms/";
const FORM_CSP: &str = "default-src 'none'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

#[derive(Clone, Default)]
pub struct ReadState {
    forms: Arc<RwLock<HashMap<String, FormDefinition>>>,
}

impl ReadState {
    pub async fn seed(&self, form: FormDefinition) {
        self.forms.write().await.insert(form.id.clone(), form);
    }
}

pub fn router(state: ReadState) -> Router {
    return Router::new()
        .route("/v1/forms/{form_id}", get(get_form))
        .route("/forms/{form_id}", get(get_form_ssr))
        .route("/components/forms/{form_id}", get(get_form_component))
        .route("/assets/ores-form.js", get(component_script))
        .layer(axum::middleware::from_fn(enforce_json_read_accept))
        .with_state(state);
}

type ApiError = (StatusCode, Json<serde_json::Value>);

async fn enforce_json_read_accept(request: Request, next: Next) -> Response {
    if request.method() != Method::GET || !request.uri().path().starts_with(TYPED_FORM_PATH_PREFIX)
    {
        return next.run(request).await;
    }

    if !accept_headers_allow_json(request.headers()) {
        return StatusCode::NOT_ACCEPTABLE.into_response();
    }

    return next.run(request).await;
}

async fn get_form(
    State(state): State<ReadState>,
    Path(form_id): Path<String>,
) -> Result<Json<FormDefinition>, ApiError> {
    return load_form(&state, &form_id).await.map(Json);
}

async fn get_form_ssr(
    State(state): State<ReadState>,
    Path(form_id): Path<String>,
) -> Result<Response, ApiError> {
    let form = load_form(&state, &form_id).await?;
    let component = render_form_component(&form);
    let title = escape_html(&form.title);
    let form_id = escape_html(&form.id);
    let body = format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>{title}</title><script defer src=\"/assets/ores-form.js\"></script></head><body><main><ores-form form-id=\"{form_id}\" data-ssr=\"true\">{component}</ores-form></main></body></html>"
    );
    return Ok(hardened_html(body));
}

async fn get_form_component(
    State(state): State<ReadState>,
    Path(form_id): Path<String>,
) -> Result<Response, ApiError> {
    let form = load_form(&state, &form_id).await?;
    return Ok(hardened_html(render_form_component(&form)));
}

async fn component_script() -> Response {
    let mut response = (
        [
            (header::CONTENT_TYPE, "text/javascript; charset=utf-8"),
            (header::CACHE_CONTROL, "public, max-age=300"),
        ],
        include_str!("../assets/ores-form.js"),
    )
        .into_response();
    response.headers_mut().insert(
        HeaderName::from_static("x-content-type-options"),
        HeaderValue::from_static("nosniff"),
    );
    return response;
}

async fn load_form(state: &ReadState, form_id: &str) -> Result<FormDefinition, ApiError> {
    return state
        .forms
        .read()
        .await
        .get(form_id)
        .cloned()
        .ok_or_else(not_found);
}

fn not_found() -> ApiError {
    return (
        StatusCode::NOT_FOUND,
        Json(json!({"ok": false, "code": "form_not_found"})),
    );
}

fn accept_headers_allow_json(headers: &HeaderMap) -> bool {
    let values = headers.get_all(header::ACCEPT);
    let mut saw_accept = false;
    let mut best_specificity: Option<u8> = None;
    let mut best_quality = 0.0_f32;

    for value in values.iter() {
        saw_accept = true;
        let Ok(value) = value.to_str() else {
            return false;
        };
        update_json_accept_preference(value, &mut best_specificity, &mut best_quality);
    }

    if !saw_accept {
        return true;
    }

    return best_specificity.is_some() && best_quality > 0.0;
}

#[cfg(test)]
#[cfg(test)]
fn accepts_json(value: &str) -> bool {
    let mut best_specificity = None;
    let mut best_quality = 0.0_f32;
    update_json_accept_preference(value, &mut best_specificity, &mut best_quality);
    return best_specificity.is_some() && best_quality > 0.0;
}

fn update_json_accept_preference(
    value: &str,
    best_specificity: &mut Option<u8>,
    best_quality: &mut f32,
) {
    for item in value.split(',') {
        let mut pieces = item.split(';');
        let media_type = pieces
            .next()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        let specificity = match media_type.as_str() {
            JSON_MEDIA_TYPE => 2,
            "application/*" => 1,
            "*/*" => 0,
            _ => continue,
        };

        let mut quality = 1.0_f32;
        for parameter in pieces {
            let mut pair = parameter.trim().splitn(2, '=');
            let name = pair.next().unwrap_or_default().trim();
            let raw_value = pair.next().unwrap_or_default().trim();
            if name.eq_ignore_ascii_case("q") {
                quality = match raw_value.parse::<f32>() {
                    Ok(parsed) if (0.0..=1.0).contains(&parsed) => parsed,
                    _ => 0.0,
                };
            }
        }

        match *best_specificity {
            None => {
                *best_specificity = Some(specificity);
                *best_quality = quality;
            }
            Some(current) if specificity > current => {
                *best_specificity = Some(specificity);
                *best_quality = quality;
            }
            Some(current) if specificity == current && quality > *best_quality => {
                *best_quality = quality;
            }
            _ => {}
        }
    }
}

fn hardened_html(body: String) -> Response {
    let mut response = Html(body).into_response();
    let headers = response.headers_mut();
    headers.insert(
        HeaderName::from_static("content-security-policy"),
        HeaderValue::from_static(FORM_CSP),
    );
    headers.insert(
        HeaderName::from_static("x-content-type-options"),
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        HeaderName::from_static("referrer-policy"),
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    return response;
}

fn render_form_component(form: &FormDefinition) -> String {
    let description = form
        .description
        .as_deref()
        .map(|value| format!("<p>{}</p>", escape_html(value)))
        .unwrap_or_default();
    let questions = form
        .questions
        .iter()
        .map(render_question)
        .collect::<Vec<_>>()
        .join("");
    let revision_id = form.revision_id.as_deref().unwrap_or_default();
    let submit = if revision_id.is_empty() {
        "<p role=\"status\">This form revision is not available for submission.</p><button type=\"submit\" disabled>Submit</button>".to_owned()
    } else {
        "<button type=\"submit\">Submit</button>".to_owned()
    };

    return format!(
        "<form method=\"post\" action=\"/v1/responses\" data-ores-form-root=\"true\" data-form-id=\"{}\" data-revision-id=\"{}\" data-submit-endpoint=\"/v1/responses\"><h1>{}</h1>{description}{questions}{submit}</form>",
        escape_html(&form.id),
        escape_html(revision_id),
        escape_html(&form.title),
    );
}

fn render_question(question: &QuestionDefinition) -> String {
    let id = escape_html(&question.id);
    let title = escape_html(&question.title);
    let description = question
        .description
        .as_deref()
        .map(|value| format!("<small>{}</small>", escape_html(value)))
        .unwrap_or_default();
    let required = if question.required { " required" } else { "" };

    match question.kind {
        QuestionKind::Paragraph => {
            return format!(
                "<section><label for=\"{id}\"><span>{title}</span>{description}</label><textarea id=\"{id}\" name=\"answers[{id}]\" data-question-id=\"{id}\"{required}></textarea></section>"
            );
        }
        QuestionKind::ShortText => {
            return format!(
                "<section><label for=\"{id}\"><span>{title}</span>{description}</label><input id=\"{id}\" name=\"answers[{id}]\" type=\"text\" data-question-id=\"{id}\"{required}></section>"
            );
        }
        QuestionKind::SingleChoice | QuestionKind::MultiChoice => {}
    }

    let input_type = if matches!(question.kind, QuestionKind::SingleChoice) {
        "radio"
    } else {
        "checkbox"
    };
    let native_required =
        if question.required && matches!(question.kind, QuestionKind::SingleChoice) {
            " required"
        } else {
            ""
        };
    let options = question
        .options
        .as_deref()
        .unwrap_or_default()
        .iter()
        .map(|option| {
            format!(
                "<label><input type=\"{input_type}\" name=\"answers[{id}]\" value=\"{}\"{native_required}>{}</label>",
                escape_html(&option.value),
                escape_html(&option.label)
            )
        })
        .collect::<Vec<_>>()
        .join("");
    let required_group = if question.required { "true" } else { "false" };
    return format!(
        "<section><fieldset data-question-id=\"{id}\" data-required=\"{required_group}\"><legend>{title}</legend>{description}{options}</fieldset></section>"
    );
}

fn escape_html(value: &str) -> String {
    return value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;");
}

#[cfg(test)]
mod tests {
    use super::*;
    use ores_forms_lib_core::ChoiceOption;

    fn sample_form() -> FormDefinition {
        return FormDefinition {
            id: "feedback".into(),
            title: "Feedback <unsafe>".into(),
            description: Some("Tell us what happened".into()),
            revision_id: Some("rev_1".into()),
            questions: vec![
                QuestionDefinition {
                    id: "name".into(),
                    kind: QuestionKind::ShortText,
                    title: "Name".into(),
                    required: true,
                    description: None,
                    options: None,
                },
                QuestionDefinition {
                    id: "rating".into(),
                    kind: QuestionKind::SingleChoice,
                    title: "Rating".into(),
                    required: true,
                    description: Some("Pick one".into()),
                    options: Some(vec![ChoiceOption {
                        value: "good".into(),
                        label: "Good".into(),
                    }]),
                },
                QuestionDefinition {
                    id: "topics".into(),
                    kind: QuestionKind::MultiChoice,
                    title: "Topics".into(),
                    required: true,
                    description: None,
                    options: Some(vec![
                        ChoiceOption {
                            value: "a".into(),
                            label: "A".into(),
                        },
                        ChoiceOption {
                            value: "b".into(),
                            label: "B".into(),
                        },
                    ]),
                },
            ],
        };
    }

    #[test]
    fn typed_read_accept_is_fail_closed_until_shared_binary_dispatch_lands() {
        assert!(accepts_json("application/json"));
        assert!(accepts_json("application/json; charset=utf-8"));
        assert!(accepts_json("application/*"));
        assert!(accepts_json("*/*"));
        assert!(accepts_json("application/msgpack, application/json;q=0.5"));
        assert!(!accepts_json("application/msgpack"));
        assert!(!accepts_json("application/cbor"));
        assert!(!accepts_json("application/x-protobuf"));
        assert!(!accepts_json("application/json;q=0"));
        assert!(!accepts_json("application/json;q=garbage"));
        assert!(!accepts_json("application/json;q=0, */*;q=1"));
        assert!(!accepts_json("application/*;q=0, */*;q=1"));
        assert!(accepts_json("application/json;q=0.2, application/*;q=1"));
    }

    #[test]
    fn multiple_accept_lines_are_combined_semantically() {
        let mut headers = HeaderMap::new();
        headers.append(
            header::ACCEPT,
            HeaderValue::from_static("application/msgpack"),
        );
        headers.append(
            header::ACCEPT,
            HeaderValue::from_static("application/json;q=0.2"),
        );
        assert!(accept_headers_allow_json(&headers));

        let mut rejected = HeaderMap::new();
        rejected.append(header::ACCEPT, HeaderValue::from_static("*/*;q=1"));
        rejected.append(
            header::ACCEPT,
            HeaderValue::from_static("application/json;q=0"),
        );
        assert!(!accept_headers_allow_json(&rejected));

        let mut type_rejected = HeaderMap::new();
        type_rejected.append(header::ACCEPT, HeaderValue::from_static("*/*;q=1"));
        type_rejected.append(
            header::ACCEPT,
            HeaderValue::from_static("application/*;q=0"),
        );
        assert!(!accept_headers_allow_json(&type_rejected));
        assert!(accept_headers_allow_json(&HeaderMap::new()));
    }

    #[test]
    fn component_escapes_untrusted_text_and_renders_current_contract() {
        let rendered = render_form_component(&sample_form());
        assert!(rendered.contains("Feedback &lt;unsafe&gt;"));
        assert!(!rendered.contains("Feedback <unsafe>"));
        assert!(rendered.contains("method=\"post\" action=\"/v1/responses\""));
        assert!(!rendered.contains("method=\"get\""));
        assert!(rendered.contains("data-form-id=\"feedback\""));
        assert!(rendered.contains("data-revision-id=\"rev_1\""));
        assert!(rendered.contains("data-question-id=\"name\""));
        assert!(rendered.contains("type=\"radio\""));
        assert!(rendered.contains("value=\"good\""));
    }

    #[test]
    fn multi_choice_required_marks_group_without_requiring_every_checkbox() {
        let rendered = render_form_component(&sample_form());
        let fieldset = rendered
            .split("data-question-id=\"topics\"")
            .nth(1)
            .expect("topics fieldset")
            .split("</fieldset>")
            .next()
            .expect("fieldset closes");
        assert!(fieldset.contains("data-required=\"true\""));
        assert!(!fieldset.contains(" required"));
    }

    #[test]
    fn document_bootstraps_same_origin_web_component() {
        let form = sample_form();
        let component = render_form_component(&form);
        let body = format!(
            "<!doctype html><ores-form form-id=\"{}\" data-ssr=\"true\">{component}</ores-form>",
            escape_html(&form.id)
        );
        assert!(body.contains("<ores-form form-id=\"feedback\" data-ssr=\"true\">"));
        assert!(component.contains("data-ores-form-root=\"true\""));
    }

    #[test]
    fn missing_revision_disables_submission() {
        let mut form = sample_form();
        form.revision_id = None;
        let rendered = render_form_component(&form);
        assert!(rendered.contains("not available for submission"));
        assert!(rendered.contains("<button type=\"submit\" disabled>"));
    }

    #[test]
    fn hardened_html_sets_form_security_headers() {
        let response = hardened_html("<p>ok</p>".to_owned());
        assert_eq!(
            response
                .headers()
                .get(HeaderName::from_static("content-security-policy"))
                .and_then(|value| value.to_str().ok()),
            Some(FORM_CSP)
        );
        assert_eq!(
            response
                .headers()
                .get(header::CACHE_CONTROL)
                .and_then(|value| value.to_str().ok()),
            Some("no-store")
        );
    }
}
