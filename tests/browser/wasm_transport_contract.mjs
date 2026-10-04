import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import test from 'node:test';

const browserName = process.env.BROWSER_ENGINE ?? 'chromium';
const packageRoot = path.resolve(process.env.ZED_WASM_PKG ?? 'clients/wasm/pkg');
const artifactRoot = path.resolve(
  process.env.BROWSER_ARTIFACT_DIR ?? path.join('artifacts', 'wasm-transport', browserName),
);
const artifactBytes = Buffer.from('zed-browser-artifact', 'utf8');
const artifactSha256 = createHash('sha256').update(artifactBytes).digest('hex');
const maxJsonBytes = 16 * 1024 * 1024;
const maxErrorBytes = 16 * 1024;

function contentType(file) {
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.wasm')) return 'application/wasm';
  if (file.endsWith('.json')) return 'application/json; charset=utf-8';
  return 'application/octet-stream';
}

async function readRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

function packageMetadata() {
  return {
    org: 'acme',
    name: 'sdk',
    description: 'browser contract fixture',
    vcs: 'git',
    repo_url: 'https://github.com/acme/sdk',
    latest: '1.0.0',
    tags: ['browser'],
    versions: ['1.0.0'],
  };
}

function json(response, status, value, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify(value));
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    ...extraHeaders,
  });
  response.end(body);
}

async function startFixture() {
  const requests = [];
  let origin = '';
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? '/', 'http://127.0.0.1');
      const body = await readRequestBody(request);
      requests.push({
        method: request.method ?? '',
        path: url.pathname,
        authorization: request.headers.authorization ?? null,
        body,
      });

      if (url.pathname === '/') {
        const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Zed WASM transport contract</title></head><body><main><h1>Zed WASM transport contract</h1><output id="state">loading</output><pre id="result"></pre></main><script type="module" src="/contract-page.js"></script></body></html>`;
        response.writeHead(200, {
          'cache-control': 'no-store',
          'content-security-policy': "default-src 'self'; script-src 'self'; connect-src 'self'; style-src 'none'; img-src 'self' data:; object-src 'none'; base-uri 'none'",
          'content-type': 'text/html; charset=utf-8',
          'x-content-type-options': 'nosniff',
        });
        response.end(html);
        return;
      }

      if (url.pathname === '/contract-page.js') {
        const source = `import init, { ZedClient } from '/pkg/zed_client_wasm.js';\nawait init('/pkg/zed_client_wasm_bg.wasm');\nglobalThis.ZedClient = ZedClient;\ndocument.querySelector('#state').textContent = 'ready';`;
        response.writeHead(200, {
          'cache-control': 'no-store',
          'content-type': 'text/javascript; charset=utf-8',
          'x-content-type-options': 'nosniff',
        });
        response.end(source);
        return;
      }

      if (url.pathname.startsWith('/pkg/')) {
        const relative = url.pathname.slice('/pkg/'.length);
        const candidate = path.resolve(packageRoot, relative);
        if (!candidate.startsWith(`${packageRoot}${path.sep}`)) {
          response.writeHead(400).end('bad package path');
          return;
        }
        try {
          const bytes = await readFile(candidate);
          response.writeHead(200, {
            'cache-control': 'no-store',
            'content-type': contentType(candidate),
            'content-length': bytes.length,
            'x-content-type-options': 'nosniff',
          });
          response.end(bytes);
        } catch (error) {
          response.writeHead(error?.code === 'ENOENT' ? 404 : 500).end('package file unavailable');
        }
        return;
      }

      if (url.pathname === '/gateway/v1/packages/acme/sdk') {
        json(response, 200, packageMetadata());
        return;
      }

      if (url.pathname === '/gateway/v1/packages/error/sdk') {
        json(response, 502, { code: 'provider_failure', message: 'provider-secret' });
        return;
      }

      if (url.pathname === '/gateway/v1/packages/oversized/sdk') {
        const oversized = Buffer.alloc(maxJsonBytes + 1, 0x20);
        response.writeHead(200, {
          'cache-control': 'no-store',
          'content-type': 'application/json; charset=utf-8',
          'content-length': oversized.length,
        });
        response.end(oversized);
        return;
      }

      if (url.pathname === '/gateway/v1/packages/large-error/sdk') {
        const oversized = Buffer.alloc(maxErrorBytes + 1, 0x78);
        response.writeHead(503, {
          'cache-control': 'no-store',
          'content-type': 'text/plain; charset=utf-8',
          'content-length': oversized.length,
        });
        response.end(oversized);
        return;
      }

      if (url.pathname === '/slow-gateway/v1/packages/acme/sdk') {
        setTimeout(() => json(response, 200, packageMetadata()), 500);
        return;
      }

      if (url.pathname === '/gateway/v1/orgs') {
        json(response, 200, { slug: 'acme', created: true });
        return;
      }

      if (url.pathname === '/redirect-gateway/v1/orgs') {
        response.writeHead(302, {
          location: `${origin}/redirect-target`,
          'cache-control': 'no-store',
        });
        response.end();
        return;
      }

      if (url.pathname === '/redirect-target') {
        json(response, 200, { slug: 'redirected', created: true });
        return;
      }

      if (url.pathname === '/gateway/v1/packages/acme/sdk/versions/1.0.0/yank') {
        const parsed = JSON.parse(body || '{}');
        json(response, 200, {
          org: 'acme',
          name: 'sdk',
          version: '1.0.0',
          yanked: Boolean(parsed.yanked),
        });
        return;
      }

      if (url.pathname === '/gateway/artifacts/blob' || url.pathname === '/artifact-host/blob') {
        response.writeHead(200, {
          'cache-control': 'no-store',
          'content-type': 'application/octet-stream',
          'content-length': artifactBytes.length,
        });
        response.end(artifactBytes);
        return;
      }

      if (url.pathname === '/gateway/artifacts/bad-digest') {
        const bytes = Buffer.from('wrong-artifact', 'utf8');
        response.writeHead(200, {
          'cache-control': 'no-store',
          'content-type': 'application/octet-stream',
          'content-length': bytes.length,
        });
        response.end(bytes);
        return;
      }

      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      response.end('not found');
    } catch (error) {
      response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
      response.end(`fixture error: ${error.message}`);
    }
  });

  server.requestTimeout = 5_000;
  server.headersTimeout = 5_000;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  assert(address && typeof address === 'object');
  origin = `http://127.0.0.1:${address.port}`;

  return {
    origin,
    requests,
    async close() {
      if (!server.listening) return;
      server.closeIdleConnections?.();
      server.closeAllConnections?.();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

function apiRequests(requests) {
  return requests.filter((request) => !['/', '/contract-page.js'].includes(request.path) && !request.path.startsWith('/pkg/'));
}

async function evaluateCapture(page, callback, argument) {
  return page.evaluate(
    async ({ source, argument }) => {
      const capture = async (operation) => {
        try {
          const value = await operation();
          return { ok: true, value };
        } catch (error) {
          return {
            ok: false,
            error: {
              name: error?.name ?? null,
              message: error?.message ?? String(error),
              status: error?.status ?? null,
              code: error?.code ?? null,
              registryMessage: error?.registryMessage ?? null,
            },
          };
        }
      };
      const fn = (0, eval)(`(${source})`);
      return capture(() => fn(globalThis.ZedClient, argument));
    },
    { source: callback.toString(), argument },
  );
}

function assertFailure(result, code = null) {
  assert.equal(result.ok, false, JSON.stringify(result));
  if (code !== null) assert.equal(result.error.code, code, JSON.stringify(result));
  return result.error;
}

async function runContract(page, fixture) {
  const { origin, requests } = fixture;
  const initialApiCount = apiRequests(requests).length;

  const valid = await evaluateCapture(
    page,
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).getPackage('acme', 'sdk'),
    origin,
  );
  assert.equal(valid.ok, true, JSON.stringify(valid));
  assert.equal(valid.value.org, 'acme');
  assert.equal(valid.value.name, 'sdk');

  const beforePreflight = apiRequests(requests).length;
  for (const operation of [
    async (ZedClient) => new ZedClient('relative/path').getPackage('acme', 'sdk'),
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).getPackage('..', 'sdk'),
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).claimOrg('acme'),
    async (ZedClient, origin) => {
      const client = new ZedClient(`${origin}/gateway`);
      client.withToken('   ');
      return client.claimOrg('acme');
    },
    async (ZedClient, origin) => {
      const client = new ZedClient(`${origin}/gateway`);
      client.withToken('token\r\nheader');
      return client.claimOrg('acme');
    },
  ]) {
    assertFailure(await evaluateCapture(page, operation, origin));
  }
  assert.equal(apiRequests(requests).length, beforePreflight, 'preflight failures reached transport');

  const authorized = await evaluateCapture(
    page,
    async (ZedClient, origin) => {
      const client = new ZedClient(`${origin}/gateway`);
      client.withToken('  bearer-token  ');
      const yanked = await client.setYanked('acme', 'sdk', '1.0.0', true);
      const restored = await client.restore('acme', 'sdk', '1.0.0');
      return { yanked, restored };
    },
    origin,
  );
  assert.equal(authorized.ok, true, JSON.stringify(authorized));
  assert.equal(authorized.value.yanked.yanked, true);
  assert.equal(authorized.value.restored.yanked, false);
  const yankRequests = requests.filter((request) => request.path.endsWith('/yank'));
  assert.equal(yankRequests.length, 2);
  assert(yankRequests.every((request) => request.authorization === 'Bearer bearer-token'));
  assert.deepEqual(yankRequests.map((request) => JSON.parse(request.body).yanked), [true, false]);

  const redirect = await evaluateCapture(
    page,
    async (ZedClient, origin) => {
      const client = new ZedClient(`${origin}/redirect-gateway`);
      client.withToken('redirect-secret');
      return client.claimOrg('acme');
    },
    origin,
  );
  assertFailure(redirect);
  assert.equal(requests.filter((request) => request.path === '/redirect-target').length, 0);
  const redirectSource = requests.find((request) => request.path === '/redirect-gateway/v1/orgs');
  assert.equal(redirectSource?.authorization, 'Bearer redirect-secret');

  const structuredError = await evaluateCapture(
    page,
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).getPackage('error', 'sdk'),
    origin,
  );
  const structured = assertFailure(structuredError, 'provider_failure');
  assert.equal(structured.status, 502);
  assert.equal(structured.message, 'registry error 502: provider_failure');
  assert.equal(structured.registryMessage, 'provider-secret');
  assert(!structured.message.includes('provider-secret'));

  const oversized = await evaluateCapture(
    page,
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).getPackage('oversized', 'sdk'),
    origin,
  );
  assertFailure(oversized, 'response_too_large');

  const oversizedError = await evaluateCapture(
    page,
    async (ZedClient, origin) => new ZedClient(`${origin}/gateway`).getPackage('large-error', 'sdk'),
    origin,
  );
  const boundedError = assertFailure(oversizedError, 'http_503');
  assert(!boundedError.message.includes('xxxx'));
  assert.match(boundedError.registryMessage, /exceeded the client limit/);

  const timedOut = await evaluateCapture(
    page,
    async (ZedClient, origin) => {
      const client = new ZedClient(`${origin}/slow-gateway`);
      client.withTimeoutMs(50);
      return client.getPackage('acme', 'sdk');
    },
    origin,
  );
  assertFailure(timedOut);

  const relativeDownload = await evaluateCapture(
    page,
    async (ZedClient, { origin, sha256 }) => {
      const client = new ZedClient(`${origin}/gateway`);
      client.withToken('registry-secret');
      const bytes = await client.downloadArtifact({
        org: 'acme', name: 'sdk', version: '1.0.0', sha256: sha256.toUpperCase(),
        size: 20, vcs_tag: 'v1.0.0', download_url: 'artifacts/blob',
        published_at: '2026-08-03T00:00:00Z',
      });
      return { length: bytes.length, text: new TextDecoder().decode(bytes) };
    },
    { origin, sha256: artifactSha256 },
  );
  assert.equal(relativeDownload.ok, true, JSON.stringify(relativeDownload));
  assert.equal(relativeDownload.value.text, artifactBytes.toString('utf8'));
  const relativeRequest = requests.find((request) => request.path === '/gateway/artifacts/blob');
  assert(relativeRequest, 'relative download did not preserve gateway prefix');
  assert.equal(relativeRequest.authorization, null);

  const absoluteDownload = await evaluateCapture(
    page,
    async (ZedClient, { origin, sha256 }) => {
      const client = new ZedClient(`${origin}/gateway`);
      client.withToken('registry-secret');
      const bytes = await client.downloadArtifact({
        org: 'acme', name: 'sdk', version: '1.0.0', sha256,
        size: 20, vcs_tag: 'v1.0.0', download_url: `${origin}/artifact-host/blob`,
        published_at: '2026-08-03T00:00:00Z',
      });
      return bytes.length;
    },
    { origin, sha256: artifactSha256 },
  );
  assert.equal(absoluteDownload.ok, true, JSON.stringify(absoluteDownload));
  const artifactHostRequest = requests.find((request) => request.path === '/artifact-host/blob');
  assert.equal(artifactHostRequest?.authorization, null);

  const beforeUnsafeDownloads = apiRequests(requests).length;
  for (const downloadUrl of ['../escape', '%2e%2e/escape', 'a%2Fb', '//evil.example/blob', '/absolute/blob']) {
    const result = await evaluateCapture(
      page,
      async (ZedClient, { origin, sha256, downloadUrl }) =>
        new ZedClient(`${origin}/gateway`).downloadArtifact({
          org: 'acme', name: 'sdk', version: '1.0.0', sha256,
          size: 20, vcs_tag: 'v1.0.0', download_url: downloadUrl,
          published_at: '2026-08-03T00:00:00Z',
        }),
      { origin, sha256: artifactSha256, downloadUrl },
    );
    assertFailure(result);
  }
  assert.equal(apiRequests(requests).length, beforeUnsafeDownloads, 'unsafe downloads reached transport');

  const mismatch = await evaluateCapture(
    page,
    async (ZedClient, { origin, sha256 }) =>
      new ZedClient(`${origin}/gateway`).downloadArtifact({
        org: 'acme', name: 'sdk', version: '1.0.0', sha256,
        size: 20, vcs_tag: 'v1.0.0', download_url: 'artifacts/bad-digest',
        published_at: '2026-08-03T00:00:00Z',
      }),
    { origin, sha256: artifactSha256 },
  );
  const mismatchError = assertFailure(mismatch);
  assert.match(mismatchError.message, /sha256 mismatch/i);

  assert(apiRequests(requests).length > initialApiCount);
}

test('hardened browser WASM transport contract', { timeout: 120_000 }, async () => {
  await mkdir(artifactRoot, { recursive: true });
  const fixture = await startFixture();
  const playwright = await import('playwright');
  const browserType = playwright[browserName];
  assert(browserType, `unsupported browser engine ${browserName}`);
  const browser = await browserType.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  const browserErrors = [];
  const externalRequests = [];
  page.on('console', (message) => message.type() === 'error' && browserErrors.push(`console: ${message.text()}`));
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`));
  page.on('request', (request) => {
    if (!request.url().startsWith(fixture.origin) && !request.url().startsWith('data:')) {
      externalRequests.push(request.url());
    }
  });

  try {
    await page.goto(fixture.origin, { waitUntil: 'load', timeout: 20_000 });
    await page.waitForFunction(() => document.querySelector('#state')?.textContent === 'ready');
    await runContract(page, fixture);
    await page.evaluate(() => {
      document.querySelector('#result').textContent = 'all transport boundaries passed';
    });
    assert.deepEqual(browserErrors, []);
    assert.deepEqual(externalRequests, []);
  } finally {
    await Promise.allSettled([
      page.screenshot({ path: path.join(artifactRoot, `${browserName}.png`), fullPage: true }),
      writeFile(path.join(artifactRoot, 'requests.json'), `${JSON.stringify(fixture.requests, null, 2)}\n`),
      writeFile(path.join(artifactRoot, 'browser-errors.json'), `${JSON.stringify(browserErrors, null, 2)}\n`),
      writeFile(path.join(artifactRoot, 'external-requests.json'), `${JSON.stringify(externalRequests, null, 2)}\n`),
    ]);
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
    await fixture.close();
  }
});
