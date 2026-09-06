defmodule ZedPkgClient do
  @moduledoc "Core Elixir client for the zed-pkg registry."

  @default_registry "https://registry.zpkg.tech"
  @max_json_bytes 16 * 1024 * 1024
  @max_artifact_bytes 100 * 1024 * 1024

  defstruct [:base_url, :token, timeout: 30_000]

  defmodule Error do
    defexception [:status, :code, :registry_message]

    @impl true
    def message(error), do: "registry error #{error.status}: #{error.code}"
  end

  @type t :: %__MODULE__{base_url: String.t(), token: String.t() | nil, timeout: pos_integer()}

  @spec new(keyword()) :: t()
  def new(opts \\ []) do
    base_url = normalize_base(Keyword.get(opts, :registry_url, @default_registry))
    %__MODULE__{base_url: base_url, token: Keyword.get(opts, :token), timeout: Keyword.get(opts, :timeout, 30_000)}
  end

  def package(client, org, name),
    do: json(client, :get, "/v1/packages/#{segment(org)}/#{segment(name)}")

  def version(client, org, name, version),
    do: json(client, :get, "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}")

  def search(client, query),
    do: json(client, :get, "/v1/search?q=#{URI.encode_www_form(to_string(query))}")

  def claim_org(client, org),
    do: json(client, :post, "/v1/orgs", Jason.encode!(%{org: org}), true)

  def set_yanked(client, org, name, version, yanked, reason \\ nil) do
    payload = %{yanked: !!yanked} |> maybe_reason(reason) |> Jason.encode!()
    json(
      client,
      :post,
      "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}/yank",
      payload,
      true
    )
  end

  def yank(client, org, name, version, reason \\ nil),
    do: set_yanked(client, org, name, version, true, reason)

  def restore(client, org, name, version),
    do: set_yanked(client, org, name, version, false)

  def publish(client, org, name, version, artifact_path, metadata) do
    artifact = File.read!(artifact_path)
    boundary = "zed-#{Base.url_encode64(:crypto.strong_rand_bytes(18), padding: false)}"
    body = multipart(boundary, Jason.encode!(metadata), Path.basename(artifact_path), artifact)

    with {:ok, response} <-
           request(
             client,
             :put,
             "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}",
             body,
             "multipart/form-data; boundary=#{boundary}",
             true,
             @max_json_bytes
           ),
         {:ok, decoded} <- Jason.decode(response) do
      {:ok, decoded}
    else
      {:error, %Jason.DecodeError{} = error} -> {:error, %Error{status: 0, code: "invalid_json", registry_message: Exception.message(error)}}
      other -> other
    end
  end

  def download_artifact(client, sha256, destination \\ nil) do
    expected = String.downcase(to_string(sha256))

    if not Regex.match?(~r/^[0-9a-f]{64}$/, expected) do
      {:error, %Error{status: 0, code: "invalid_sha256", registry_message: "sha256 must be 64 hexadecimal characters"}}
    else
      with {:ok, body} <- request(client, :get, "/v1/artifacts/#{expected}", nil, nil, false, @max_artifact_bytes),
           actual = :crypto.hash(:sha256, body) |> Base.encode16(case: :lower),
           :ok <- verify_digest(expected, actual),
           :ok <- maybe_write(destination, body) do
        {:ok, body}
      end
    end
  end

  defp json(client, method, path, body \\ nil, auth \\ false) do
    with {:ok, response} <- request(client, method, path, body, if(body, do: "application/json", else: nil), auth, @max_json_bytes),
         {:ok, decoded} <- decode_json(response) do
      {:ok, decoded}
    end
  end

  defp request(client, method, path, body, content_type, auth, limit) do
    :inets.start()
    :ssl.start()
    url = String.to_charlist(client.base_url <> path)
    headers = headers(client, auth)
    options = [timeout: client.timeout, connect_timeout: min(client.timeout, 10_000), autoredirect: false]
    http_options = [body_format: :binary]

    request =
      if is_nil(body) do
        {url, headers}
      else
        {url, headers, String.to_charlist(content_type), body}
      end

    case :httpc.request(method, request, options, http_options) do
      {:ok, {{_, status, _}, _response_headers, response_body}} when status in 200..299 ->
        if byte_size(response_body) <= limit do
          {:ok, response_body}
        else
          {:error, %Error{status: 0, code: "response_too_large", registry_message: "response exceeded #{limit} bytes"}}
        end

      {:ok, {{_, status, _}, _response_headers, response_body}} ->
        remote = decode_error(response_body)
        {:error, %Error{status: status, code: remote.code, registry_message: remote.message}}

      {:error, reason} ->
        {:error, %Error{status: 0, code: "transport_error", registry_message: inspect(reason)}}
    end
  end

  defp headers(client, auth) do
    base = [{'accept', 'application/json'}]

    if auth and is_binary(client.token) and client.token != "" do
      [{'authorization', String.to_charlist("Bearer " <> client.token)} | base]
    else
      base
    end
  end

  defp normalize_base(raw) do
    uri = URI.parse(String.trim(to_string(raw)))

    if uri.scheme in ["http", "https"] and is_binary(uri.host) and is_nil(uri.userinfo) and
         is_nil(uri.query) and is_nil(uri.fragment) do
      %{uri | path: String.trim_trailing(uri.path || "", "/")} |> URI.to_string() |> String.trim_trailing("/")
    else
      raise ArgumentError, "registry_url must be a credential-free absolute HTTP(S) URL"
    end
  end

  defp segment(value) do
    value = to_string(value)

    if String.trim(value) == "" or value in [".", ".."] or byte_size(value) > 256 or
         Regex.match?(~r/[\x00-\x1f\x7f]/, value) do
      raise ArgumentError, "invalid path segment"
    end

    URI.encode(value, &URI.char_unreserved?/1)
  end

  defp decode_json(""), do: {:ok, %{}}
  defp decode_json(body), do: Jason.decode(body)

  defp decode_error(body) do
    case Jason.decode(body) do
      {:ok, %{"code" => code, "message" => message}} -> %{code: code, message: String.slice(to_string(message), 0, 16_384)}
      _ -> %{code: "http_error", message: String.slice(body, 0, 16_384)}
    end
  end

  defp maybe_reason(payload, nil), do: payload
  defp maybe_reason(payload, reason), do: Map.put(payload, :reason, reason)

  defp multipart(boundary, meta, filename, artifact) do
    safe = String.replace(filename, ~r/[\r\n\"]/, "_")

    IO.iodata_to_binary([
      "--#{boundary}\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json\r\n\r\n",
      meta,
      "\r\n--#{boundary}\r\nContent-Disposition: form-data; name=\"artifact\"; filename=\"#{safe}\"\r\nContent-Type: application/octet-stream\r\n\r\n",
      artifact,
      "\r\n--#{boundary}--\r\n"
    ])
  end

  defp verify_digest(expected, expected), do: :ok
  defp verify_digest(expected, actual), do: {:error, %Error{status: 0, code: "digest_mismatch", registry_message: "expected #{expected}, got #{actual}"}}
  defp maybe_write(nil, _body), do: :ok
  defp maybe_write(path, body), do: File.write(path, body)
end
