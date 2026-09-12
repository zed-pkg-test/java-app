# frozen_string_literal: true

require "digest"
require "json"
require "net/http"
require "securerandom"
require "uri"

module ZedPkg
  class Error < StandardError
    attr_reader :status, :code, :registry_message

    def initialize(status, code, registry_message)
      @status = status
      @code = code
      @registry_message = registry_message.to_s.byteslice(0, 16_384)
      super("registry error #{status}: #{code}")
    end
  end

  class Client
    DEFAULT_REGISTRY_URL = "https://registry.zpkg.tech"
    MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    MAX_ARTIFACT_BYTES = 100 * 1024 * 1024

    def initialize(registry_url: DEFAULT_REGISTRY_URL, token: nil, open_timeout: 10, read_timeout: 30)
      @base = normalize_base(registry_url)
      @token = token
      @open_timeout = open_timeout
      @read_timeout = read_timeout
    end

    def package(org, name)
      json_request(:get, "/v1/packages/#{segment(org)}/#{segment(name)}")
    end

    def version(org, name, version)
      json_request(:get, "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}")
    end

    def search(query)
      json_request(:get, "/v1/search?q=#{URI.encode_www_form_component(query.to_s)}")
    end

    def claim_org(org)
      json_request(:post, "/v1/orgs", body: JSON.generate(org: org), auth: true)
    end

    def set_yanked(org, name, version, yanked:, reason: nil)
      body = { yanked: !!yanked }
      body[:reason] = reason unless reason.nil?
      json_request(
        :post,
        "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}/yank",
        body: JSON.generate(body),
        auth: true
      )
    end

    def yank(org, name, version, reason: nil)
      set_yanked(org, name, version, yanked: true, reason: reason)
    end

    def restore(org, name, version)
      set_yanked(org, name, version, yanked: false)
    end

    def publish(org, name, version, artifact_path:, metadata:)
      boundary = "zed-#{SecureRandom.hex(18)}"
      artifact = File.binread(artifact_path)
      meta = JSON.generate(metadata)
      body = multipart(boundary, meta, File.basename(artifact_path), artifact)
      response = request(
        :put,
        "/v1/packages/#{segment(org)}/#{segment(name)}/versions/#{segment(version)}",
        body: body,
        content_type: "multipart/form-data; boundary=#{boundary}",
        auth: true
      )
      parse_json(response)
    end

    def download_artifact(sha256, destination: nil)
      expected = sha256.to_s.downcase
      raise ArgumentError, "sha256 must be 64 lowercase hex characters" unless expected.match?(/\A[0-9a-f]{64}\z/)

      response = request(:get, "/v1/artifacts/#{expected}", auth: false, limit: MAX_ARTIFACT_BYTES)
      actual = Digest::SHA256.hexdigest(response.body)
      raise Error.new(0, "digest_mismatch", "expected #{expected}, got #{actual}") unless actual == expected

      File.binwrite(destination, response.body) if destination
      response.body
    end

    private

    def normalize_base(raw)
      uri = URI.parse(raw.to_s.strip)
      valid = %w[http https].include?(uri.scheme) && uri.host && !uri.user && !uri.query && !uri.fragment
      raise ArgumentError, "registry_url must be a credential-free absolute HTTP(S) URL" unless valid

      uri.path = uri.path.to_s.sub(%r{/+\z}, "")
      uri.to_s.sub(%r{/+\z}, "")
    rescue URI::InvalidURIError
      raise ArgumentError, "registry_url must be a credential-free absolute HTTP(S) URL"
    end

    def segment(value)
      text = value.to_s
      raise ArgumentError, "path segment must be nonblank" if text.strip.empty? || [".", ".."].include?(text)
      raise ArgumentError, "path segment is too long" if text.bytesize > 256
      raise ArgumentError, "path segment contains a control character" if text.match?(/[\x00-\x1f\x7f]/)

      URI.encode_www_form_component(text).gsub("+", "%20")
    end

    def json_request(method, path, body: nil, auth: false)
      parse_json(request(method, path, body: body, content_type: body ? "application/json" : nil, auth: auth))
    end

    def request(method, path, body: nil, content_type: nil, auth: false, limit: MAX_RESPONSE_BYTES)
      uri = URI.parse("#{@base}#{path}")
      klass = { get: Net::HTTP::Get, post: Net::HTTP::Post, put: Net::HTTP::Put }.fetch(method)
      req = klass.new(uri)
      req["Accept"] = "application/json"
      req["Content-Type"] = content_type if content_type
      req["Authorization"] = "Bearer #{@token}" if auth && @token && !@token.empty?
      req.body = body if body

      http = Net::HTTP.new(uri.host, uri.port)
      http.use_ssl = uri.scheme == "https"
      http.open_timeout = @open_timeout
      http.read_timeout = @read_timeout
      response = http.request(req)
      bytes = response.body.to_s.b
      raise Error.new(0, "response_too_large", "response exceeded #{limit} bytes") if bytes.bytesize > limit
      unless response.code.to_i.between?(200, 299)
        payload = JSON.parse(bytes) rescue {}
        raise Error.new(response.code.to_i, payload["code"] || "http_error", payload["message"] || bytes)
      end
      response.body = bytes
      response
    end

    def parse_json(response)
      return {} if response.body.nil? || response.body.empty?
      JSON.parse(response.body)
    rescue JSON::ParserError => e
      raise Error.new(0, "invalid_json", e.message)
    end

    def multipart(boundary, meta, filename, artifact)
      crlf = "\r\n"
      [
        "--#{boundary}#{crlf}",
        "Content-Disposition: form-data; name=\"meta\"#{crlf}",
        "Content-Type: application/json#{crlf}#{crlf}",
        meta,
        crlf,
        "--#{boundary}#{crlf}",
        "Content-Disposition: form-data; name=\"artifact\"; filename=\"#{filename.gsub(/[\r\n\"]/, "_")}\"#{crlf}",
        "Content-Type: application/octet-stream#{crlf}#{crlf}",
        artifact,
        crlf,
        "--#{boundary}--#{crlf}"
      ].join.b
    end
  end
end
