# Backtick template literals

Backticks delimit a string expression; ${expr} interpolates a full Oreslang
expression. For now, interpolation is an explicit **String** concatenation:
convert numbers with a conversion function (for example, `${integer(value)}`)
and escape context-sensitive values explicitly.

```ores
val message = `hello ${name}!`;
val json = `{"schema":"oreslang-otel.v1","value":${integer(value)},"message":${quote(message)}}`;
```

The scanner expands template literals into parenthesized string concatenation
before parsing. Every embedded expression is parenthesized so its operators keep
their normal precedence. Interpolations may contain quotes, nested braces,
nested templates and ordinary comments. Empty `${}` interpolations
are rejected.

Backslash escapes match ordinary string behavior, including `\n`,
`\r`, `\t`, `\uXXXX`, `\\`, `\`` and
`\${` (a literal interpolation marker). Newlines inside a template
are preserved as newlines in the resulting string.

**JSON safety:** templates do not automatically JSON-quote values. In a JSON
template, use a JSON string encoder such as `quote(text)` inside
interpolation, and use integer encoding without surrounding quotes for numeric
fields. For JSON Lines, keep the template on one physical line or explicitly
remove embedded newlines before writing; use one println per complete record.

This initial language feature does not promise a single allocation, implicit
stringification, or a specialized StringBuilder; it lowers to ordinary String
concatenation and retains the current optimizer's behavior.
