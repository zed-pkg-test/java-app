<?php

declare(strict_types=1);

namespace ZedPkg;

use JsonException;
use RuntimeException;

final class RegistryException extends RuntimeException
{
    public function __construct(
        public readonly int $status,
        public readonly string $errorCode,
        public readonly string $registryMessage,
    ) {
        parent::__construct("registry error {$status}: {$errorCode}");
    }
}

final class Client
{
    private const DEFAULT_REGISTRY = 'https://registry.zpkg.tech';
    private const MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private const MAX_ARTIFACT_BYTES = 100 * 1024 * 1024;

    private readonly string $baseUrl;

    public function __construct(
        string $registryUrl = self::DEFAULT_REGISTRY,
        private readonly ?string $token = null,
        private readonly int $timeoutSeconds = 30,
    ) {
        $this->baseUrl = $this->normalizeBaseUrl($registryUrl);
    }

    /** @return array<string,mixed> */
    public function package(string $org, string $name): array
    {
        return $this->json('GET', '/v1/packages/' . $this->segment($org) . '/' . $this->segment($name));
    }

    /** @return array<string,mixed> */
    public function version(string $org, string $name, string $version): array
    {
        return $this->json('GET', '/v1/packages/' . $this->segment($org) . '/' . $this->segment($name) . '/versions/' . $this->segment($version));
    }

    /** @return array<string,mixed> */
    public function search(string $query): array
    {
        return $this->json('GET', '/v1/search?q=' . rawurlencode($query));
    }

    /** @return array<string,mixed> */
    public function claimOrg(string $org): array
    {
        return $this->json('POST', '/v1/orgs', ['org' => $org], true);
    }

    /** @return array<string,mixed> */
    public function setYanked(string $org, string $name, string $version, bool $yanked, ?string $reason = null): array
    {
        $payload = ['yanked' => $yanked];
        if ($reason !== null) {
            $payload['reason'] = $reason;
        }
        return $this->json(
            'POST',
            '/v1/packages/' . $this->segment($org) . '/' . $this->segment($name) . '/versions/' . $this->segment($version) . '/yank',
            $payload,
            true,
        );
    }

    /** @return array<string,mixed> */
    public function yank(string $org, string $name, string $version, ?string $reason = null): array
    {
        return $this->setYanked($org, $name, $version, true, $reason);
    }

    /** @return array<string,mixed> */
    public function restore(string $org, string $name, string $version): array
    {
        return $this->setYanked($org, $name, $version, false);
    }

    /** @param array<string,mixed> $metadata @return array<string,mixed> */
    public function publish(string $org, string $name, string $version, string $artifactPath, array $metadata): array
    {
        if (!is_file($artifactPath)) {
            throw new RuntimeException("artifact does not exist: {$artifactPath}");
        }
        $fields = [
            'meta' => json_encode($metadata, JSON_THROW_ON_ERROR),
            'artifact' => new \CURLFile($artifactPath, 'application/octet-stream', basename($artifactPath)),
        ];
        $body = $this->request(
            'PUT',
            '/v1/packages/' . $this->segment($org) . '/' . $this->segment($name) . '/versions/' . $this->segment($version),
            $fields,
            true,
            null,
            self::MAX_RESPONSE_BYTES,
        );
        return $this->decode($body);
    }

    public function downloadArtifact(string $sha256, ?string $destination = null): string
    {
        $sha256 = strtolower($sha256);
        if (!preg_match('/^[0-9a-f]{64}$/', $sha256)) {
            throw new RuntimeException('sha256 must be 64 hexadecimal characters');
        }
        $body = $this->request('GET', '/v1/artifacts/' . $sha256, null, false, null, self::MAX_ARTIFACT_BYTES);
        $actual = hash('sha256', $body);
        if (!hash_equals($sha256, $actual)) {
            throw new RegistryException(0, 'digest_mismatch', "expected {$sha256}, got {$actual}");
        }
        if ($destination !== null && file_put_contents($destination, $body) === false) {
            throw new RuntimeException("failed to write {$destination}");
        }
        return $body;
    }

    /** @param array<string,mixed>|null $payload @return array<string,mixed> */
    private function json(string $method, string $path, ?array $payload = null, bool $auth = false): array
    {
        $encoded = $payload === null ? null : json_encode($payload, JSON_THROW_ON_ERROR);
        return $this->decode($this->request($method, $path, $encoded, $auth, $payload === null ? null : 'application/json'));
    }

    /** @return array<string,mixed> */
    private function decode(string $body): array
    {
        if ($body === '') {
            return [];
        }
        try {
            $decoded = json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException $e) {
            throw new RegistryException(0, 'invalid_json', $e->getMessage());
        }
        if (!is_array($decoded)) {
            throw new RegistryException(0, 'invalid_json', 'expected a JSON object');
        }
        return $decoded;
    }

    private function request(string $method, string $path, mixed $body, bool $auth, ?string $contentType = null, int $limit = self::MAX_RESPONSE_BYTES): string
    {
        $ch = curl_init($this->baseUrl . $path);
        if ($ch === false) {
            throw new RuntimeException('failed to initialize curl');
        }
        $headers = ['Accept: application/json'];
        if ($contentType !== null) {
            $headers[] = 'Content-Type: ' . $contentType;
        }
        if ($auth && $this->token !== null && $this->token !== '') {
            $headers[] = 'Authorization: Bearer ' . $this->token;
        }
        curl_setopt_array($ch, [
            CURLOPT_CUSTOMREQUEST => $method,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_TIMEOUT => $this->timeoutSeconds,
            CURLOPT_HTTPHEADER => $headers,
        ]);
        if ($body !== null) {
            curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
        }
        $response = curl_exec($ch);
        if (!is_string($response)) {
            $message = curl_error($ch);
            curl_close($ch);
            throw new RegistryException(0, 'transport_error', $message);
        }
        $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        if (strlen($response) > $limit) {
            throw new RegistryException(0, 'response_too_large', "response exceeded {$limit} bytes");
        }
        if ($status < 200 || $status >= 300) {
            $decoded = json_decode($response, true);
            $code = is_array($decoded) && is_string($decoded['code'] ?? null) ? $decoded['code'] : 'http_error';
            $message = is_array($decoded) && is_string($decoded['message'] ?? null) ? $decoded['message'] : substr($response, 0, 16384);
            throw new RegistryException($status, $code, $message);
        }
        return $response;
    }

    private function normalizeBaseUrl(string $raw): string
    {
        $parts = parse_url(trim($raw));
        $valid = is_array($parts)
            && in_array($parts['scheme'] ?? '', ['http', 'https'], true)
            && isset($parts['host'])
            && !isset($parts['user'])
            && !isset($parts['pass'])
            && !isset($parts['query'])
            && !isset($parts['fragment']);
        if (!$valid) {
            throw new RuntimeException('registryUrl must be a credential-free absolute HTTP(S) URL');
        }
        return rtrim($raw, '/');
    }

    private function segment(string $value): string
    {
        if (trim($value) === '' || $value === '.' || $value === '..' || strlen($value) > 256 || preg_match('/[\x00-\x1f\x7f]/', $value)) {
            throw new RuntimeException('invalid path segment');
        }
        return rawurlencode($value);
    }
}
