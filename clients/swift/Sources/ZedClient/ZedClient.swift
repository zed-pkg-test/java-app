import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum JSONValue: Codable, Equatable, Sendable {
    case object([String: JSONValue])
    case array([JSONValue])
    case string(String)
    case number(Double)
    case bool(Bool)
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .object(value): try container.encode(value)
        case let .array(value): try container.encode(value)
        case let .string(value): try container.encode(value)
        case let .number(value): try container.encode(value)
        case let .bool(value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}

public struct PackageSummary: Codable, Equatable, Sendable {
    public let org: String
    public let name: String
    public let description: String?
    public let latest: String?
}

public struct PackageMetadata: Codable, Equatable, Sendable {
    public let org: String
    public let name: String
    public let description: String?
    public let vcs: String
    public let repoURL: String
    public let latest: String?
    public let versions: [String]
    public let versionScheme: String?

    enum CodingKeys: String, CodingKey {
        case org, name, description, vcs, latest, versions
        case repoURL = "repo_url"
        case versionScheme = "version_scheme"
    }
}

public struct VersionMetadata: Codable, Equatable, Sendable {
    public let org: String
    public let name: String
    public let version: String
    public let sha256: String
    public let size: Int64
    public let format: String
    public let vcsTag: String
    public let vcsCommit: String?
    public let downloadURL: String
    public let publishedAt: String
    public let yanked: Bool

    enum CodingKeys: String, CodingKey {
        case org, name, version, sha256, size, format, yanked
        case vcsTag = "vcs_tag"
        case vcsCommit = "vcs_commit"
        case downloadURL = "download_url"
        case publishedAt = "published_at"
    }
}

public struct SearchResponse: Codable, Equatable, Sendable {
    public let query: String
    public let items: [PackageSummary]
}

public struct ClaimOrgResponse: Codable, Equatable, Sendable {
    public let slug: String
    public let created: Bool
}

public struct PublishResponse: Codable, Equatable, Sendable {
    public let org: String
    public let name: String
    public let version: String
    public let sha256: String
}

public struct YankResponse: Codable, Equatable, Sendable {
    public let org: String
    public let name: String
    public let version: String
    public let yanked: Bool
}

public enum ZedClientError: Error, Sendable, Equatable, LocalizedError {
    case invalidConfiguration(String)
    case missingToken
    case invalidResponse
    case api(status: Int, code: String, body: String)
    case responseTooLarge(limit: Int)
    case sha256Mismatch(expected: String, actual: String)
    case decode(String)

    public var errorDescription: String? {
        switch self {
        case let .invalidConfiguration(message):
            return "Invalid Zed client configuration: \(message)"
        case .missingToken:
            return "Authenticated registry operation requires a bearer token"
        case .invalidResponse:
            return "Registry returned a non-HTTP response"
        case let .api(status, code, _):
            return "Registry returned HTTP \(status): \(code)"
        case let .responseTooLarge(limit):
            return "Registry response exceeded \(limit) bytes"
        case .sha256Mismatch:
            return "Artifact SHA-256 mismatch"
        case let .decode(message):
            return "Registry response decode error: \(message)"
        }
    }
}

private struct ErrorEnvelope: Decodable {
    let code: String?
}

private final class BoundedDataDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    typealias ResultValue = Result<(Data, HTTPURLResponse), Error>

    let successLimit: Int
    let errorLimit: Int
    var completion: ((ResultValue) -> Void)?

    private var body = Data()
    private var response: HTTPURLResponse?
    private var completed = false

    init(successLimit: Int, errorLimit: Int) {
        self.successLimit = successLimit
        self.errorLimit = errorLimit
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            finish(.failure(ZedClientError.invalidResponse))
            return
        }
        self.response = http
        let successful = (200..<300).contains(http.statusCode)
        if successful,
           response.expectedContentLength >= 0,
           response.expectedContentLength > Int64(successLimit)
        {
            completionHandler(.cancel)
            finish(.failure(ZedClientError.responseTooLarge(limit: successLimit)))
            return
        }
        completionHandler(.allow)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive chunk: Data
    ) {
        guard !completed, let response else { return }
        let successful = (200..<300).contains(response.statusCode)
        let limit = successful ? successLimit : errorLimit
        let remaining = max(0, limit - body.count)
        if chunk.count <= remaining {
            body.append(chunk)
            return
        }

        if remaining > 0 {
            body.append(chunk.prefix(remaining))
        }
        dataTask.cancel()
        if successful {
            finish(.failure(ZedClientError.responseTooLarge(limit: limit)))
        } else {
            finish(.success((body, response)))
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard !completed else { return }
        if let error {
            finish(.failure(error))
        } else if let response {
            finish(.success((body, response)))
        } else {
            finish(.failure(ZedClientError.invalidResponse))
        }
    }

    private func finish(_ result: ResultValue) {
        guard !completed else { return }
        completed = true
        let callback = completion
        completion = nil
        callback?(result)
    }
}

public final class ZedClient: @unchecked Sendable {
    public static let defaultRegistryURL = "https://registry.zpkg.tech"
    public static let maxArtifactBytes = 100 * 1024 * 1024

    static let maxJSONBytes = 2 * 1024 * 1024
    static let maxErrorBytes = 16 * 1024
    private static let downloadSlackBytes = 1024 * 1024
    private static let timeout: TimeInterval = 30
    private static let maxSegmentBytes = 256

    let baseURL: URL
    private let token: String?
    private let protocolClasses: [AnyClass]?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(
        registryURL: String = ZedClient.defaultRegistryURL,
        token: String? = nil
    ) throws {
        self.baseURL = try Self.validateBaseURL(registryURL)
        self.token = Self.normalizeOptional(token)
        self.protocolClasses = nil
    }

    init(
        registryURL: String,
        token: String?,
        protocolClasses: [AnyClass]?
    ) throws {
        self.baseURL = try Self.validateBaseURL(registryURL)
        self.token = Self.normalizeOptional(token)
        self.protocolClasses = protocolClasses
    }

    public func getPackage(org: String, name: String) async throws -> PackageMetadata {
        try await requestJSON(
            PackageMetadata.self,
            method: "GET",
            path: try Self.packagePath(org: org, name: name)
        )
    }

    public func getVersion(
        org: String,
        name: String,
        version: String
    ) async throws -> VersionMetadata {
        try await requestJSON(
            VersionMetadata.self,
            method: "GET",
            path: try Self.versionPath(org: org, name: name, version: version)
        )
    }

    public func search(query: String) async throws -> SearchResponse {
        var components = URLComponents()
        components.queryItems = [URLQueryItem(name: "q", value: query)]
        let encoded = components.percentEncodedQuery ?? "q="
        return try await requestJSON(
            SearchResponse.self,
            method: "GET",
            path: "/v1/search?\(encoded)"
        )
    }

    public func claimOrg(slug: String) async throws -> ClaimOrgResponse {
        let body = JSONValue.object([
            "slug": .string(try Self.requireText(slug, name: "slug")),
        ])
        return try await requestJSON(
            ClaimOrgResponse.self,
            method: "POST",
            path: "/v1/orgs",
            authenticated: true,
            body: encoder.encode(body),
            contentType: "application/json"
        )
    }

    public func setYanked(
        org: String,
        name: String,
        version: String,
        yanked: Bool
    ) async throws -> YankResponse {
        try await requestJSON(
            YankResponse.self,
            method: "POST",
            path: try Self.yankPath(org: org, name: name, version: version),
            authenticated: true,
            body: encoder.encode(JSONValue.object(["yanked": .bool(yanked)])),
            contentType: "application/json"
        )
    }

    public func yank(org: String, name: String, version: String) async throws -> YankResponse {
        try await setYanked(org: org, name: name, version: version, yanked: true)
    }

    public func restore(org: String, name: String, version: String) async throws -> YankResponse {
        try await setYanked(org: org, name: name, version: version, yanked: false)
    }

    /// Downloads and verifies an artifact without attaching the registry bearer.
    public func downloadArtifact(_ version: VersionMetadata) async throws -> Data {
        let url = try downloadURL(version)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = Self.timeout
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")

        let limit = Self.downloadLimit(version.size)
        let (artifact, response) = try await boundedData(
            for: request,
            successLimit: limit,
            errorLimit: Self.maxErrorBytes
        )
        guard (200..<300).contains(response.statusCode) else {
            throw apiError(status: response.statusCode, data: artifact)
        }
        let actual = SHA256.hexDigest(artifact)
        guard actual.caseInsensitiveCompare(version.sha256) == .orderedSame else {
            throw ZedClientError.sha256Mismatch(expected: version.sha256, actual: actual)
        }
        return artifact
    }

    /// Publishes raw archive bytes as multipart `meta` and `artifact` fields.
    public func publish(meta: JSONValue, artifact: Data) async throws -> PublishResponse {
        guard artifact.count <= Self.maxArtifactBytes else {
            throw ZedClientError.responseTooLarge(limit: Self.maxArtifactBytes)
        }
        let coordinate = try Self.packageCoordinate(meta)
        let boundary = "zed-\(UUID().uuidString)"
        let body = try multipartBody(
            boundary: boundary,
            meta: meta,
            artifact: artifact,
            coordinate: coordinate
        )
        return try await requestJSON(
            PublishResponse.self,
            method: "PUT",
            path: try Self.versionPath(
                org: coordinate.org,
                name: coordinate.name,
                version: coordinate.version
            ),
            authenticated: true,
            body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
    }

    public var debugDescription: String {
        "ZedClient(baseURL: \(baseURL.absoluteString), token: [REDACTED])"
    }

    private func requestJSON<T: Decodable>(
        _ type: T.Type,
        method: String,
        path: String,
        authenticated: Bool = false,
        body: Data? = nil,
        contentType: String? = nil
    ) async throws -> T {
        var request = URLRequest(url: try url(path: path))
        request.httpMethod = method
        request.timeoutInterval = Self.timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if authenticated {
            request.setValue("Bearer \(try requireToken())", forHTTPHeaderField: "Authorization")
        }
        if let body {
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await boundedData(
            for: request,
            successLimit: Self.maxJSONBytes,
            errorLimit: Self.maxErrorBytes
        )
        guard (200..<300).contains(response.statusCode) else {
            throw apiError(status: response.statusCode, data: data)
        }
        do {
            return try decoder.decode(type, from: data)
        } catch {
            throw ZedClientError.decode(String(describing: error))
        }
    }

    private func boundedData(
        for request: URLRequest,
        successLimit: Int,
        errorLimit: Int
    ) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { continuation in
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = Self.timeout
            configuration.timeoutIntervalForResource = Self.timeout
            configuration.httpShouldSetCookies = false
            configuration.httpCookieAcceptPolicy = .never
            configuration.protocolClasses = protocolClasses

            let delegate = BoundedDataDelegate(
                successLimit: successLimit,
                errorLimit: errorLimit
            )
            let queue = OperationQueue()
            queue.maxConcurrentOperationCount = 1
            let session = URLSession(
                configuration: configuration,
                delegate: delegate,
                delegateQueue: queue
            )
            delegate.completion = { result in
                session.finishTasksAndInvalidate()
                continuation.resume(with: result)
            }
            session.dataTask(with: request).resume()
        }
    }

    private func apiError(status: Int, data: Data) -> ZedClientError {
        let body = String(decoding: data, as: UTF8.self)
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        let envelope = try? decoder.decode(ErrorEnvelope.self, from: data)
    let code = envelope
        .flatMap { Self.normalizeOptional($0.code) }
        ?? "http_\(status)"
        return .api(status: status, code: code, body: body)
    }

    private func requireToken() throws -> String {
        guard let token else { throw ZedClientError.missingToken }
        return token
    }

    private func url(path: String) throws -> URL {
        guard let value = URL(string: baseURL.absoluteString + path) else {
            throw ZedClientError.invalidConfiguration("request URL is invalid")
        }
        return value
    }

    private func downloadURL(_ version: VersionMetadata) throws -> URL {
    guard let raw = Self.normalizeOptional(version.downloadURL) else {
        return try url(path: try Self.artifactPath(version.sha256))
    }

    let candidate: URL
    if raw.contains("://") {
        guard let absolute = URL(string: raw) else {
            throw ZedClientError.invalidConfiguration("download_url is invalid")
        }
        candidate = absolute
    } else {
        try Self.validateRelativeDownloadPath(raw)
        guard let base = URL(string: baseURL.absoluteString + "/"),
              let relative = URL(string: raw, relativeTo: base)?.absoluteURL
        else {
            throw ZedClientError.invalidConfiguration("download_url is invalid")
        }
        candidate = relative
    }

    guard let components = URLComponents(
        url: candidate,
        resolvingAgainstBaseURL: false
    ),
          let scheme = components.scheme?.lowercased(),
          let host = components.host?.lowercased(),
          !host.isEmpty,
          components.user == nil,
          components.password == nil,
          components.fragment == nil,
          let value = components.url
    else {
        throw ZedClientError.invalidConfiguration("download_url is invalid")
    }
    let loopback = host == "localhost" || host == "::1" || host.hasPrefix("127.")
    let allowed = scheme == "https"
        || (scheme == "http" && (loopback || baseURL.scheme?.lowercased() == "http"))
    guard allowed else {
        throw ZedClientError.invalidConfiguration(
            "download_url must use HTTPS; HTTP is allowed only for loopback or an HTTP development registry"
        )
    }
    return value
}

private func multipartBody(
        boundary: String,
        meta: JSONValue,
        artifact: Data,
        coordinate: PackageCoordinate
    ) throws -> Data {
        var body = Data()
        body.appendUTF8("--\(boundary)\r\n")
        body.appendUTF8("Content-Disposition: form-data; name=\"meta\"\r\n")
        body.appendUTF8("Content-Type: application/json\r\n\r\n")
        body.append(try encoder.encode(meta))
        body.appendUTF8("\r\n--\(boundary)\r\n")
        let filename = Self.safeFilename(
            "\(coordinate.org)-\(coordinate.name)-\(coordinate.version).tar.gz"
        )
        body.appendUTF8(
            "Content-Disposition: form-data; name=\"artifact\"; filename=\"\(filename)\"\r\n"
        )
        body.appendUTF8("Content-Type: application/octet-stream\r\n\r\n")
        body.append(artifact)
        body.appendUTF8("\r\n--\(boundary)--\r\n")
        return body
    }

    private static func validateRawPath(
    _ raw: String,
    name: String
) throws {
    guard let schemeRange = raw.range(of: "://") else { return }
    let authorityStart = schemeRange.upperBound
    guard let pathStart = raw[authorityStart...].firstIndex(of: "/") else { return }
    let suffix = raw[pathStart...]
    let pathEnd = suffix.firstIndex(where: { $0 == "?" || $0 == "#" })
        ?? raw.endIndex
    for (index, encoded) in raw[pathStart..<pathEnd]
        .split(separator: "/", omittingEmptySubsequences: true)
        .enumerated()
    {
        guard let decoded = String(encoded).removingPercentEncoding else {
            throw ZedClientError.invalidConfiguration(
                "\(name) contains invalid percent encoding"
            )
        }
        _ = try validateSegmentText(
            decoded,
            name: "\(name) segment \(index + 1)"
        )
        guard !decoded.contains("/"), !decoded.contains("\\") else {
            throw ZedClientError.invalidConfiguration(
                "\(name) segments must not contain encoded separators"
            )
        }
    }
}

private static func validateRelativeDownloadPath(_ raw: String) throws {
    let pathEnd = raw.firstIndex(where: { $0 == "?" || $0 == "#" })
        ?? raw.endIndex
    for (index, encoded) in raw[..<pathEnd]
        .split(separator: "/", omittingEmptySubsequences: true)
        .enumerated()
    {
        guard let decoded = String(encoded).removingPercentEncoding else {
            throw ZedClientError.invalidConfiguration(
                "download_url contains invalid percent encoding"
            )
        }
        _ = try validateSegmentText(
            decoded,
            name: "download_url segment \(index + 1)"
        )
        guard !decoded.contains("/"), !decoded.contains("\\") else {
            throw ZedClientError.invalidConfiguration(
                "download_url segments must not contain encoded separators"
            )
        }
    }
}

private static func validateSegmentText(
    _ value: String,
    name: String
) throws -> String {
    let checked = try requireText(value, name: name)
    guard checked != ".",
          checked != "..",
          checked.utf8.count <= maxSegmentBytes,
          !checked.unicodeScalars.contains(where: {
              CharacterSet.controlCharacters.contains($0)
          })
    else {
        throw ZedClientError.invalidConfiguration(
            "\(name) must not be a dot segment, exceed \(maxSegmentBytes) UTF-8 bytes, or contain control characters"
        )
    }
    return checked
}

private static func validateBaseURL(_ input: String) throws -> URL {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    try validateRawPath(trimmed, name: "registry URL path")
    guard var components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host,
              !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil
        else {
            throw ZedClientError.invalidConfiguration(
                "registry URL must be a credential-free absolute HTTP(S) URL without query or fragment"
            )
        }
        while components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        guard let url = components.url else {
            throw ZedClientError.invalidConfiguration("registry URL could not be normalized")
        }
        return url
    }

    private static func packagePath(org: String, name: String) throws -> String {
        "/v1/packages/\(try encodeSegment(org, name: "org"))/\(try encodeSegment(name, name: "name"))"
    }

    private static func versionPath(
        org: String,
        name: String,
        version: String
    ) throws -> String {
        "\(try packagePath(org: org, name: name))/versions/\(try encodeSegment(version, name: "version"))"
    }

    private static func yankPath(org: String, name: String, version: String) throws -> String {
        "\(try versionPath(org: org, name: name, version: version))/yank"
    }

    private static func artifactPath(_ sha256: String) throws -> String {
    let encoded = try encodeSegment(sha256, name: "sha256")
    return "/v1/artifacts/\(encoded)"
}

private static func encodeSegment(_ value: String, name: String) throws -> String {
    let checked = try validateSegmentText(value, name: name)
    let allowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )
    guard let encoded = checked.addingPercentEncoding(withAllowedCharacters: allowed) else {
        throw ZedClientError.invalidConfiguration("\(name) could not be encoded")
    }
    return encoded
}

private static func requireText(_ value: String?, name: String) throws -> String {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ZedClientError.invalidConfiguration("\(name) must not be blank")
        }
        return value
    }

    private static func normalizeOptional(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func downloadLimit(_ declaredSize: Int64) -> Int {
        guard declaredSize > 0 else { return maxArtifactBytes }
        let (withSlack, overflow) = declaredSize.addingReportingOverflow(Int64(downloadSlackBytes))
        if overflow { return maxArtifactBytes }
        return Int(min(withSlack, Int64(maxArtifactBytes)))
    }

    private static func packageCoordinate(_ meta: JSONValue) throws -> PackageCoordinate {
        guard case let .object(root) = meta,
              case let .object(manifest)? = root["manifest"],
              case let .object(package)? = manifest["package"],
              case let .string(org)? = package["org"],
              case let .string(name)? = package["name"],
              case let .string(version)? = package["version"]
        else {
            throw ZedClientError.invalidConfiguration(
                "publish meta.manifest.package must contain string org, name, and version"
            )
        }
        return PackageCoordinate(
    org: try validateSegmentText(org, name: "meta.manifest.package.org"),
    name: try validateSegmentText(name, name: "meta.manifest.package.name"),
    version: try validateSegmentText(
        version,
        name: "meta.manifest.package.version"
    )
)
    }

    private static func safeFilename(_ value: String) -> String {
        String(value.map { character in
            character.isASCII && (character.isLetter || character.isNumber || "._-".contains(character))
                ? character
                : "_"
        })
    }
}

private struct PackageCoordinate {
    let org: String
    let name: String
    let version: String
}

private extension Data {
    mutating func appendUTF8(_ value: String) {
        append(contentsOf: value.utf8)
    }
}

private enum SHA256 {
    private static let constants: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    static func hexDigest(_ data: Data) -> String {
        var message = [UInt8](data)
        let bitLength = UInt64(message.count) * 8
        message.append(0x80)
        while message.count % 64 != 56 {
            message.append(0)
        }
        message.append(contentsOf: [
            UInt8((bitLength >> 56) & 0xff),
            UInt8((bitLength >> 48) & 0xff),
            UInt8((bitLength >> 40) & 0xff),
            UInt8((bitLength >> 32) & 0xff),
            UInt8((bitLength >> 24) & 0xff),
            UInt8((bitLength >> 16) & 0xff),
            UInt8((bitLength >> 8) & 0xff),
            UInt8(bitLength & 0xff),
        ])

        var hash: [UInt32] = [
            0x6a09e667,
            0xbb67ae85,
            0x3c6ef372,
            0xa54ff53a,
            0x510e527f,
            0x9b05688c,
            0x1f83d9ab,
            0x5be0cd19,
        ]

        for offset in stride(from: 0, to: message.count, by: 64) {
            var words = [UInt32](repeating: 0, count: 64)
            for index in 0..<16 {
                let start = offset + index * 4
                words[index] = UInt32(message[start]) << 24
                    | UInt32(message[start + 1]) << 16
                    | UInt32(message[start + 2]) << 8
                    | UInt32(message[start + 3])
            }
            for index in 16..<64 {
                let s0 = rotateRight(words[index - 15], by: 7)
                    ^ rotateRight(words[index - 15], by: 18)
                    ^ (words[index - 15] >> 3)
                let s1 = rotateRight(words[index - 2], by: 17)
                    ^ rotateRight(words[index - 2], by: 19)
                    ^ (words[index - 2] >> 10)
                words[index] = words[index - 16]
                    &+ s0
                    &+ words[index - 7]
                    &+ s1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]

            for index in 0..<64 {
                let sum1 = rotateRight(e, by: 6)
                    ^ rotateRight(e, by: 11)
                    ^ rotateRight(e, by: 25)
                let choice = (e & f) ^ ((~e) & g)
                let temporary1 = h
                    &+ sum1
                    &+ choice
                    &+ constants[index]
                    &+ words[index]
                let sum0 = rotateRight(a, by: 2)
                    ^ rotateRight(a, by: 13)
                    ^ rotateRight(a, by: 22)
                let majority = (a & b) ^ (a & c) ^ (b & c)
                let temporary2 = sum0 &+ majority

                h = g
                g = f
                f = e
                e = d &+ temporary1
                d = c
                c = b
                b = a
                a = temporary1 &+ temporary2
            }

            hash[0] = hash[0] &+ a
            hash[1] = hash[1] &+ b
            hash[2] = hash[2] &+ c
            hash[3] = hash[3] &+ d
            hash[4] = hash[4] &+ e
            hash[5] = hash[5] &+ f
            hash[6] = hash[6] &+ g
            hash[7] = hash[7] &+ h
        }

        return hash.map { String(format: "%08x", $0) }.joined()
    }

    private static func rotateRight(_ value: UInt32, by amount: UInt32) -> UInt32 {
        (value >> amount) | (value << (32 - amount))
    }
}
