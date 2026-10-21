// Privacy notice: fixed sections in the page's language, the data controller
// (name, address, email) and who runs the server from the organiser's settings.

import { api } from '../js/api.js';
import { escapeHtml, t, translatePage } from '../js/i18n.js';

translatePage();
document.title = `${t('privacy.title')} · ${t('common.portal')}`;

const SECTIONS = ['purpose', 'data', 'basis', 'recipients', 'retention', 'cookies', 'rights', 'complaint'];

// The recipients name the hoster as processor, or say that the organiser runs the server itself
function sectionText(section, hosting) {
    if (section !== 'recipients') return t(`privacy.${section}.text`);
    return `${t('privacy.recipients.text')} ${hosting ? t('privacy.recipients.hosted', { hosting }) : t('privacy.recipients.ownServer')}`;
}

async function render() {
    let controller = {};
    let hosting = null;
    try {
        const privacy = await api('/privacy');
        controller = privacy.controller || {};
        hosting = privacy.hosting;
    } catch {
        // shown without the controller's details
    }
    const who = [controller.name, controller.address, controller.email].filter(Boolean).map(escapeHtml).join('<br>')
        || `<span class="muted">${escapeHtml(t('privacy.controllerMissing'))}</span>`;
    document.getElementById('privacy').innerHTML = `
        <h2>${escapeHtml(t('privacy.controller'))}</h2><p>${who}</p>
        ${SECTIONS.map(s => `<h2 class="mt-18">${escapeHtml(t(`privacy.${s}.title`))}</h2><p>${escapeHtml(sectionText(s, hosting))}</p>`).join('')}`;
}

render();
