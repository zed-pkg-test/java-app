-module(zed_pkg_client_adversarial_tests).

-include_lib("eunit/include/eunit.hrl").

mutated_client_configuration_fails_before_transport_test() ->
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:9"),
    TraversingBase = Client#{base => <<"https://registry.test/%2e%2e/admin">>},
    ?assertMatch(
        {error, {invalid_input, _}},
        zed_pkg_client:get_package(TraversingBase, "acme", "kit")
    ),
    InvalidTimeout = Client#{timeout => 0},
    ?assertMatch(
        {error, {invalid_configuration, _}},
        zed_pkg_client:get_package(InvalidTimeout, "acme", "kit")
    ).

relative_download_escape_variants_fail_before_transport_test() ->
    {ok, Client} = zed_pkg_client:new("http://127.0.0.1:9/gateway"),
    Digest = binary:encode_hex(crypto:hash(sha256, <<"artifact">>), lowercase),
    lists:foreach(
        fun(DownloadUrl) ->
            Version = #{
                <<"download_url">> => DownloadUrl,
                <<"sha256">> => Digest,
                <<"size">> => 8
            },
            ?assertMatch(
                {error, {_, _}},
                zed_pkg_client:download_artifact(Client, Version)
            )
        end,
        [
            <<"../escape">>,
            <<"%2e%2e/escape">>,
            <<"//evil.example/artifact">>,
            <<"/absolute/artifact">>,
            <<"artifacts%2Fescape">>,
            <<"artifacts\\escape">>
        ]
    ).

relative_download_query_is_preserved_without_bearer_test() ->
    Bytes = <<"signed-artifact">>,
    Digest = binary:encode_hex(crypto:hash(sha256, Bytes), uppercase),
    {Port, Ref} = spawn_server(200, "application/octet-stream", Bytes),
    {ok, Client0} = zed_pkg_client:new(loopback_url(Port) ++ "/gateway"),
    Client = zed_pkg_client:with_token(Client0, <<"registry-secret">>),
    Version = #{
        <<"download_url">> => <<"artifacts/hash?signature=one%2Btwo&expires=3">>,
        <<"sha256">> => Digest,
        <<"size">> => byte_size(Bytes)
    },
    ?assertEqual({ok, Bytes}, zed_pkg_client:download_artifact(Client, Version)),
    Request = captured_request(Ref),
    ?assertMatch(
        {_, _},
        binary:match(
            Request,
            <<"GET /gateway/artifacts/hash?signature=one%2Btwo&expires=3 HTTP/1.1">>
        )
    ),
    ?assertEqual(nomatch, binary:match(Request, <<"authorization">>)).

redirect_response_is_not_followed_test() ->
    Parent = self(),
    DestinationRef = make_ref(),
    {ok, DestinationListen} = gen_tcp:listen(0, [binary, {active, false}, {reuseaddr, true}]),
    {ok, DestinationPort} = inet:port(DestinationListen),
    DestinationPid = spawn_link(fun() ->
        case gen_tcp:accept(DestinationListen, 1200) of
            {ok, Socket} ->
                Parent ! {unexpected_redirect_request, DestinationRef},
                gen_tcp:close(Socket);
            {error, timeout} ->
                Parent ! {no_redirect_request, DestinationRef};
            {error, Reason} ->
                Parent ! {redirect_destination_error, DestinationRef, Reason}
        end,
        gen_tcp:close(DestinationListen)
    end),

    Location = "http://127.0.0.1:" ++ integer_to_list(DestinationPort) ++ "/escaped",
    {SourcePort, SourceRef} = spawn_redirect_server(Location),
    {ok, Client} = zed_pkg_client:new(loopback_url(SourcePort)),
    ?assertMatch(
        {error, {api_error, 302, <<"http_302">>, _}},
        zed_pkg_client:get_package(Client, "acme", "kit")
    ),
    _ = captured_request(SourceRef),
    receive
        {no_redirect_request, DestinationRef} -> ok;
        {unexpected_redirect_request, DestinationRef} ->
            ?assert(false);
        {redirect_destination_error, DestinationRef, Reason} ->
            error({redirect_destination_error, Reason})
    after 3000 ->
        exit(DestinationPid, kill),
        error(redirect_destination_timeout)
    end.

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

spawn_redirect_server(Location) ->
    Parent = self(),
    Ref = make_ref(),
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}, {reuseaddr, true}]),
    {ok, Port} = inet:port(Listen),
    spawn_link(fun() ->
        {ok, Socket} = gen_tcp:accept(Listen, 5000),
        Request = read_request(Socket, <<>>),
        Parent ! {captured_request, Ref, Request},
        Response = [
            "HTTP/1.1 302 Found\r\n",
            "location: ", Location, "\r\n",
            "content-length: 0\r\n",
            "connection: close\r\n\r\n"
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
