// Translations of the user interface. Every page imports this module (via
// appNav.js or its own scripts), which picks the language, translates the
// page's static HTML and offers t() for the texts the scripts build.
//
// Static HTML is marked with attributes holding a message key:
//   data-i18n="key"              the element's text
//   data-i18n-html="key"         the element's HTML (for messages with markup)
//   data-i18n-placeholder="key"  data-i18n-title="key"  data-i18n-aria-label="key"
//
// A message is a string with {name} placeholders, or for counted things an
// object of plural forms ({ one, other }) chosen by the `count` parameter.
// A key missing in a language falls back to English, then to the key itself.
//
// The language is the one picked in the sidebar (kept in localStorage and in
// the `lang` cookie, which the backend reads for its own messages), else the
// browser's preferred language if there is a translation for it, else English.
// Adding a language is one file in static/i18n/ plus one line in LANGUAGES.

import en from '../i18n/en.js';
import de from '../i18n/de.js';

export const LANGUAGES = {
    en: { name: 'English', messages: en },
    de: { name: 'Deutsch', messages: de }
};

export const DEFAULT_LANGUAGE = 'en';
const STORAGE_KEY = 'mlaiccmanager.language';
const COOKIE = 'lang';

function storedLanguage() {
    try {
        return localStorage.getItem(STORAGE_KEY);
    } catch {
        return null;
    }
}

function cookieLanguage() {
    const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${COOKIE}=([^;]*)`));
    return match ? decodeURIComponent(match[1]) : null;
}

function browserLanguage() {
    const preferred = navigator.languages?.length ? navigator.languages : [navigator.language];
    for (const tag of preferred) {
        const base = String(tag || '').toLowerCase().split('-')[0];
        if (base in LANGUAGES) return base;
    }
    return null;
}

function pickLanguage() {
    for (const candidate of [storedLanguage(), cookieLanguage(), browserLanguage()]) {
        if (candidate && candidate in LANGUAGES) return candidate;
    }
    return DEFAULT_LANGUAGE;
}

function writeCookie(lang) {
    document.cookie = `${COOKIE}=${encodeURIComponent(lang)}; path=/; max-age=31536000; SameSite=Lax`;
}

/** The language of this page, e.g. 'de'; also the locale for Intl formatting. */
export const language = pickLanguage();
export const locale = language;

const messages = LANGUAGES[language].messages;
const pluralRules = new Intl.PluralRules(locale);

function lookup(key) {
    if (Object.prototype.hasOwnProperty.call(messages, key)) return messages[key];
    if (Object.prototype.hasOwnProperty.call(en, key)) return en[key];
    return undefined;
}

/**
 * The message for `key` in the current language with its {placeholders}
 * filled from `params`; plural messages pick their form by `params.count`.
 */
export function t(key, params = {}) {
    let message = lookup(key);
    if (message === undefined) {
        console.warn(`Missing translation: ${key}`);
        return key;
    }
    if (typeof message === 'object') {
        message = message[pluralRules.select(Number(params.count))] ?? message.other;
    }
    return String(message).replace(/\{(\w+)\}/g, (placeholder, name) =>
        Object.prototype.hasOwnProperty.call(params, name) ? String(params[name] ?? '') : placeholder);
}

/** Whether `key` has a message (in the current language or English). */
export function hasMessage(key) {
    return lookup(key) !== undefined;
}

/** t(key, params), or `fallback` if there is no such message (e.g. for a value from the data). */
export function tOr(key, fallback, params) {
    return hasMessage(key) ? t(key, params) : fallback;
}

/** Switches the language and reloads the page in it. */
export function setLanguage(lang) {
    if (!(lang in LANGUAGES)) return;
    try {
        localStorage.setItem(STORAGE_KEY, lang);
    } catch {
        // Private mode without storage: the cookie still carries the choice
    }
    writeCookie(lang);
    location.reload();
}

/** Fills a <select> with the languages and switches to the one picked. */
export function bindLanguageSelect(select) {
    select.innerHTML = '';
    Object.entries(LANGUAGES).forEach(([code, { name }]) => select.add(new Option(name, code, false, code === language)));
    select.addEventListener('change', () => setLanguage(select.value));
}

/** Formats a number in the current language, e.g. 1.234,5 in German. */
export function formatNumber(value, options) {
    return new Intl.NumberFormat(locale, options).format(value);
}

/** Translates the marked elements in `root` (see the top of this file). */
export function translatePage(root = document) {
    root.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.dataset.i18n); });
    root.querySelectorAll('[data-i18n-html]').forEach(el => { el.innerHTML = t(el.dataset.i18nHtml); });
    root.querySelectorAll('[data-i18n-placeholder]').forEach(el => { el.placeholder = t(el.dataset.i18nPlaceholder); });
    root.querySelectorAll('[data-i18n-title]').forEach(el => { el.title = t(el.dataset.i18nTitle); });
    root.querySelectorAll('[data-i18n-aria-label]').forEach(el => { el.setAttribute('aria-label', t(el.dataset.i18nAriaLabel)); });
}

// Keep the backend's language in step with the page's, also for a language
// that came from the browser rather than an explicit choice
if (cookieLanguage() !== language) writeCookie(language);
document.documentElement.lang = language;
// Module scripts run once the HTML is parsed, so the static texts are there
translatePage();
