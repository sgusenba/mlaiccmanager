// Privacy notice: fixed sections in the page's language, the data controller
// (name, address, email) from the organiser's settings.

import { api } from '../js/api.js';
import { escapeHtml, t, translatePage } from '../js/i18n.js';

translatePage();
document.title = `${t('privacy.title')} · ${t('common.portal')}`;

const SECTIONS = ['purpose', 'data', 'basis', 'recipients', 'retention', 'cookies', 'rights', 'complaint'];

async function render() {
    let controller = {};
    try {
        controller = (await api('/privacy')).controller || {};
    } catch {
        // shown without the controller's details
    }
    const who = [controller.name, controller.address, controller.email].filter(Boolean).map(escapeHtml).join('<br>')
        || `<span class="muted">${escapeHtml(t('privacy.controllerMissing'))}</span>`;
    document.getElementById('privacy').innerHTML = `
        <h2>${escapeHtml(t('privacy.controller'))}</h2><p>${who}</p>
        ${SECTIONS.map(s => `<h2 class="mt-18">${escapeHtml(t(`privacy.${s}.title`))}</h2><p>${escapeHtml(t(`privacy.${s}.text`))}</p>`).join('')}`;
}

render();
