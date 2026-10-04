-module(zed_pkg_client_hardening_tests).

-include_lib("eunit/include/eunit.hrl").

-define(MAX_ERROR_BODY_BYTES, 16384).
-define(MAX_TOKEN_BYTES, 8192).

invalid_bases_and_dot_segments_are_rejected_test() ->
    InvalidBases = [
        "relative/path",
        "ftp://registry.test",
        "https://user:secret@registry.test",
        "https://registry.test?tenant=one",
        "https://registry.test#fragment",
        "https://registry.test/../admin",
        "https://registry.test/%2e%2e/admin",
        "https://registry.test/a%2Fb"
    ],
    lists:foreach(
        fun(Base) ->
            ?assertMatch({error, {_, _}}, zed_pkg_client:new(Base))
        end,
        InvalidBases
    ),
    ?assertEqual(
        <<"/v1/packages/%2E%2E/kit">>,
        zed_pkg_client:package_path("..", "kit")
    ).

hostile_segments_and_missing_tokens_fail_before_transport_test() ->
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:9"),
    lists:foreach(
        fun(Value) ->
            ?assertMatch(
                {error, {invalid_input, _}},
                zed_pkg_client:get_package(Client, Value, "kit")
            )
        end,
        [<<>>, <<"   ">>, <<".">>, <<"..">>, <<"line\nbreak">>]
    ),
    Overlong = binary:copy(<<"x">>, 257),
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:get_version(Client, "acme", "kit", Overlong)
    ),
    ?assertMatch(
        {error, {missing_token, _}},
        zed_pkg_client:claim_org(Client, "acme")
    ),
    ?assertMatch(
        {error, {missing_token, _}},
        zed_pkg_client:restore(Client, "acme", "kit", "1.2.0")
    ),
    Meta = <<"{\"manifest\":{\"package\":{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}">>,
    ?assertMatch(
        {error, {missing_token, _}},
        zed_pkg_client:publish(Client, "acme", "kit", "1.2.0", Meta, <<"bytes">>)
    ),
    UnsafeToken = zed_pkg_client:with_token(Client, <<"token\r\nheader">>),
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:claim_org(UnsafeToken, "acme")
    ),
    BoundaryToken = binary:copy(<<"t">>, ?MAX_TOKEN_BYTES),
    BoundaryClient = zed_pkg_client:with_token(Client, BoundaryToken),
    ?assertEqual(?MAX_TOKEN_BYTES, byte_size(maps:get(token, BoundaryClient))),
    OverlongToken = binary:copy(<<"t">>, ?MAX_TOKEN_BYTES + 1),
    OverlongClient = zed_pkg_client:with_token(Client, OverlongToken),
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:claim_org(OverlongClient, "acme")
    ),
    MutatedControlClient = Client#{
        token => <<"token\r\nheader">>,
        token_error => undefined
    },
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:restore(MutatedControlClient, "acme", "kit", "1.2.0")
    ),
    MutatedTypeClient = Client#{token => 42, token_error => undefined},
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:claim_org(MutatedTypeClient, "acme")
    ),
    ?assertMatch(
        {error, {invalid_configuration, _}},
        zed_pkg_client:publish(
            not_a_client,
            "acme",
            "kit",
            "1.2.0",
            Meta,
            <<"bytes">>
        )
    ),
    ?assertMatch(
        {error, {invalid_configuration, _}},
        zed_pkg_client:with_token(not_a_client, <<"token">>)
    ).

artifact_url_policy_rejects_credentials_fragments_and_unsupported_schemes_test() ->
    Base = <<"https://registry.zpkg.tech">>,
    lists:foreach(
        fun(Url) ->
            ?assertMatch(
                {error, {insecure_download_url, _}},
                zed_pkg_client:allowed_download_url(Url, Base)
            )
        end,
        [
            <<"file:///etc/passwd">>,
            <<"https://user:secret@cdn.example/a">>,
            <<"https://cdn.example/a#fragment">>,
            <<"http://evil.example/a">>
        ]
    ),
    ?assertEqual(
        {ok, <<"https://cdn.example/a?signature=one">>},
        zed_pkg_client:allowed_download_url(
            <<"https://cdn.example/a?signature=one">>, Base
        )
    ),
    ?assertEqual(
        {ok, <<"http://[::1]:8080/a">>},
        zed_pkg_client:allowed_download_url(<<"http://[::1]:8080/a">>, Base)
    ).

sha_verification_accepts_uppercase_hex_test() ->
    Digest = binary:encode_hex(crypto:hash(sha256, <<"zed">>), uppercase),
    ?assertEqual(ok, zed_pkg_client:verify_sha256(<<"zed">>, Digest)).

blank_structured_codes_fall_back_to_http_status_test() ->
    Body = <<"{\"code\":\"   \",\"message\":\"remote detail\"}">>,
    {Port, Ref} = spawn_server(409, "application/json", Body),
    {ok, Client0} = zed_pkg_client:new(loopback_url(Port)),
    Client = zed_pkg_client:with_token(Client0, <<" token ">>),
    ?assertEqual(
        {error, {api_error, 409, <<"http_409">>, <<"remote detail">>}},
        zed_pkg_client:claim_org(Client, "acme")
    ),
    Request = captured_request(Ref),
    ?assertMatch({_, _}, binary:match(Request, <<"authorization: Bearer token">>)).

oversized_error_bodies_are_not_retained_test() ->
    Body = binary:copy(<<"provider-secret">>, ?MAX_ERROR_BODY_BYTES),
    {Port, Ref} = spawn_server(502, "text/plain", Body),
    {ok, Client} = zed_pkg_client:new(loopback_url(Port)),
    ?assertEqual(
        {error,
            {api_error, 502, <<"http_502">>,
                <<"registry error body exceeded the client limit">>}},
        zed_pkg_client:get_package(Client, "acme", "kit")
    ),
    _ = captured_request(Ref),
    ok.

relative_downloads_preserve_gateway_and_use_no_bearer_test() ->
    Bytes = <<"artifact-bytes">>,
    Digest = binary:encode_hex(crypto:hash(sha256, Bytes), uppercase),
    {Port, Ref} = spawn_server(200, "application/octet-stream", Bytes),
    {ok, Client0} = zed_pkg_client:new(loopback_url(Port) ++ "/gateway"),
    Client = zed_pkg_client:with_token(Client0, <<"registry-secret">>),
    Version = #{
        <<"download_url">> => <<"artifacts/hash">>,
        <<"sha256">> => Digest,
        <<"size">> => byte_size(Bytes)
    },
    ?assertEqual({ok, Bytes}, zed_pkg_client:download_artifact(Client, Version)),
    Request = captured_request(Ref),
    ?assertMatch({_, _}, binary:match(Request, <<"GET /gateway/artifacts/hash HTTP/1.1">>)),
    ?assertEqual(nomatch, binary:match(Request, <<"authorization">>)).

verified_file_download_replaces_destination_atomically_test() ->
    Bytes = <<"verified-artifact">>,
    Digest = binary:encode_hex(crypto:hash(sha256, Bytes), lowercase),
    {Port, Ref} = spawn_server(200, "application/octet-stream", Bytes),
    {ok, Client} = zed_pkg_client:new(loopback_url(Port)),
    Version = #{
        <<"download_url">> => <<>>,
        <<"sha256">> => Digest,
        <<"size">> => byte_size(Bytes)
    },
    Root = filename:join(temp_dir(), "zed-erlang-" ++ integer_to_list(erlang:unique_integer([positive]))),
    Destination = filename:join([Root, "nested", "artifact.tar.gz"]),
    ok = filelib:ensure_dir(Destination),
    ok = file:write_file(Destination, <<"old">>),
    ok = zed_pkg_client:download_artifact(Client, Version, Destination),
    ?assertEqual({ok, Bytes}, file:read_file(Destination)),
    {ok, Entries} = file:list_dir(filename:dirname(Destination)),
    ?assertEqual([], [Entry || Entry <- Entries, string:find(Entry, ".zed-") =/= nomatch]),
    _ = captured_request(Ref),
    ok = file:del_dir_r(Root).

restore_posts_false_and_publish_uses_a_fixed_filename_test() ->
    RestoreBody = <<"{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\",\"yanked\":false}">>,
    {RestorePort, RestoreRef} = spawn_server(200, "application/json", RestoreBody),
    {ok, RestoreClient0} = zed_pkg_client:new(loopback_url(RestorePort)),
    RestoreClient = zed_pkg_client:with_token(RestoreClient0, <<"token">>),
    {ok, Restored} = zed_pkg_client:restore(RestoreClient, "acme", "kit", "1.2.0"),
    ?assertEqual(false, maps:get(<<"yanked">>, Restored)),
    RestoreRequest = captured_request(RestoreRef),
    ?assertMatch({_, _}, binary:match(RestoreRequest, <<"\"yanked\":false">>)),

    PublishBody = <<"{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\",\"sha256\":\"abc\"}">>,
    {PublishPort, PublishRef} = spawn_server(200, "application/json", PublishBody),
    {ok, PublishClient0} = zed_pkg_client:new(loopback_url(PublishPort)),
    PublishClient = zed_pkg_client:with_token(PublishClient0, <<"token">>),
    Meta = <<"{\"manifest\":{\"package\":{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}">>,
    {ok, _} = zed_pkg_client:publish(
        PublishClient, "acme", "kit", "1.2.0", Meta, <<"bytes">>
    ),
    PublishRequest = captured_request(PublishRef),
    ?assertMatch({_, _}, binary:match(PublishRequest, <<"filename=\"artifact.tar.gz\"">>)).

publish_coordinate_mismatch_fails_before_transport_test() ->
    {ok, Client0} = zed_pkg_client:new("http://127.0.0.1:9"),
    Client = zed_pkg_client:with_token(Client0, <<"token">>),
    Meta = <<"{\"manifest\":{\"package\":{\"org\":\"other\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}">>,
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:publish(Client, "acme", "kit", "1.2.0", Meta, <<"bytes">>)
    ).

spawn_server(Status, ContentType, Body) ->
    Parent = self(),
    Ref = make_ref(),
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}, {reuseaddr, true}]),
    {ok, Port} = inet:port(Listen),
    spawn_link(fun() ->
        {ok, Socket} = gen_tcp:accept(Listen, 5000),
        Request = read_request(Socket, <<>>),
        Parent ! {captured_request, Ref, Request},
        Response = [
            "HTTP/1.1 ", integer_to_list(Status), " status\r\n",
            "content-type: ", ContentType, "\r\n",
            "content-length: ", integer_to_list(byte_size(Body)), "\r\n",
            "connection: close\r\n\r\n",
            Body
        ],
        ok = gen_tcp:send(Socket, Response),
        gen_tcp:close(Socket),
        gen_tcp:close(Listen)
    end),
    {Port, Ref}.

read_request(Socket, Acc) ->
    case binary:match(Acc, <<"\r\n\r\n">>) of
        {_, _} -> Acc;
        nomatch ->
            case gen_tcp:recv(Socket, 0, 2000) of
                {ok, Data} -> read_request(Socket, <<Acc/binary, Data/binary>>);
                {error, _} -> Acc
            end
    end.

captured_request(Ref) ->
    receive
        {captured_request, Ref, Request} -> Request
    after 5000 -> error(no_request_captured)
    end.

loopback_url(Port) ->
    "http://127.0.0.1:" ++ integer_to_list(Port).

temp_dir() ->
    case os:getenv("TMPDIR") of
        false -> "/tmp";
        Value -> Value
    end.
