-module(zed_pkg_client_tests).

-include_lib("eunit/include/eunit.hrl").

%%--------------------------------------------------------------------
%% Pure helpers
%%--------------------------------------------------------------------

paths_match_contract_test() ->
    ?assertEqual(<<"/v1/packages/acme/kit">>, zed_pkg_client:package_path("acme", "kit")),
    ?assertEqual(
        <<"/v1/packages/acme/kit/versions/1.2.0">>,
        zed_pkg_client:version_path("acme", "kit", "1.2.0")
    ),
    ?assertEqual(<<"/v1/artifacts/abc">>, zed_pkg_client:artifact_path("abc")),
    ?assertEqual(
        <<"/v1/packages/acme/kit/versions/1.2.0/yank">>,
        zed_pkg_client:yank_path("acme", "kit", "1.2.0")
    ).

path_segments_are_percent_encoded_test() ->
    ?assertEqual(
        <<"/v1/packages/acme/kit/versions/release%20candidate%2F1">>,
        zed_pkg_client:version_path("acme", "kit", "release candidate/1")
    ).

download_limit_caps_at_ceiling_test() ->
    Ceiling = 104857600,
    ?assertEqual(Ceiling, zed_pkg_client:download_limit(0)),
    ?assertEqual(10 + 1048576, zed_pkg_client:download_limit(10)),
    ?assertEqual(Ceiling, zed_pkg_client:download_limit(Ceiling)).

insecure_download_urls_are_rejected_test() ->
    Base = <<"https://registry.zpkg.tech">>,
    ?assertMatch(
        {error, {insecure_download_url, _}},
        zed_pkg_client:allowed_download_url(<<"http://evil.example/a">>, Base)
    ),
    ?assertMatch(
        {error, {insecure_download_url, _}},
        zed_pkg_client:allowed_download_url(<<"file:///etc/passwd">>, Base)
    ),
    ?assertEqual(
        {ok, <<"https://cdn.example/a">>},
        zed_pkg_client:allowed_download_url(<<"https://cdn.example/a">>, Base)
    ),
    ?assertEqual(
        {ok, <<"http://127.0.0.1:8080/a">>},
        zed_pkg_client:allowed_download_url(<<"http://127.0.0.1:8080/a">>, Base)
    ),
    ?assertEqual(
        {ok, <<"http://localhost/a">>},
        zed_pkg_client:allowed_download_url(<<"http://localhost/a">>, Base)
    ),
    %% An http registry base opts in to plaintext downloads.
    ?assertEqual(
        {ok, <<"http://mirror.internal/a">>},
        zed_pkg_client:allowed_download_url(
            <<"http://mirror.internal/a">>, <<"http://registry.internal">>
        )
    ).

sha_verification_test() ->
    Good = binary:encode_hex(crypto:hash(sha256, <<"zed">>), lowercase),
    ?assertEqual(ok, zed_pkg_client:verify_sha256(<<"zed">>, Good)),
    ?assertMatch(
        {error, {sha256_mismatch, _}},
        zed_pkg_client:verify_sha256(<<"zed">>, <<"00">>)
    ).

multipart_body_layout_test() ->
    Body = zed_pkg_client:multipart_body(
        <<"BOUNDARY">>, <<"{\"manifest\":{}}">>, <<"acme-kit-1.2.0.tar.gz">>, <<"bytes">>
    ),
    ?assertMatch({_, _}, binary:match(Body, <<"name=\"meta\"">>)),
    ?assertMatch(
        {_, _},
        binary:match(Body, <<"name=\"artifact\"; filename=\"acme-kit-1.2.0.tar.gz\"">>)
    ),
    Suffix = <<"--BOUNDARY--\r\n">>,
    ?assertEqual(
        Suffix,
        binary:part(Body, byte_size(Body) - byte_size(Suffix), byte_size(Suffix))
    ).

invalid_timeout_is_refused_test() ->
    ?assertMatch({error, {invalid_configuration, _}}, zed_pkg_client:new("https://x", 0)).

%%--------------------------------------------------------------------
%% Round-trips against a one-shot loopback server
%%--------------------------------------------------------------------

%% Serve one HTTP response on a random loopback port; the captured raw
%% request (headers included) is sent to the test process.
spawn_server(Status, ContentType, Body) ->
    Parent = self(),
    Ref = make_ref(),
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}, {reuseaddr, true}]),
    {ok, Port} = inet:port(Listen),
    spawn_link(fun() ->
        {ok, Socket} = gen_tcp:accept(Listen, 5000),
        Request = read_request(Socket, <<>>),
        Parent ! {captured_request, Ref, Request},
        Reason = integer_to_list(Status),
        Response = [
            "HTTP/1.1 ", integer_to_list(Status), " ", Reason, "\r\n",
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
        {_, _} ->
            Acc;
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

get_package_round_trip_test() ->
    Body = iolist_to_binary(
        json:encode(#{
            <<"org">> => <<"acme">>,
            <<"name">> => <<"kit">>,
            <<"vcs">> => <<"git">>,
            <<"repo_url">> => <<"https://github.com/acme/kit">>,
            <<"latest">> => <<"1.2.0">>,
            <<"versions">> => [<<"1.2.0">>]
        })
    ),
    {Port, Ref} = spawn_server(200, "application/json", Body),
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    {ok, Package} = zed_pkg_client:get_package(Client, "acme", "kit"),
    ?assertEqual(<<"1.2.0">>, maps:get(<<"latest">>, Package)),
    Request = captured_request(Ref),
    ?assertMatch({_, _}, binary:match(Request, <<"GET /v1/packages/acme/kit HTTP/1.1">>)),
    %% Reads are unauthenticated.
    ?assertEqual(nomatch, binary:match(Request, <<"authorization">>)).

api_errors_carry_the_registry_code_test() ->
    Body = iolist_to_binary(
        json:encode(#{<<"code">> => <<"org_taken">>, <<"message">> => <<"claimed">>})
    ),
    {Port, Ref} = spawn_server(409, "application/json", Body),
    {ok, Client0} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    Client = zed_pkg_client:with_token(Client0, <<"zpkg_t">>),
    ?assertEqual(
        {error, {api_error, 409, <<"org_taken">>, <<"claimed">>}},
        zed_pkg_client:claim_org(Client, "acme")
    ),
    Request = captured_request(Ref),
    ?assertMatch({_, _}, binary:match(Request, <<"authorization: Bearer zpkg_t">>)).

non_json_error_bodies_map_to_http_status_test() ->
    {Port, Ref} = spawn_server(500, "text/plain", <<"boom">>),
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    ?assertEqual(
        {error, {api_error, 500, <<"http_500">>, <<"boom">>}},
        zed_pkg_client:get_version(Client, "acme", "kit", "1.2.0")
    ),
    _ = captured_request(Ref),
    ok.

download_verifies_sha_and_omits_auth_test() ->
    Bytes = <<"artifact-bytes">>,
    Digest = binary:encode_hex(crypto:hash(sha256, Bytes), lowercase),
    {Port, Ref} = spawn_server(200, "application/octet-stream", Bytes),
    {ok, Client0} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    Client = zed_pkg_client:with_token(Client0, <<"zpkg_t">>),
    Version = #{
        <<"download_url">> => <<>>,
        <<"sha256">> => Digest,
        <<"size">> => byte_size(Bytes)
    },
    ?assertEqual({ok, Bytes}, zed_pkg_client:download_artifact(Client, Version)),
    Request = captured_request(Ref),
    ?assertMatch(
        {_, _}, binary:match(Request, <<"GET /v1/artifacts/", Digest/binary>>)
    ),
    %% Bearer token must not leak to the download host.
    ?assertEqual(nomatch, binary:match(Request, <<"authorization">>)).

download_rejects_sha_mismatch_test() ->
    {Port, Ref} = spawn_server(200, "application/octet-stream", <<"tampered">>),
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    Version = #{<<"download_url">> => <<>>, <<"sha256">> => <<"00">>, <<"size">> => 8},
    ?assertMatch(
        {error, {sha256_mismatch, _}},
        zed_pkg_client:download_artifact(Client, Version)
    ),
    _ = captured_request(Ref),
    ok.

publish_round_trip_test() ->
    Body = iolist_to_binary(
        json:encode(#{
            <<"org">> => <<"acme">>,
            <<"name">> => <<"kit">>,
            <<"version">> => <<"1.2.0">>,
            <<"sha256">> => <<"abc">>
        })
    ),
    {Port, Ref} = spawn_server(200, "application/json", Body),
    {ok, Client0} = zed_pkg_client:new("http://127.0.0.1:" ++ integer_to_list(Port)),
    Client = zed_pkg_client:with_token(Client0, <<"zpkg_t">>),
    Meta = <<"{\"manifest\":{\"package\":{\"org\":\"acme\",\"name\":\"kit\",\"version\":\"1.2.0\"}}}">>,
    {ok, Response} = zed_pkg_client:publish(Client, "acme", "kit", "1.2.0", Meta, <<"bytes">>),
    ?assertEqual(<<"abc">>, maps:get(<<"sha256">>, Response)),
    Request = captured_request(Ref),
    ?assertMatch(
        {_, _}, binary:match(Request, <<"PUT /v1/packages/acme/kit/versions/1.2.0 HTTP/1.1">>)
    ),
    ?assertMatch({_, _}, binary:match(Request, <<"authorization: Bearer zpkg_t">>)),
    ?assertMatch({_, _}, binary:match(Request, <<"multipart/form-data; boundary=zedpkg">>)).
