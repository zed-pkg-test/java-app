use crate::error::{OrmError, Result};
use crate::ir::{OrmType, Table};
use crate::policy::Policy;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "snake_case")]
pub enum ShapeKind {
    Row,
    Create,
    Update,
    Patch,
    PublicRead,
    PublicCreate,
    PublicUpdate,
    PublicPatch,
}

impl ShapeKind {
    #[must_use]
    pub const fn is_public(self) -> bool {
        return matches!(
            self,
            Self::PublicRead | Self::PublicCreate | Self::PublicUpdate | Self::PublicPatch
        );
    }

    #[must_use]
    pub const fn requires_non_empty_object(self) -> bool {
        return matches!(self, Self::Patch | Self::PublicPatch);
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct ShapeField {
    pub db_name: String,
    pub wire_name: String,
    pub ty: OrmType,
    pub nullable: bool,
    pub required: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct Shape {
    pub table: String,
    pub kind: ShapeKind,
    pub fields: Vec<ShapeField>,
}

pub fn derive_shape(table: &Table, policy: &Policy, kind: ShapeKind) -> Result<Shape> {
    policy.validate_table(table)?;
    let table_policy = policy.table_policy(table);

    if kind.is_public() && !table_policy.public_surface {
        return Err(OrmError::Invalid(format!(
            "cannot derive public shape for {} without public_surface = true",
            table.qualified_name()
        )));
    }

    let fields = table
        .columns
        .iter()
        .filter_map(|column| {
            let name = &column.db_name;
            let include = match kind {
                ShapeKind::Row => true,
                ShapeKind::Create => !table_policy.generated.contains(name),
                ShapeKind::Update | ShapeKind::Patch => {
                    !table_policy.generated.contains(name) && !table_policy.immutable.contains(name)
                }
                ShapeKind::PublicRead => table_policy.public_read.contains(name),
                ShapeKind::PublicCreate => {
                    table_policy.public_create.contains(name)
                        && !table_policy.generated.contains(name)
                }
                ShapeKind::PublicUpdate | ShapeKind::PublicPatch => {
                    table_policy.public_update.contains(name)
                        && !table_policy.generated.contains(name)
                        && !table_policy.immutable.contains(name)
                }
            };

            if !include {
                return None;
            }

            let required = match kind {
                ShapeKind::Row | ShapeKind::PublicRead => true,
                ShapeKind::Create | ShapeKind::PublicCreate => {
                    !column.nullable && !table_policy.defaulted.contains(name)
                }
                ShapeKind::Update | ShapeKind::PublicUpdate => true,
                ShapeKind::Patch | ShapeKind::PublicPatch => false,
            };
            let wire_name = table_policy
                .wire_names
                .get(name)
                .cloned()
                .unwrap_or_else(|| name.clone());

            return Some(ShapeField {
                db_name: name.clone(),
                wire_name,
                ty: column.ty.clone(),
                nullable: column.nullable,
                required,
            });
        })
        .collect();

    return Ok(Shape {
        table: table.qualified_name(),
        kind,
        fields,
    });
}
