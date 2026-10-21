// Log-in page. A club logs in with its access code, typed or from the invite
// link (/login/#c=CODE): the code is in the #fragment, which the browser
// never sends to a server, and is removed from the address bar at once.
// Organisers log in with name and password, then the authenticator code if
// the server asks for it.

import { api } from '../js/api.js';
import { t, translatePage } from '../js/i18n.js';

translatePage();
document.title = t('login.title');

const clubForm = document.getElementById('club-form');
const organiserForm = document.getElementById('organiser-form');

async function clubLogin(code) {
    const error = document.getElementById('club-error');
    error.textContent = '';
    try {
        await api('/auth/club', 'POST', { code });
        location.href = '/sheet/';
    } catch (e) {
        error.textContent = e.message;
        document.getElementById('link-state').hidden = true;
    }
}

clubForm.addEventListener('submit', (e) => {
    e.preventDefault();
    clubLogin(document.getElementById('code').value);
});

organiserForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const error = document.getElementById('organiser-error');
    const totpField = document.getElementById('totp-field');
    error.textContent = '';
    try {
        await api('/auth/organiser', 'POST', {
            username: document.getElementById('username').value,
            password: document.getElementById('password').value,
            totp: totpField.hidden ? null : document.getElementById('totp').value
        });
        location.href = '/admin/';
    } catch (err) {
        if (err.body.totp_required) {
            totpField.hidden = false;
            document.getElementById('totp').focus();
        }
        error.textContent = err.message;
    }
});

// Invite link: take the code from the fragment, clear the address bar, log in
const match = location.hash.match(/^#c=([0-9A-Za-z-]{16,24})$/);
if (match) {
    history.replaceState(null, '', location.pathname);
    document.getElementById('code').value = match[1];
    document.getElementById('link-state').hidden = false;
    clubLogin(match[1]);
} else {
    // Already logged in: straight to the own page
    api('/session').then(s => {
        location.href = s.role === 'club' ? '/sheet/' : '/admin/';
    }).catch(() => document.getElementById('code').focus());
}
