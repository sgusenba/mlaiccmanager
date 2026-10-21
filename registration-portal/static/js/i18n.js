// Translations of the portal's pages, as in the Manager: the language picked
// on the page (localStorage and the "lang" cookie, which the server reads for
// its own messages), else the browser's, else German. Static HTML is marked
// with data-i18n="key" (text), data-i18n-placeholder, data-i18n-aria-label.
// A message is a string with {name} placeholders or, for counted things,
// { one, other } chosen by the "count" parameter.

import de from '../i18n/de.js';
import en from '../i18n/en.js';

export const LANGUAGES = { de, en };
const KEY = 'mlaicportal.language';

function stored() {
    try {
        return localStorage.getItem(KEY);
    } catch {
        return null;
    }
}

function pick() {
    const candidates = [stored(), ...(navigator.languages || [navigator.language])];
    for (const c of candidates) {
        const base = String(c || '').toLowerCase().split('-')[0];
        if (base in LANGUAGES) return base;
    }
    return 'de';
}

export const language = pick();
document.documentElement.lang = language;
document.cookie = `lang=${language}; Path=/; SameSite=Strict; Max-Age=31536000`;

export function t(key, params = {}) {
    let message = LANGUAGES[language][key] ?? en[key] ?? key;
    if (typeof message === 'object') {
        message = params.count === 1 ? message.one : message.other;
    }
    return String(message).replace(/\{(\w+)\}/g, (_, name) => params[name] ?? '');
}

export function setLanguage(lang) {
    try {
        localStorage.setItem(KEY, lang);
    } catch {
        // private mode: the cookie still carries it for this visit
    }
    document.cookie = `lang=${lang}; Path=/; SameSite=Strict; Max-Age=31536000`;
    location.reload();
}

/** Translates the marked elements and wires the DE/EN switch (an element with data-lang buttons). */
export function translatePage(root = document) {
    root.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.dataset.i18n); });
    root.querySelectorAll('[data-i18n-placeholder]').forEach(el => { el.placeholder = t(el.dataset.i18nPlaceholder); });
    root.querySelectorAll('[data-i18n-aria-label]').forEach(el => { el.setAttribute('aria-label', t(el.dataset.i18nAriaLabel)); });
    root.querySelectorAll('[data-lang]').forEach(b => {
        b.setAttribute('aria-pressed', String(b.dataset.lang === language));
        b.addEventListener('click', () => setLanguage(b.dataset.lang));
    });
}

export const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
