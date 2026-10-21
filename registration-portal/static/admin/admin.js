// Placeholder until the organiser pages exist (phase 6): only log-out.
import { api } from '../js/api.js';
import { translatePage } from '../js/i18n.js';

translatePage();
document.getElementById('logout').addEventListener('click', async () => {
    await api('/auth/logout', 'POST').catch(() => {});
    location.href = '/login/';
});
