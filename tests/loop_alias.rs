use oreslang_format::{format_source, is_formatted};

#[test]
fn bare_for_alias_preserves_iterators_literals_and_comments() {
    let src = r#"pub routine main() -> void {
  for {
    break;
  }
  for const item of items {
    consume(item);
  }
  stdio.stdout.write("for {");
  // for {
  loop {
    break;
  }
}
"#;
    let formatted = format_source(src).unwrap();
    assert!(formatted.contains("  loop {\n    break;\n  }"));
    assert!(formatted.contains("for const item of items {"));
    assert!(formatted.contains("stdio.stdout.write(\"for {\");"));
    assert!(formatted.contains("// for {"));
    assert!(!formatted.contains("  for {"));
    assert!(is_formatted(&formatted).unwrap());
}

#[test]
fn comments_do_not_change_the_bare_loop_decision() {
    let src = r#"for /* legacy */ {
  break;
}
for /* keyword */ do
  break;
done
for /* iterator */ const item of items {
  consume(item);
}
"#;
    let formatted = format_source(src).unwrap();
    assert!(formatted.contains("loop /* legacy */ {"));
    assert!(formatted.contains("loop /* keyword */ do"));
    assert!(formatted.contains("for /* iterator */ const item of items {"));
    assert!(is_formatted(&formatted).unwrap());
}
