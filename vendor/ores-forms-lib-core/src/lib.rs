#![forbid(unsafe_code)]
#![allow(clippy::needless_return)]

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum QuestionKind {
    ShortText,
    Paragraph,
    SingleChoice,
    MultiChoice,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChoiceOption {
    pub value: String,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct QuestionDefinition {
    pub id: String,
    pub kind: QuestionKind,
    pub title: String,
    pub required: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub options: Option<Vec<ChoiceOption>>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FormDefinition {
    pub id: String,
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "revisionId")]
    pub revision_id: Option<String>,
    #[serde(default)]
    pub questions: Vec<QuestionDefinition>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResponseAnswer {
    #[serde(alias = "questionId")]
    pub question_id: String,
    pub values: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResponseSubmission {
    #[serde(alias = "responseId")]
    pub response_id: String,
    #[serde(alias = "formId")]
    pub form_id: String,
    #[serde(alias = "revisionId")]
    pub revision_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub answers: Option<Vec<ResponseAnswer>>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ValidationIssue {
    pub path: String,
    pub code: String,
    pub message: String,
}

pub fn validate_form_definition(form: &FormDefinition) -> Vec<ValidationIssue> {
    let mut issues: Vec<ValidationIssue> = [
        ("id", form.id.as_str(), "form id must not be empty"),
        ("title", form.title.as_str(), "form title must not be empty"),
    ]
    .into_iter()
    .filter(|(_, value, _)| value.trim().is_empty())
    .map(|(path, _, message)| issue(path, "required", message))
    .collect();

    if let Some(revision_id) = form.revision_id.as_deref()
        && revision_id.trim().is_empty()
    {
        issues.push(issue(
            "revision_id",
            "required",
            "revision id must not be empty when present",
        ));
    }

    for (index, question) in form.questions.iter().enumerate() {
        for nested in validate_question_definition(question) {
            issues.push(ValidationIssue {
                path: format!("questions.{index}.{}", nested.path),
                code: nested.code,
                message: nested.message,
            });
        }
    }

    return issues;
}

pub fn validate_question_definition(question: &QuestionDefinition) -> Vec<ValidationIssue> {
    let mut issues: Vec<ValidationIssue> = [
        ("id", question.id.as_str(), "question id must not be empty"),
        (
            "title",
            question.title.as_str(),
            "question title must not be empty",
        ),
    ]
    .into_iter()
    .filter(|(_, value, _)| value.trim().is_empty())
    .map(|(path, _, message)| issue(path, "required", message))
    .collect();

    if matches!(question.kind, QuestionKind::SingleChoice | QuestionKind::MultiChoice) {
        match question.options.as_deref() {
            Some(options) if !options.is_empty() => {
                for (index, option) in options.iter().enumerate() {
                    if option.value.trim().is_empty() {
                        issues.push(issue(
                            &format!("options[{index}].value"),
                            "required",
                            "choice value must not be empty",
                        ));
                    }
                    if option.label.trim().is_empty() {
                        issues.push(issue(
                            &format!("options[{index}].label"),
                            "required",
                            "choice label must not be empty",
                        ));
                    }
                }
            }
            _ => issues.push(issue(
                "options",
                "required",
                "choice questions require at least one option",
            )),
        }
    }

    return issues;
}

pub fn validate_submission(submission: &ResponseSubmission) -> Vec<ValidationIssue> {
    let mut issues: Vec<ValidationIssue> = [
        ("response_id", submission.response_id.as_str()),
        ("form_id", submission.form_id.as_str()),
        ("revision_id", submission.revision_id.as_str()),
    ]
    .into_iter()
    .filter(|(_, value)| value.trim().is_empty())
    .map(|(path, _)| issue(path, "required", "value must not be empty"))
    .collect();

    if let Some(answers) = submission.answers.as_deref() {
        for (index, answer) in answers.iter().enumerate() {
            if answer.question_id.trim().is_empty() {
                issues.push(issue(
                    &format!("answers[{index}].question_id"),
                    "required",
                    "question id must not be empty",
                ));
            }
            if answer.values.iter().any(|value| value.trim().is_empty()) {
                issues.push(issue(
                    &format!("answers[{index}].values"),
                    "invalid",
                    "answer values must not contain empty values",
                ));
            }
        }
    }

    return issues;
}

fn issue(path: &str, code: &str, message: &str) -> ValidationIssue {
    return ValidationIssue {
        path: path.to_owned(),
        code: code.to_owned(),
        message: message.to_owned(),
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_form_has_no_issues() {
        let form = FormDefinition {
            id: "form_1".into(),
            title: "Feedback".into(),
            description: None,
            revision_id: None,
            questions: vec![QuestionDefinition {
                id: "question_1".into(),
                kind: QuestionKind::ShortText,
                title: "What should we improve?".into(),
                required: true,
                description: None,
                options: None,
            }],
        };
        assert!(validate_form_definition(&form).is_empty());
    }

    #[test]
    fn form_validation_prefixes_nested_question_paths() {
        let form = FormDefinition {
            id: "form_1".into(),
            title: "Feedback".into(),
            description: None,
            revision_id: None,
            questions: vec![QuestionDefinition {
                id: "".into(),
                kind: QuestionKind::ShortText,
                title: "Question".into(),
                required: false,
                description: None,
                options: None,
            }],
        };
        let issues = validate_form_definition(&form);
        assert_eq!(issues.len(), 1);
        assert_eq!(issues[0].path, "questions.0.id");
    }

    #[test]
    fn form_without_questions_remains_wire_compatible() {
        let value = serde_json::json!({
            "id": "form_1",
            "title": "Feedback",
            "description": null
        });
        let form: FormDefinition = serde_json::from_value(value).expect("deserialize");
        assert!(form.questions.is_empty());
    }

    #[test]
    fn response_submission_serializes_snake_case() {
        let submission = ResponseSubmission {
            response_id: "r_1".into(),
            form_id: "f_1".into(),
            revision_id: "rev_1".into(),
            answers: None,
        };
        let value = serde_json::to_value(submission).expect("serialize");
        assert_eq!(value["response_id"], "r_1");
        assert_eq!(value["form_id"], "f_1");
        assert_eq!(value["revision_id"], "rev_1");
    }

    #[test]
    fn choice_questions_require_non_empty_options() {
        let question = QuestionDefinition {
            id: "q_1".into(),
            kind: QuestionKind::SingleChoice,
            title: "Pick one".into(),
            required: true,
            description: None,
            options: None,
        };
        assert!(validate_question_definition(&question)
            .iter()
            .any(|entry| entry.path == "options"));
    }

    #[test]
    fn submission_accepts_authored_camel_case_aliases() {
        let value = serde_json::json!({
            "responseId": "r_2",
            "formId": "f_2",
            "revisionId": "rev_2",
            "answers": [{"questionId": "q_2", "values": ["ok"]}]
        });
        let decoded: ResponseSubmission = serde_json::from_value(value).expect("deserialize");
        assert_eq!(decoded.response_id, "r_2");
        assert_eq!(decoded.answers.expect("answers")[0].question_id, "q_2");
    }
}
