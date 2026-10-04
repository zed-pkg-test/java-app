import http from 'node:http';

const originalWriteHead = http.ServerResponse.prototype.writeHead;

/**
 * wasm-bindgen's browser loader requires WebAssembly code generation, and the
 * contract dispatcher intentionally evaluates only test-owned function source.
 * Grant those capabilities solely on the in-process fixture response while
 * retaining same-origin scripts/connect requests and every other CSP boundary.
 */
http.ServerResponse.prototype.writeHead = function writeHeadWithWasmFixtureCsp(
  statusCode,
  statusMessageOrHeaders,
  maybeHeaders,
) {
  const rewrite = (headers) => {
    if (!headers || typeof headers !== 'object' || Array.isArray(headers)) return headers;
    const copy = { ...headers };
    const key = Object.keys(copy).find((name) => name.toLowerCase() === 'content-security-policy');
    if (key && typeof copy[key] === 'string') {
      copy[key] = copy[key].replace(
        "script-src 'self'",
        "script-src 'self' 'unsafe-eval' 'wasm-unsafe-eval'",
      );
    }
    return copy;
  };

  if (typeof statusMessageOrHeaders === 'string') {
    return originalWriteHead.call(this, statusCode, statusMessageOrHeaders, rewrite(maybeHeaders));
  }
  return originalWriteHead.call(this, statusCode, rewrite(statusMessageOrHeaders));
};
