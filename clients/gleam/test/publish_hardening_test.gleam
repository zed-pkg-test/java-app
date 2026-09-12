import gleam/bit_array
import gleam/http/request.{type Request}
import gleam/http/response.{type Response}
import gleam/httpc
import gleam/string
import zed_pkg_client as client

fn forbidden_transport(
  _request: Request(BitArray),
  _timeout_ms: Int,
) -> Result(Response(BitArray), httpc.HttpError) {
  panic as "transport must not run"
}

pub fn malformed_publish_meta_fails_before_transport_test() {
  let zed =
    client.new("https://registry.zpkg.tech")
    |> client.with_token("token")
    |> client.with_transport(forbidden_transport)
  let result =
    client.publish(
      zed,
      "acme",
      "kit",
      "1.2.0",
      "not-json",
      bit_array.from_string("bytes"),
    )
  case result {
    Error(client.InvalidResponse(message: _)) -> Nil
    other ->
      panic as {
        "expected malformed metadata rejection, got " <> string.inspect(other)
      }
  }
}
