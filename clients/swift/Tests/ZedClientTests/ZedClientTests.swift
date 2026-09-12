import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import ZedClient

final class ZedClientTests: XCTestCase {
    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    func testValidatesAndNormalizesRegistryURLsWithoutLeakingTokens() throws {
        let client = try ZedClient(
            registryURL: " https://registry.example/gateway/// ",
            token: " very-secret "
        )
        XCTAssertEqual(client.baseURL.absoluteString, "https://registry.example/gateway")
        XCTAssertTrue(client.debugDescription.contains("[REDACTED]"))
        XCTAssertFalse(client.debugDescription.contains("very-secret"))

        for invalid in [
            "relative/path",
            "ftp://registry.example",
            "https://user:secret@registry.example",
            "https://registry.example?tenant=one",
            "https://registry.example#fragment",
        "https://registry.example/../admin",
        "https://registry.example/%2e%2e/admin",
        "https://registry.example/a%2Fb",
    ] {
            XCTAssertThrowsError(try ZedClient(registryURL: invalid), invalid)
        }
    }

    func testPublicReadsAndAuthenticatedMutationsUseExactCoreRoutes() async throws {
        let recorder = RequestRecorder()
        StubURLProtocol.handler = { request in
            recorder.append(request)
            let path = request.url?.path ?? ""
            if path.hasSuffix("/yank") {
                return StubResponse(
                    status: 200,
                    body: Data(
                        #"{"org":"acme","name":"http-kit","version":"1.0.0","yanked":true}"#.utf8
                    )
                )
            }
            if path.hasSuffix("/v1/orgs") {
                return StubResponse(
                    status: 200,
                    body: Data(#"{"slug":"acme","created":true}"#.utf8)
                )
            }
            return StubResponse(
                status: 200,
                body: Data(
                    #"{"org":"acme","name":"http-kit","vcs":"git","repo_url":"https://example/acme/http-kit","versions":["1.0.0"]}"#.utf8
                )
            )
        }

        let client = try makeClient(
            basePath: "/gateway///",
            token: "publisher-token"
        )
        let metadata = try await client.getPackage(org: "acme", name: "http kit")
        let claim = try await client.claimOrg(slug: "acme")
        let yank = try await client.yank(
            org: "acme",
            name: "http-kit",
            version: "1.0.0"
        )
        XCTAssertEqual(metadata.org, "acme")
        XCTAssertEqual(metadata.versions, ["1.0.0"])
        XCTAssertTrue(claim.created)
        XCTAssertTrue(yank.yanked)

        let requests = recorder.snapshot()
        XCTAssertEqual(requests.count, 3)
        XCTAssertEqual(requests[0].httpMethod, "GET")
        XCTAssertEqual(requests[0].url?.path, "/gateway/v1/packages/acme/http kit")
        XCTAssertNil(requests[0].value(forHTTPHeaderField: "Authorization"))

        XCTAssertEqual(requests[1].httpMethod, "POST")
        XCTAssertEqual(requests[1].url?.path, "/gateway/v1/orgs")
        XCTAssertEqual(
            requests[1].value(forHTTPHeaderField: "Authorization"),
            "Bearer publisher-token"
        )
        let claimBody = try JSONDecoder().decode(
            JSONValue.self,
            from: try bodyData(requests[1])
        )
        XCTAssertEqual(claimBody, .object(["slug": .string("acme")]))

        XCTAssertEqual(
            requests[2].url?.path,
            "/gateway/v1/packages/acme/http-kit/versions/1.0.0/yank"
        )
        XCTAssertEqual(
            requests[2].value(forHTTPHeaderField: "Authorization"),
            "Bearer publisher-token"
        )
    }

    func testArtifactDownloadNeverCarriesBearerAndVerifiesSHA256() async throws {
        let artifact = Data("abc".utf8)
        let recorder = RequestRecorder()
        StubURLProtocol.handler = { request in
            recorder.append(request)
            return StubResponse(
                status: 200,
                body: artifact,
                headers: [
                    "Content-Type": "application/octet-stream",
                    "Content-Length": "\(artifact.count)",
                ]
            )
        }
        let client = try makeClient(token: "registry-secret")
        let version = VersionMetadata(
            org: "acme",
            name: "http-kit",
            version: "1.0.0",
            sha256: "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            size: Int64(artifact.count),
            format: "tar.gz",
            vcsTag: "v1.0.0",
            vcsCommit: nil,
            downloadURL: "http://127.0.0.1/presigned?signature=abc",
            publishedAt: "2026-07-31T00:00:00Z",
            yanked: false
        )
        let downloaded = try await client.downloadArtifact(version)
        XCTAssertEqual(downloaded, artifact)
        let request = try XCTUnwrap(recorder.snapshot().first)
        XCTAssertNil(
            request.value(forHTTPHeaderField: "Authorization"),
            "registry bearer leaked to artifact host"
        )
    }

    func testArtifactMismatchAndInsecureRemoteURLsFailClosed() async throws {
        StubURLProtocol.handler = { _ in
            StubResponse(status: 200, body: Data("artifact".utf8))
        }
        let development = try makeClient()
        let mismatch = VersionMetadata(
            org: "acme",
            name: "pkg",
            version: "1.0.0",
            sha256: String(repeating: "0", count: 64),
            size: 8,
            format: "tar.gz",
            vcsTag: "v1.0.0",
            vcsCommit: nil,
            downloadURL: "http://127.0.0.1/artifact",
            publishedAt: "now",
            yanked: false
        )
        do {
            _ = try await development.downloadArtifact(mismatch)
            XCTFail("expected digest mismatch")
        } catch let error as ZedClientError {
            guard case .sha256Mismatch = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }

        let production = try ZedClient(registryURL: "https://registry.example")
        let insecure = VersionMetadata(
            org: "acme",
            name: "pkg",
            version: "1.0.0",
            sha256: String(repeating: "0", count: 64),
            size: 1,
            format: "tar.gz",
            vcsTag: "v1.0.0",
            vcsCommit: nil,
            downloadURL: "http://artifacts.example/object",
            publishedAt: "now",
            yanked: false
        )
        do {
            _ = try await production.downloadArtifact(insecure)
            XCTFail("expected insecure URL refusal")
        } catch let error as ZedClientError {
            guard case .invalidConfiguration = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
    }

    func testMultipartPublishPreservesRawBytesAndCoordinate() async throws {
        let artifact = Data([0, 7, 13, 200, 255])
        let recorder = RequestRecorder()
        StubURLProtocol.handler = { request in
            recorder.append(request)
            return StubResponse(
                status: 200,
                body: Data(
                    #"{"org":"acme","name":"http-kit","version":"1.2.3","sha256":"abc"}"#.utf8
                )
            )
        }
        let client = try makeClient(basePath: "/registry", token: "publisher-token")
        let meta: JSONValue = .object([
            "manifest": .object([
                "package": .object([
                    "org": .string("acme"),
                    "name": .string("http-kit"),
                    "version": .string("1.2.3"),
                ]),
            ]),
            "provenance": .object(["commit": .string("deadbeef")]),
        ])
        let response = try await client.publish(meta: meta, artifact: artifact)
        XCTAssertEqual(response.version, "1.2.3")

        let request = try XCTUnwrap(recorder.snapshot().first)
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(
            request.url?.path,
            "/registry/v1/packages/acme/http-kit/versions/1.2.3"
        )
        XCTAssertEqual(
            request.value(forHTTPHeaderField: "Authorization"),
            "Bearer publisher-token"
        )
        XCTAssertTrue(
            request.value(forHTTPHeaderField: "Content-Type")?
                .hasPrefix("multipart/form-data; boundary=") == true
        )
        let body = try bodyData(request)
        XCTAssertNotNil(body.range(of: artifact), "multipart body altered raw archive bytes")
        let readable = String(decoding: body, as: UTF8.self)
        XCTAssertTrue(readable.contains("name=\"meta\""))
        XCTAssertTrue(readable.contains("name=\"artifact\""))
        XCTAssertTrue(readable.contains("\"commit\":\"deadbeef\""))
    }

    func testDefaultAPIDiagnosticsHideRemoteBodyButRetainBoundedBody() async throws {
        StubURLProtocol.handler = { _ in
            StubResponse(
                status: 403,
                body: Data(
                    #"{"code":"scope_denied","message":"provider-secret"}"#.utf8
                )
            )
        }
        let client = try makeClient(token: "token")
        do {
            _ = try await client.claimOrg(slug: "acme")
            XCTFail("expected API error")
        } catch let error as ZedClientError {
            guard case let .api(status, code, body) = error else {
                return XCTFail("unexpected error: \(error)")
            }
            XCTAssertEqual(status, 403)
            XCTAssertEqual(code, "scope_denied")
            XCTAssertTrue(body.contains("provider-secret"))
            XCTAssertFalse(error.errorDescription?.contains("provider-secret") == true)
        }
    }

    func testRejectsHostileSegmentsBeforeTransport() async throws {
    let client = try makeClient()
    let hostileValues = [
        "",
        "   ",
        ".",
        "..",
        String(UnicodeScalar(10)!),
    ]
    for value in hostileValues {
        do {
            _ = try await client.getPackage(org: value, name: "kit")
            XCTFail("expected segment rejection for \(value.debugDescription)")
        } catch let error as ZedClientError {
            guard case .invalidConfiguration = error else {
                return XCTFail("unexpected error: \(error)")
            }
        }
    }
}

func testRelativeDownloadURLPreservesGatewayAndUppercaseDigest() async throws {
    let artifact = Data("abc".utf8)
    let recorder = RequestRecorder()
    StubURLProtocol.handler = { request in
        recorder.append(request)
        return StubResponse(status: 200, body: artifact)
    }
    let client = try makeClient(basePath: "/gateway")
    let version = VersionMetadata(
        org: "acme",
        name: "kit",
        version: "1.0.0",
        sha256: "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
        size: Int64(artifact.count),
        format: "tar.gz",
        vcsTag: "v1.0.0",
        vcsCommit: nil,
        downloadURL: "artifacts/hash",
        publishedAt: "now",
        yanked: false
    )
    let downloaded = try await client.downloadArtifact(version)
    XCTAssertEqual(downloaded, artifact)
    XCTAssertEqual(
        recorder.snapshot().first?.url?.path,
        "/gateway/artifacts/hash"
    )
}

func testBlankStructuredErrorCodeFallsBackToHTTPStatus() async throws {
    StubURLProtocol.handler = { _ in
        StubResponse(
            status: 409,
            body: Data(#"{"code":"   ","message":"remote detail"}"#.utf8)
        )
    }
    let client = try makeClient(token: "token")
    do {
        _ = try await client.claimOrg(slug: "acme")
        XCTFail("expected API error")
    } catch let error as ZedClientError {
        guard case let .api(status, code, _) = error else {
            return XCTFail("unexpected error: \(error)")
        }
        XCTAssertEqual(status, 409)
        XCTAssertEqual(code, "http_409")
    }
}

func testAuthenticatedOperationsRequireTokenBeforeTransport() async throws {
        let client = try makeClient(token: nil)
        do {
            _ = try await client.claimOrg(slug: "acme")
            XCTFail("expected missing-token error")
        } catch let error as ZedClientError {
            XCTAssertEqual(error, .missingToken)
        }
    }

    private func makeClient(
        basePath: String = "",
        token: String? = nil
    ) throws -> ZedClient {
        try ZedClient(
            registryURL: "http://127.0.0.1\(basePath)",
            token: token,
            protocolClasses: [StubURLProtocol.self]
        )
    }
}

private struct StubResponse {
    let status: Int
    let body: Data
    let headers: [String: String]

    init(status: Int, body: Data, headers: [String: String] = ["Content-Type": "application/json"]) {
        self.status = status
        self.body = body
        self.headers = headers
    }
}

private final class RequestRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var requests: [URLRequest] = []

    func append(_ request: URLRequest) {
        lock.lock()
        requests.append(request)
        lock.unlock()
    }

    func snapshot() -> [URLRequest] {
        lock.lock()
        let value = requests
        lock.unlock()
        return value
    }
}

private final class StubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> StubResponse)?

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            let handler = try XCTUnwrap(Self.handler)
            let stub = try handler(request)
            let response = try XCTUnwrap(
                HTTPURLResponse(
                    url: try XCTUnwrap(request.url),
                    statusCode: stub.status,
                    httpVersion: "HTTP/1.1",
                    headerFields: stub.headers
                )
            )
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: stub.body)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() { }
}

private func bodyData(_ request: URLRequest) throws -> Data {
    if let body = request.httpBody {
        return body
    }
    guard let stream = request.httpBodyStream else {
        return Data()
    }
    stream.open()
    defer { stream.close() }
    var output = Data()
    var buffer = [UInt8](repeating: 0, count: 8192)
    while stream.hasBytesAvailable {
        let capacity = buffer.count
        let count = buffer.withUnsafeMutableBytes { bytes -> Int in
            guard let baseAddress = bytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                return 0
            }
            return stream.read(baseAddress, maxLength: capacity)
        }
        if count < 0 {
            throw stream.streamError ?? URLError(.cannotDecodeContentData)
        }
        if count == 0 { break }
        output.append(contentsOf: buffer.prefix(count))
    }
    return output
}
