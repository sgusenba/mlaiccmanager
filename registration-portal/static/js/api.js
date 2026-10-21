// Calls to the portal's API. Writes are JSON (the server refuses anything
// else) and the browser adds the page's Origin, which the server checks.

import { t } from './i18n.js';

export class ApiError extends Error {
    constructor(status, body) {
        super(body?.error || t('common.httpError', { status }));
        this.status = status;
        this.body = body || {};
    }
}

export async function api(path, method = 'GET', body) {
    const options = { method, credentials: 'same-origin', headers: {} };
    if (method !== 'GET') {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body ?? {});
    }
    let response;
    try {
        response = await fetch(`/api${path}`, options);
    } catch {
        throw new ApiError(0, { error: t('common.offline') });
    }
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new ApiError(response.status, json);
    return json;
}
