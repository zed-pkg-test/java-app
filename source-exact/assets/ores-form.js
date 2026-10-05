const MAX_RESPONSE_BYTES = 1024 * 1024;

const sameOriginUrl = (raw) => {
  const url = new URL(raw, window.location.href);
  if (url.origin !== window.location.origin) {
    throw new Error('ores-forms endpoint must be same-origin');
  }
  return url;
};

const readBoundedText = async (response) => {
  const declared = Number(response.headers.get('content-length') ?? '0');
  if (Number.isFinite(declared) && declared > MAX_RESPONSE_BYTES) {
    throw new Error('ores-forms response exceeds the client limit');
  }

  if (!response.body) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.byteLength > MAX_RESPONSE_BYTES) {
      throw new Error('ores-forms response exceeds the client limit');
    }
    return new TextDecoder().decode(bytes);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let total = 0;
  let text = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error('ores-forms response exceeds the client limit');
      }
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
    return text;
  } finally {
    reader.releaseLock();
  }
};

const isJsonContentType = (value) => {
  const mediaType = value.split(';', 1)[0].trim().toLowerCase();
  return mediaType === 'application/json' || mediaType.endsWith('+json');
};

const parseResponseBody = (response, text) => {
  if (text.length === 0) return null;
  if (!isJsonContentType(response.headers.get('content-type') ?? '')) {
    return { text };
  }
  try {
    return JSON.parse(text);
  } catch {
    return { text, invalid_json: true };
  }
};

class OresForm extends HTMLElement {
  connectedCallback() {
    if (this.dataset.oresInitialized === 'true') return;
    this.dataset.oresInitialized = 'true';
    void this.initialize();
  }

  async initialize() {
    try {
      if (!this.querySelector('[data-ores-form-root]')) {
        await this.mountFragment();
      }
      const form = this.querySelector('form[data-ores-form-root]');
      if (form && form.dataset.oresBound !== 'true') {
        form.dataset.oresBound = 'true';
        form.addEventListener('submit', (event) => {
          event.preventDefault();
          void this.submit(form);
        });
      }
    } catch {
      this.renderError('Could not initialize form.');
    }
  }

  async mountFragment() {
    const formId = this.getAttribute('form-id');
    if (!formId) {
      this.renderError('Missing form-id.');
      return;
    }
    const raw = this.getAttribute('component-src')
      ?? `/components/forms/${encodeURIComponent(formId)}`;
    const response = await fetch(sameOriginUrl(raw), {
      method: 'GET',
      headers: { accept: 'text/html' },
      credentials: 'same-origin',
      redirect: 'error',
    });
    if (!response.ok) {
      this.renderError(`Could not load form (${response.status}).`);
      return;
    }
    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.toLowerCase().startsWith('text/html')) {
      this.renderError('Form endpoint returned an unexpected content type.');
      return;
    }
    const html = await readBoundedText(response);
    const template = document.createElement('template');
    template.innerHTML = html;
    this.replaceChildren(template.content.cloneNode(true));
  }

  async submit(form) {
    const revisionId = form.dataset.revisionId ?? '';
    if (!revisionId) {
      this.emitError({ code: 'missing_revision_id' });
      return;
    }
    if (typeof globalThis.crypto?.randomUUID !== 'function') {
      this.emitError({ code: 'secure_response_id_unavailable' });
      return;
    }

    const answers = [];
    for (const field of form.querySelectorAll('[data-question-id]')) {
      const questionId = field.dataset.questionId;
      if (!questionId) continue;

      let values = [];
      if (field instanceof HTMLFieldSetElement) {
        values = Array.from(field.querySelectorAll('input:checked'), (input) => input.value);
        if (field.dataset.required === 'true' && values.length === 0) {
          this.emitError({ code: 'required_answer_missing', question_id: questionId });
          const firstInput = field.querySelector('input');
          if (firstInput instanceof HTMLInputElement) firstInput.focus();
          return;
        }
      } else if (field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement) {
        values = [field.value];
      }
      answers.push({ question_id: questionId, values });
    }

    const payload = {
      response_id: globalThis.crypto.randomUUID(),
      form_id: form.dataset.formId ?? '',
      revision_id: revisionId,
      answers,
    };
    if (!payload.form_id) {
      this.emitError({ code: 'missing_form_id' });
      return;
    }

    try {
      const endpoint = sameOriginUrl(form.dataset.submitEndpoint ?? '/v1/responses');
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          accept: 'application/json',
          'content-type': 'application/json',
        },
        body: JSON.stringify(payload),
        credentials: 'same-origin',
        redirect: 'error',
      });
      const text = await readBoundedText(response);
      if (!response.ok) {
        this.emitError({
          status: response.status,
          body: parseResponseBody(response, text),
        });
        return;
      }
      this.dispatchEvent(new CustomEvent('ores-form-submitted', {
        bubbles: true,
        composed: true,
        detail: parseResponseBody(response, text),
      }));
    } catch {
      this.emitError({ code: 'submission_transport_error' });
    }
  }

  emitError(detail) {
    this.dispatchEvent(new CustomEvent('ores-form-error', {
      bubbles: true,
      composed: true,
      detail,
    }));
  }

  renderError(message) {
    const output = document.createElement('p');
    output.setAttribute('role', 'alert');
    output.textContent = message;
    this.replaceChildren(output);
  }
}

if (!customElements.get('ores-form')) {
  customElements.define('ores-form', OresForm);
}
