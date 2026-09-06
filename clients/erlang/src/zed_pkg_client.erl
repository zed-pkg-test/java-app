%% Erlang SDK for the zed-pkg registry. Stdlib only (httpc + json + crypto);
%% maps mirror the JSON Schemas in zed-interfaces/schemas/.
-module(zed_pkg_client).

-export([
    new/1,
    new/2,
    with_token/2,
    get_package/3,
    get_version/4,
    search/2,
    claim_org/2,
    set_yanked/5,
    yank/5,
    restore/4,
    download_artifact/2,
    download_artifact/3,
    publish/6,
    %% Exported for tests and reuse.
    package_path/2,
    version_path/3,
    artifact_path/1,
    yank_path/3,
    encode_segment/1,
    download_limit/1,
    allowed_download_url/2,
    verify_sha256/2,
    multipart_body/4
]).

-define(DEFAULT_REGISTRY_URL, <<"https://registry.zpkg.tech">>).

%% Bounds every request (connect + read), in milliseconds.
-define(DEFAULT_TIMEOUT, 30000).

%% Independent response ceilings.
-define(MAX_JSON_RESPONSE_BYTES, 16777216).
-define(MAX_ERROR_BODY_BYTES, 16384).
-define(MAX_PATH_SEGMENT_BYTES, 256).
-define(MAX_TOKEN_BYTES, 8192).

%% Hard ceiling on artifact downloads, matching the server's
%% MAX_ARTIFACT_BYTES default (100 MiB); plus the slack added to a version's
%% declared size.
-define(MAX_ARTIFACT_BYTES, 104857600).
-define(DOWNLOAD_SLACK, 1048576).

%%--------------------------------------------------------------------
%% Construction
%%--------------------------------------------------------------------

new(BaseUrl) ->
    new(BaseUrl, ?DEFAULT_TIMEOUT).

new(BaseUrl, Timeout) when is_integer(Timeout), Timeout > 0 ->
    case validate_base(BaseUrl) of
        {ok, Base} ->
            {ok, #{
                base => Base,
                token => undefined,
                token_error => undefined,
                timeout => Timeout
            }};
        Error ->
            Error
    end;
new(_BaseUrl, _Timeout) ->
    {error, {invalid_configuration, <<"timeout must be a positive integer">>}}.

%% Attach a bearer token used for authenticated calls (claim_org, yank,
%% publish). Never sent on artifact downloads. Blank tokens are absent; tokens
%% containing control characters are retained as an error so auth fails before
%% transport instead of creating an unsafe header.
with_token(Client, Token) when is_map(Client) ->
    case normalize_token(Token) of
        {ok, Normalized} -> Client#{token => Normalized, token_error => undefined};
        {error, Reason} -> Client#{token => undefined, token_error => Reason}
    end;
with_token(_Client, _Token) ->
    {error, {invalid_configuration, <<"client must be a map returned by new/1">>}}.

normalize_token(undefined) ->
    {ok, undefined};
normalize_token(Value) when is_binary(Value); is_list(Value); is_atom(Value) ->
    Token = trim_binary(text(Value)),
    case Token of
        <<>> ->
            {ok, undefined};
        _ when byte_size(Token) > ?MAX_TOKEN_BYTES ->
            {error,
                {invalid_input,
                    <<"token must not exceed 8192 UTF-8 bytes">>}};
        _ ->
            case has_control(Token) of
                true ->
                    {error,
                        {invalid_input,
                            <<"token must not contain control characters">>}};
                false -> {ok, Token}
            end
    end;
normalize_token(_) ->
    {error, {invalid_input, <<"token must be text">>}}.

validate_base(Value) ->
    Base = trim_binary(text(Value)),
    try uri_string:parse(Base) of
        Parsed when is_map(Parsed) ->
            Scheme = maps:get(scheme, Parsed, undefined),
            Host = maps:get(host, Parsed, undefined),
            InvalidAuthority = maps:is_key(userinfo, Parsed),
            InvalidSuffix = maps:is_key(query, Parsed) orelse maps:is_key(fragment, Parsed),
            case
                (Scheme =:= <<"http">> orelse Scheme =:= <<"https">>)
                andalso is_binary(Host)
                andalso Host =/= <<>>
                andalso not InvalidAuthority
                andalso not InvalidSuffix
            of
                true ->
                    case validate_path(maps:get(path, Parsed, <<>>), <<"registry URL path">>) of
                        ok -> {ok, trim_trailing_slashes(Base)};
                        PathError -> PathError
                    end;
                false ->
                    {error,
                        {invalid_configuration,
                            <<"registry URL must be credential-free absolute HTTP(S) without query or fragment">>}}
            end;
        _ ->
            {error, {invalid_configuration, <<"registry URL is invalid">>}}
    catch
        _:_ -> {error, {invalid_configuration, <<"registry URL is invalid">>}}
    end.

trim_trailing_slashes(<<>>) ->
    <<>>;
trim_trailing_slashes(Base) ->
    case binary:last(Base) of
        $/ -> trim_trailing_slashes(binary:part(Base, 0, byte_size(Base) - 1));
        _ -> Base
    end.

client_base(Client) when is_map(Client) ->
    case maps:find(base, Client) of
        {ok, Base} -> validate_base(Base);
        error -> {error, {invalid_configuration, <<"client base URL is missing">>}}
    end;
client_base(_) ->
    {error, {invalid_configuration, <<"client must be a map returned by new/1">>}}.

client_timeout(Client) ->
    case maps:get(timeout, Client, ?DEFAULT_TIMEOUT) of
        Timeout when is_integer(Timeout), Timeout > 0 -> {ok, Timeout};
        _ -> {error, {invalid_configuration, <<"timeout must be a positive integer">>}}
    end.

%%--------------------------------------------------------------------
%% Paths
%%--------------------------------------------------------------------

encode_segment(Segment0) ->
    Segment = text(Segment0),
    case Segment of
        <<".">> -> <<"%2E">>;
        <<"..">> -> <<"%2E%2E">>;
        _ -> iolist_to_binary(uri_string:quote(Segment))
    end.

package_path(Org, Name) ->
    <<"/v1/packages/", (encode_segment(Org))/binary, "/", (encode_segment(Name))/binary>>.

version_path(Org, Name, Version) ->
    <<(package_path(Org, Name))/binary, "/versions/", (encode_segment(Version))/binary>>.

artifact_path(Sha256) ->
    <<"/v1/artifacts/", (encode_segment(Sha256))/binary>>.

yank_path(Org, Name, Version) ->
    <<(version_path(Org, Name, Version))/binary, "/yank">>.

validate_segment(Value, Name) ->
    Segment = text(Value),
    case
        trim_binary(Segment) =:= <<>>
        orelse Segment =:= <<".">>
        orelse Segment =:= <<"..">>
        orelse byte_size(Segment) > ?MAX_PATH_SEGMENT_BYTES
        orelse has_control(Segment)
    of
        true ->
            {error,
                {invalid_input,
                    <<Name/binary,
                        " must be nonblank, non-dot, bounded, and free of control characters">>}};
        false ->
            {ok, Segment}
    end.

validate_path(Path0, Name) ->
    Path = text(Path0),
    validate_path_segments(binary:split(Path, <<"/">>, [global]), Name, 1).

validate_path_segments([], _Name, _Index) ->
    ok;
validate_path_segments([<<>> | Rest], Name, Index) ->
    validate_path_segments(Rest, Name, Index + 1);
validate_path_segments([Encoded | Rest], Name, Index) ->
    try text(uri_string:unquote(Encoded)) of
        Decoded ->
            case binary:match(Decoded, <<"/">>) =/= nomatch
                orelse binary:match(Decoded, <<"\\">>) =/= nomatch
            of
                true ->
                    {error,
                        {invalid_input,
                            <<Name/binary, " segments must not contain encoded separators">>}};
                false ->
                    SegmentName = <<Name/binary, " segment ", (integer_to_binary(Index))/binary>>,
                    case validate_segment(Decoded, SegmentName) of
                        {ok, _} -> validate_path_segments(Rest, Name, Index + 1);
                        SegmentError -> SegmentError
                    end
            end
    catch
        _:_ -> {error, {invalid_input, <<Name/binary, " contains invalid percent encoding">>}}
    end.

checked_package_path(Org, Name) ->
    case validate_segment(Org, <<"org">>) of
        {ok, CheckedOrg} ->
            case validate_segment(Name, <<"name">>) of
                {ok, CheckedName} -> {ok, package_path(CheckedOrg, CheckedName)};
                NameError -> NameError
            end;
        OrgError -> OrgError
    end.

checked_version_path(Org, Name, Version) ->
    case checked_package_path(Org, Name) of
        {ok, PackagePath} ->
            case validate_segment(Version, <<"version">>) of
                {ok, CheckedVersion} ->
                    {ok, <<PackagePath/binary, "/versions/", (encode_segment(CheckedVersion))/binary>>};
                VersionError -> VersionError
            end;
        PackageError -> PackageError
    end.

checked_yank_path(Org, Name, Version) ->
    case checked_version_path(Org, Name, Version) of
        {ok, Path} -> {ok, <<Path/binary, "/yank">>};
        VersionPathError -> VersionPathError
    end.

%%--------------------------------------------------------------------
%% Download policy and integrity
%%--------------------------------------------------------------------

download_limit(Size) when is_integer(Size), Size > 0 ->
    min(Size + ?DOWNLOAD_SLACK, ?MAX_ARTIFACT_BYTES);
download_limit(_Size) ->
    ?MAX_ARTIFACT_BYTES.

allowed_download_url(Raw, Base) ->
    RawText = text(Raw),
    try uri_string:parse(RawText) of
        Parsed when is_map(Parsed) ->
            Scheme = maps:get(scheme, Parsed, undefined),
            Host = maps:get(host, Parsed, undefined),
            HasUserInfo = maps:is_key(userinfo, Parsed),
            HasFragment = maps:is_key(fragment, Parsed),
            case
                is_binary(Host)
                andalso Host =/= <<>>
                andalso not HasUserInfo
                andalso not HasFragment
            of
                false ->
                    {error,
                        {insecure_download_url,
                            <<"download URL contains credentials, fragment, or no host">>}};
                true ->
                    case Scheme of
                        <<"https">> -> {ok, RawText};
                        <<"http">> ->
                            case is_loopback(Host) orelse is_http_base(text(Base)) of
                                true -> {ok, RawText};
                                false ->
                                    {error,
                                        {insecure_download_url,
                                            <<"refusing artifact download over `http`">>}}
                            end;
                        _ ->
                            {error,
                                {insecure_download_url,
                                    <<"refusing artifact download over an unsupported scheme">>}}
                    end
            end;
        _ ->
            {error, {insecure_download_url, <<"download URL is invalid">>}}
    catch
        _:_ -> {error, {insecure_download_url, <<"download URL is invalid">>}}
    end.

is_http_base(<<"http://", _/binary>>) -> true;
is_http_base(_) -> false.

is_loopback(<<"localhost">>) ->
    true;
is_loopback(<<"[::1]">>) ->
    true;
is_loopback(Host) when is_binary(Host) ->
    case inet:parse_address(binary_to_list(Host)) of
        {ok, Address} ->
            case Address of
                {127, _, _, _} -> true;
                {0, 0, 0, 0, 0, 0, 0, 1} -> true;
                _ -> false
            end;
        {error, _} ->
            false
    end.

resolve_download_url(DownloadUrl0, Base, Sha256) ->
    DownloadUrl = trim_binary(text(DownloadUrl0)),
    case DownloadUrl of
        <<>> ->
            case validate_segment(Sha256, <<"sha256">>) of
                {ok, CheckedSha} -> {ok, <<Base/binary, (artifact_path(CheckedSha))/binary>>};
                ShaError -> ShaError
            end;
        _ ->
            try uri_string:parse(DownloadUrl) of
                Parsed when is_map(Parsed) ->
                    case maps:get(scheme, Parsed, undefined) of
                        undefined ->
                            case
                                maps:get(host, Parsed, undefined) =:= undefined
                                andalso not maps:is_key(userinfo, Parsed)
                                andalso not maps:is_key(fragment, Parsed)
                                andalso not starts_with_slash(DownloadUrl)
                            of
                                true ->
                                    case validate_path(maps:get(path, Parsed, <<>>), <<"download_url">>) of
                                        ok -> allowed_download_url(<<Base/binary, "/", DownloadUrl/binary>>, Base);
                                        RelativePathError -> RelativePathError
                                    end;
                                false ->
                                    {error,
                                        {insecure_download_url,
                                            <<"relative download URL contains an authority, fragment, or absolute path">>}}
                            end;
                        _ ->
                            allowed_download_url(DownloadUrl, Base)
                    end;
                _ ->
                    {error, {insecure_download_url, <<"download URL is invalid">>}}
            catch
                _:_ -> {error, {insecure_download_url, <<"download URL is invalid">>}}
            end
    end.

starts_with_slash(<<"/", _/binary>>) -> true;
starts_with_slash(_) -> false.

verify_sha256(Bytes, Expected) ->
    Actual = binary:encode_hex(crypto:hash(sha256, Bytes), lowercase),
    ExpectedLower = lower_binary(text(Expected)),
    case Actual =:= ExpectedLower of
        true -> ok;
        false -> {error, {sha256_mismatch, #{expected => text(Expected), actual => Actual}}}
    end.

%%--------------------------------------------------------------------
%% Operations
%%--------------------------------------------------------------------

get_package(Client, Org, Name) ->
    case checked_package_path(Org, Name) of
        {ok, Path} -> request_json(Client, get, Path, undefined, false);
        PathError -> PathError
    end.

get_version(Client, Org, Name, Version) ->
    case checked_version_path(Org, Name, Version) of
        {ok, Path} -> request_json(Client, get, Path, undefined, false);
        PathError -> PathError
    end.

search(Client, Query) ->
    Path = <<"/v1/search?q=", (encode_segment(Query))/binary>>,
    request_json(Client, get, Path, undefined, false).

claim_org(Client, Slug) ->
    case validate_segment(Slug, <<"slug">>) of
        {ok, CheckedSlug} ->
            request_json(Client, post, <<"/v1/orgs">>, #{<<"slug">> => CheckedSlug}, true);
        SlugError -> SlugError
    end.

set_yanked(Client, Org, Name, Version, Yanked) when is_boolean(Yanked) ->
    case checked_yank_path(Org, Name, Version) of
        {ok, Path} ->
            request_json(Client, post, Path, #{<<"yanked">> => Yanked}, true);
        PathError -> PathError
    end;
set_yanked(_Client, _Org, _Name, _Version, _Yanked) ->
    {error, {invalid_input, <<"yanked must be a boolean">>}}.

yank(Client, Org, Name, Version, Yanked) ->
    set_yanked(Client, Org, Name, Version, Yanked).

restore(Client, Org, Name, Version) ->
    set_yanked(Client, Org, Name, Version, false).

download_artifact(Client, Version) when is_map(Version) ->
    case client_base(Client) of
        {ok, Base} ->
            DownloadUrl = text(maps:get(<<"download_url">>, Version, <<>>)),
            Sha256 = text(maps:get(<<"sha256">>, Version, <<>>)),
            Size = maps:get(<<"size">>, Version, 0),
            case resolve_download_url(DownloadUrl, Base, Sha256) of
                {ok, Url} ->
                    %% Deliberately no authorization header: download_url may
                    %% point at a third-party host and the bearer must not leak.
                    case http_request(Client, get, Url, [], undefined) of
                        {ok, Status, Body} when Status >= 200, Status < 300 ->
                            Limit = download_limit(Size),
                            case byte_size(Body) > Limit of
                                true -> {error, {artifact_too_large, Limit}};
                                false ->
                                    case verify_sha256(Body, Sha256) of
                                        ok -> {ok, Body};
                                        VerifyError -> VerifyError
                                    end
                            end;
                        {ok, Status, Body} ->
                            {error, decode_api_error(Status, Body)};
                        RequestError -> RequestError
                    end;
                UrlError -> UrlError
            end;
        BaseError -> BaseError
    end;
download_artifact(_Client, _Version) ->
    {error, {invalid_input, <<"version metadata must be a map">>}}.

%% Download to DestPath atomically: the verified bytes are written to a new
%% sibling file, synced, and renamed into place. Partial or failed downloads
%% never replace an existing destination.
download_artifact(Client, Version, DestPath) ->
    case download_artifact(Client, Version) of
        {ok, Body} -> write_file_atomic(DestPath, Body);
        DownloadError -> DownloadError
    end.

write_file_atomic(DestPath0, Body) ->
    DestPath = path_text(DestPath0),
    case filelib:ensure_dir(DestPath) of
        ok ->
            Dir = filename:dirname(DestPath),
            BaseName = filename:basename(DestPath),
            Nonce = integer_to_list(erlang:unique_integer([positive, monotonic])),
            Temp = filename:join(Dir, "." ++ BaseName ++ ".zed-" ++ Nonce ++ ".tmp"),
            case file:open(Temp, [write, binary, raw, exclusive]) of
                {ok, Handle} ->
                    WriteResult =
                        case file:write(Handle, Body) of
                            ok -> file:sync(Handle);
                            WriteError -> WriteError
                        end,
                    CloseResult = file:close(Handle),
                    case WriteResult of
                        ok ->
                            case CloseResult of
                                ok ->
                                    case file:rename(Temp, DestPath) of
                                        ok -> ok;
                                        RenameError ->
                                            _ = file:delete(Temp),
                                            RenameError
                                    end;
                                CloseError ->
                                    _ = file:delete(Temp),
                                    CloseError
                            end;
                        WriteFailure ->
                            _ = file:delete(Temp),
                            WriteFailure
                    end;
                OpenError -> OpenError
            end;
        EnsureError -> EnsureError
    end.

publish(Client, Org, Name, Version, MetaJson, Artifact) when is_binary(Artifact) ->
    case auth_headers(Client, true) of
        {ok, Headers} ->
            case checked_version_path(Org, Name, Version) of
                {ok, Path} ->
                    case byte_size(Artifact) > ?MAX_ARTIFACT_BYTES of
                        true ->
                            {error, {artifact_too_large, ?MAX_ARTIFACT_BYTES}};
                        false ->
                            ExpectedOrg = text(Org),
                            ExpectedName = text(Name),
                            ExpectedVersion = text(Version),
                            case decode_publish_coordinate(MetaJson) of
                                {ok, {MetaOrg, MetaName, MetaVersion}} ->
                                    case {
                                        MetaOrg =:= ExpectedOrg,
                                        MetaName =:= ExpectedName,
                                        MetaVersion =:= ExpectedVersion
                                    } of
                                        {true, true, true} ->
                                            case client_base(Client) of
                                                {ok, Base} ->
                                                    Boundary =
                                                        <<"zedpkg",
                                                            (binary:encode_hex(
                                                                crypto:strong_rand_bytes(16), lowercase
                                                            ))/binary>>,
                                                    Body = multipart_body(
                                                        Boundary,
                                                        text(MetaJson),
                                                        <<"artifact.tar.gz">>,
                                                        Artifact
                                                    ),
                                                    ContentType =
                                                        <<"multipart/form-data; boundary=", Boundary/binary>>,
                                                    Url = <<Base/binary, Path/binary>>,
                                                    case http_request(
                                                        Client,
                                                        put,
                                                        Url,
                                                        Headers,
                                                        {ContentType, Body}
                                                    ) of
                                                        {ok, Status, ResponseBody}
                                                        when Status >= 200, Status < 300 ->
                                                            decode_success_json(ResponseBody);
                                                        {ok, Status, ResponseBody} ->
                                                            {error,
                                                                decode_api_error(Status, ResponseBody)};
                                                        RequestError -> RequestError
                                                    end;
                                                BaseError -> BaseError
                                            end;
                                        _ ->
                                            {error,
                                                {invalid_input,
                                                    <<"publish route and meta.manifest.package coordinates differ">>}}
                                    end;
                                DecodeError -> DecodeError
                            end
                    end;
                PathError -> PathError
            end;
        AuthError -> AuthError
    end;
publish(_Client, _Org, _Name, _Version, _MetaJson, _Artifact) ->
    {error, {invalid_input, <<"artifact must be a binary">>}}.

decode_publish_coordinate(MetaJson) ->
    try json:decode(text(MetaJson)) of
        #{
            <<"manifest">> := #{
                <<"package">> := #{
                    <<"org">> := Org,
                    <<"name">> := Name,
                    <<"version">> := Version
                }
            }
        } ->
            case validate_segment(Org, <<"meta.manifest.package.org">>) of
                {ok, CheckedOrg} ->
                    case validate_segment(Name, <<"meta.manifest.package.name">>) of
                        {ok, CheckedName} ->
                            case validate_segment(
                                Version, <<"meta.manifest.package.version">>
                            ) of
                                {ok, CheckedVersion} ->
                                    {ok, {CheckedOrg, CheckedName, CheckedVersion}};
                                VersionError -> VersionError
                            end;
                        NameError -> NameError
                    end;
                OrgError -> OrgError
            end;
        _ ->
            {error, {invalid_input, <<"meta.manifest.package is required">>}}
    catch
        _:_ -> {error, {invalid_input, <<"publish metadata is invalid JSON">>}}
    end.

multipart_body(Boundary, MetaJson, Filename, Artifact) ->
    iolist_to_binary([
        "--", Boundary, "\r\n",
        "content-disposition: form-data; name=\"meta\"\r\n\r\n",
        MetaJson, "\r\n",
        "--", Boundary, "\r\n",
        "content-disposition: form-data; name=\"artifact\"; filename=\"", Filename, "\"\r\n",
        "content-type: application/octet-stream\r\n\r\n",
        Artifact, "\r\n",
        "--", Boundary, "--\r\n"
    ]).

%%--------------------------------------------------------------------
%% Transport
%%--------------------------------------------------------------------

request_json(Client, Method, Path, Body, Authorized) ->
    case client_base(Client) of
        {ok, Base} ->
            case auth_headers(Client, Authorized) of
                {ok, Headers} ->
                    Url = <<Base/binary, Path/binary>>,
                    Payload =
                        case Body of
                            undefined -> undefined;
                            _ -> {<<"application/json">>, iolist_to_binary(json:encode(Body))}
                        end,
                    case http_request(Client, Method, Url, Headers, Payload) of
                        {ok, Status, ResponseBody} when Status >= 200, Status < 300 ->
                            decode_success_json(ResponseBody);
                        {ok, Status, ResponseBody} ->
                            {error, decode_api_error(Status, ResponseBody)};
                        RequestError -> RequestError
                    end;
                AuthError -> AuthError
            end;
        BaseError -> BaseError
    end.

auth_headers(Client, true) when is_map(Client) ->
    case maps:get(token_error, Client, undefined) of
        undefined ->
            %% Client maps remain transparent for compatibility, so callers can mutate
            %% them after construction. Revalidate the live token on every authenticated
            %% operation instead of trusting only with_token/2 state.
            case normalize_token(maps:get(token, Client, undefined)) of
                {ok, undefined} ->
                    {error,
                        {missing_token,
                            <<"authenticated registry operation requires a nonblank bearer token">>}};
                {ok, Token} ->
                    {ok, [{"authorization", "Bearer " ++ binary_to_list(Token)}]};
                {error, Reason} -> {error, Reason}
            end;
        TokenError -> {error, TokenError}
    end;
auth_headers(_Client, true) ->
    {error, {invalid_configuration, <<"client must be a map returned by new/1">>}};
auth_headers(_Client, false) ->
    {ok, []}.

http_request(Client, Method, Url, Headers0, Payload) ->
    case client_timeout(Client) of
        {ok, Timeout} ->
            _ = application:ensure_all_started(inets),
            _ = application:ensure_all_started(ssl),
            Headers = [{"accept", "application/json"} | Headers0],
            Request =
                case Payload of
                    undefined ->
                        {binary_to_list(Url), Headers};
                    {ContentType, RequestBody} ->
                        {binary_to_list(Url), Headers, binary_to_list(ContentType), RequestBody}
                end,
            HttpOptions = [
                {timeout, Timeout},
                {connect_timeout, Timeout},
                {autoredirect, false}
            ],
            Options = [{body_format, binary}],
            case httpc:request(Method, Request, HttpOptions, Options) of
                {ok, {{_Version, Status, _Reason}, _ResponseHeaders, ResponseBody}} ->
                    {ok, Status, ResponseBody};
                {error, Reason} ->
                    {error, {transport, Reason}}
            end;
        TimeoutError -> TimeoutError
    end.

%%--------------------------------------------------------------------
%% Decoding
%%--------------------------------------------------------------------

decode_success_json(Body) when byte_size(Body) > ?MAX_JSON_RESPONSE_BYTES ->
    {error, {response_too_large, ?MAX_JSON_RESPONSE_BYTES}};
decode_success_json(Body) ->
    decode_json(Body).

decode_json(<<>>) ->
    {error, {protocol, empty_json_body}};
decode_json(Body) ->
    try
        {ok, json:decode(Body)}
    catch
        Class:Reason -> {error, {protocol, {invalid_json, Class, Reason}}}
    end.

decode_api_error(Status, Body) when byte_size(Body) > ?MAX_ERROR_BODY_BYTES ->
    {api_error, Status, http_status_code(Status), <<"registry error body exceeded the client limit">>};
decode_api_error(Status, Body) ->
    Fallback = http_status_code(Status),
    try json:decode(Body) of
        #{<<"code">> := Code0, <<"message">> := Message} ->
            Code =
                case trim_binary(text(Code0)) of
                    <<>> -> Fallback;
                    Value -> Value
                end,
            {api_error, Status, Code, text(Message)};
        _ ->
            {api_error, Status, Fallback, Body}
    catch
        _:_ -> {api_error, Status, Fallback, Body}
    end.

http_status_code(Status) ->
    <<"http_", (integer_to_binary(Status))/binary>>.

%%--------------------------------------------------------------------
%% Helpers
%%--------------------------------------------------------------------

text(Value) when is_binary(Value) -> Value;
text(Value) when is_list(Value) -> unicode:characters_to_binary(Value);
text(Value) when is_atom(Value) -> atom_to_binary(Value, utf8).

trim_binary(Value) ->
    unicode:characters_to_binary(string:trim(binary_to_list(text(Value)))).

lower_binary(Value) ->
    unicode:characters_to_binary(string:lowercase(binary_to_list(text(Value)))).

has_control(Binary) ->
    lists:any(fun(Byte) -> Byte < 32 orelse Byte =:= 127 end, binary_to_list(Binary)).

path_text(Value) when is_binary(Value) -> unicode:characters_to_list(Value);
path_text(Value) when is_list(Value) -> Value.
