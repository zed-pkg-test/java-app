# zed_pkg_client (Erlang)

Erlang/OTP SDK for the [zed-pkg](https://zpkg.tech) registry. Stdlib only
(`httpc` + the OTP 27+ `json` module + `crypto`); maps mirror the JSON
Schemas in `zed-interfaces/schemas/`.

```erlang
{ok, Client0} = zed_pkg_client:new(<<"https://registry.zpkg.tech">>),
{ok, Package} = zed_pkg_client:get_package(Client0, <<"acme">>, <<"kit">>),
{ok, Version} = zed_pkg_client:get_version(Client0, <<"acme">>, <<"kit">>, <<"1.2.0">>),
{ok, Bytes} = zed_pkg_client:download_artifact(Client0, Version), % sha256-verified

Client = zed_pkg_client:with_token(Client0, <<"zpkg_...">>),
{ok, _} = zed_pkg_client:claim_org(Client, <<"acme">>),
{ok, _} = zed_pkg_client:publish(Client, <<"acme">>, <<"kit">>, <<"1.2.0">>, MetaJson, ArtifactBin).
```

Requires OTP 27 or newer. Verify with `rebar3 compile && rebar3 eunit`.
