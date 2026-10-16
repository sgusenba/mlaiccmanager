// Shows the progress of a save or delete on the clicked button itself instead of a
// "saved successfully" message: a spinner while the request runs, then a green
// checkmark for a moment, then the button's own label again.

import { t } from './i18n.js';

const CHECK_MS = 1000;

const SPINNER = () => `<svg class="btn-feedback-icon btn-feedback-spinner" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="3" opacity="0.25"></circle>
    <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="3" stroke-linecap="round"></path>
</svg><span class="btn-feedback-label">${t('common.working')}</span>`;

const CHECK = () => `<svg class="btn-feedback-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <path d="M5 13l4 4L19 7" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"></path>
</svg><span class="btn-feedback-label">${t('common.done')}</span>`;

// Runs action() with the button showing a spinner. On success the button shows a
// checkmark and the returned promise resolves once the label is back, so the caller
// can hide or re-render the button afterwards. On failure the label comes back at
// once and the error is rethrown for the caller to report.
export async function withButtonFeedback(button, action) {
    if (!button) return action();

    const original = {
        html: button.innerHTML,
        disabled: button.disabled,
        minWidth: button.style.minWidth,
        minHeight: button.style.minHeight
    };
    // Keep the button's size so the layout does not jump while it shows only an icon
    const { width, height } = button.getBoundingClientRect();
    button.style.minWidth = `${width}px`;
    button.style.minHeight = `${height}px`;
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    button.classList.add('btn-feedback', 'btn-feedback-busy');
    button.innerHTML = SPINNER();

    const restore = () => {
        button.classList.remove('btn-feedback', 'btn-feedback-busy', 'btn-feedback-done');
        button.removeAttribute('aria-busy');
        button.innerHTML = original.html;
        button.disabled = original.disabled;
        button.style.minWidth = original.minWidth;
        button.style.minHeight = original.minHeight;
    };

    let result;
    try {
        result = await action();
    } catch (error) {
        restore();
        throw error;
    }

    button.classList.replace('btn-feedback-busy', 'btn-feedback-done');
    button.removeAttribute('aria-busy');
    button.innerHTML = CHECK();
    await new Promise(resolve => setTimeout(resolve, CHECK_MS));
    restore();
    return result;
}
