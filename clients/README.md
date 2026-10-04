# Client language matrix

The directory names follow ecosystem conventions while Zed package target names
follow canonical language tokens.

| Requested surface | Canonical directory | Zed target |
| --- | --- | --- |
| TypeScript / JavaScript | `typescript/` | `nodejs` |
| Python 3 | `python/` | `python` |
| Go / Golang | `go/` | `golang` |
| Gleam / Gleamlang | `gleam/` | `gleam` |
| Erlang | `erlang/` | `erlang` |
| Elixir | `elixir/` | `elixir` |
| Dart | `dart/` | `dart` |
| Rust | `rust/` | `rust` |
| Java | `java/` | `java` |
| Ruby | `ruby/` | `ruby` |
| PHP | `php/` | `php` |
| Kotlin | `kotlin/` | `kotlin` |
| Swift | `swift/` | `swift` |

The TypeScript package has explicit runtime entry points under
`typescript/src/runtimes/{nodejs,deno,bun,edge}`. They share the fetch-based
core and exist to make runtime intent explicit without publishing four copies of
the same npm-compatible artifact.
