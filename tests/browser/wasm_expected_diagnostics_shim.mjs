import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const playwright = await import('playwright');
const expectedDiagnostics = [];
const artifactRoot = process.env.BROWSER_ARTIFACT_DIR
  ? path.resolve(process.env.BROWSER_ARTIFACT_DIR)
  : null;

const redirectPath = '/redirect-gateway/v1/orgs';

function expectedConsoleDiagnostic(message) {
  if (message?.type?.() !== 'error') return null;
  const text = String(message.text?.() ?? '');
  const location = message.location?.() ?? {};
  const url = String(location.url ?? '');

  const exactNetworkErrors = new Set([
    'Failed to load resource: net::ERR_FAILED',
    'Failed to load resource: the server responded with a status of 502 (Bad Gateway)',
    'Failed to load resource: the server responded with a status of 503 (Service Unavailable)',
  ]);
  if (exactNetworkErrors.has(text)) {
    return { kind: 'console', text, url };
  }

  if (
    text.includes('Not allowed to follow a redirection while loading') &&
    text.includes(redirectPath)
  ) {
    return { kind: 'console', text, url };
  }

  return null;
}

function expectedPageError(error) {
  const message = String(error?.message ?? error ?? '');
  if (
    message.includes(redirectPath) &&
    message.includes('due to access control checks')
  ) {
    return { kind: 'pageerror', text: message, url: '' };
  }
  return null;
}

function wrapPage(page) {
  if (page.__zedExpectedDiagnosticsWrapped) return page;
  Object.defineProperty(page, '__zedExpectedDiagnosticsWrapped', {
    value: true,
    enumerable: false,
  });

  const originalOn = page.on.bind(page);
  page.on = (eventName, listener) => {
    if (typeof listener !== 'function') return originalOn(eventName, listener);

    if (eventName === 'console') {
      return originalOn(eventName, (message, ...rest) => {
        const expected = expectedConsoleDiagnostic(message);
        if (expected) {
          expectedDiagnostics.push(expected);
          return undefined;
        }
        return listener(message, ...rest);
      });
    }

    if (eventName === 'pageerror') {
      return originalOn(eventName, (error, ...rest) => {
        const expected = expectedPageError(error);
        if (expected) {
          expectedDiagnostics.push(expected);
          return undefined;
        }
        return listener(error, ...rest);
      });
    }

    return originalOn(eventName, listener);
  };
  return page;
}

function wrapContext(context) {
  if (context.__zedExpectedDiagnosticsWrapped) return context;
  Object.defineProperty(context, '__zedExpectedDiagnosticsWrapped', {
    value: true,
    enumerable: false,
  });
  const originalNewPage = context.newPage.bind(context);
  context.newPage = async (...args) => wrapPage(await originalNewPage(...args));
  return context;
}

function wrapBrowser(browser) {
  if (browser.__zedExpectedDiagnosticsWrapped) return browser;
  Object.defineProperty(browser, '__zedExpectedDiagnosticsWrapped', {
    value: true,
    enumerable: false,
  });
  const originalNewContext = browser.newContext.bind(browser);
  browser.newContext = async (...args) => wrapContext(await originalNewContext(...args));
  return browser;
}

const browserTypePrototype = Object.getPrototypeOf(playwright.chromium);
const originalLaunch = browserTypePrototype.launch;
browserTypePrototype.launch = async function launchWithExpectedDiagnostics(...args) {
  return wrapBrowser(await originalLaunch.apply(this, args));
};

process.on('exit', () => {
  if (!artifactRoot) return;
  mkdirSync(artifactRoot, { recursive: true });
  writeFileSync(
    path.join(artifactRoot, 'expected-browser-diagnostics.json'),
    `${JSON.stringify(expectedDiagnostics, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
});
