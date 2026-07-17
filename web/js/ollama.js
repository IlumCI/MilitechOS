// Talks to an Ollama-compatible API. Works against a self-hosted server
// (http://host:11434) or Ollama Cloud (https://ollama.com with an API key).
// Note for browsers: a self-hosted server must allow this origin via OLLAMA_ORIGINS.

import { ApiError } from './github.js';

export class OllamaClient {
  constructor(baseUrl, model, apiKey = '', { fetchFn = globalThis.fetch.bind(globalThis) } = {}) {
    this.base = baseUrl.replace(/\/+$/, '');
    this.model = model;
    this.apiKey = apiKey;
    this.fetchFn = fetchFn;
  }

  #headers(json = true) {
    return {
      ...(json ? { 'Content-Type': 'application/json' } : {}),
      ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
    };
  }

  /** Non-streaming chat completion; forceJson asks the server for a JSON-constrained reply. */
  async chat(messages, { temperature = 0.2, forceJson = true } = {}) {
    const resp = await this.fetchFn(`${this.base}/api/chat`, {
      method: 'POST',
      headers: this.#headers(),
      body: JSON.stringify({
        model: this.model,
        stream: false,
        ...(forceJson ? { format: 'json' } : {}),
        messages,
        options: { temperature },
      }),
    });
    const text = await resp.text();
    if (!resp.ok) throw new ApiError(resp.status, text, `Ollama chat → ${resp.status}`);
    return JSON.parse(text).message?.content ?? '';
  }

  /** Lists model tags available on this endpoint (GET /api/tags). */
  async listModels() {
    const resp = await this.fetchFn(`${this.base}/api/tags`, {
      method: 'GET',
      headers: this.#headers(false),
    });
    const text = await resp.text();
    if (!resp.ok) throw new ApiError(resp.status, text, `Ollama tags → ${resp.status}`);
    const models = (JSON.parse(text).models ?? [])
      .map((m) => m.name || m.model || '')
      .filter(Boolean);
    return [...new Set(models)].sort();
  }
}
