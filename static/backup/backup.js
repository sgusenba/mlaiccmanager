const API = '/api';

let messageTimer;
function showMessage(text, type = 'error') {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = `mx-4 mb-4 px-4 py-3 rounded-md max-w-5xl ${type === 'error'
        ? 'bg-red-100 text-red-800 border border-red-200'
        : 'bg-green-100 text-green-800 border border-green-200'}`;
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), type === 'error' ? 8000 : 10000);
}

async function errorOf(response) {
    try {
        const json = await response.json();
        if (json?.error) return json.error;
    } catch { /* not JSON */ }
    return `HTTP error! status: ${response.status}`;
}

// The server names the file after the time the backup was taken
function fileNameOf(response) {
    const match = /filename="?([^";]+)"?/.exec(response.headers.get('Content-Disposition') || '');
    return match ? match[1] : 'mlaiccmanager-backup.zip';
}

async function downloadBackup() {
    const button = document.getElementById('download-btn');
    button.disabled = true;
    try {
        const response = await fetch(`${API}/backup`, { cache: 'no-store' });
        if (!response.ok) throw new Error(await errorOf(response));
        const url = URL.createObjectURL(await response.blob());
        const link = document.createElement('a');
        link.href = url;
        link.download = fileNameOf(response);
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        showMessage(`Backup ${link.download} downloaded.`, 'success');
    } catch (error) {
        showMessage(`Could not create the backup: ${error.message}`);
    } finally {
        button.disabled = false;
    }
}

async function restoreBackup(event) {
    event.preventDefault();
    const input = document.getElementById('restore-file');
    const file = input.files[0];
    if (!file) return;
    if (!confirm(`Replace ALL current data with the backup "${file.name}"?\n\n`
        + 'The current data is saved on the server first, so this can be undone.')) {
        return;
    }

    const button = document.getElementById('restore-btn');
    button.disabled = true;
    try {
        const response = await fetch(`${API}/backup/restore`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/zip' },
            body: file
        });
        if (!response.ok) throw new Error(await errorOf(response));
        const result = await response.json();
        input.value = '';
        showMessage(`Backup restored (${result.restored_files.join(', ')}).`
            + (result.safety_copy ? ` The previous data was saved to ${result.safety_copy}.` : ''), 'success');
    } catch (error) {
        showMessage(`Could not restore the backup: ${error.message}`);
    } finally {
        button.disabled = false;
    }
}

// --- Automatic external backup ---

const ext = {
    form: document.getElementById('external-form'),
    enabled: document.getElementById('ext-enabled'),
    url: document.getElementById('ext-url'),
    token: document.getElementById('ext-token'),
    debounce: document.getElementById('ext-debounce'),
    maxDelay: document.getElementById('ext-max-delay'),
    warning: document.getElementById('ext-url-warning'),
    status: document.getElementById('ext-status'),
    buttons: ['ext-save-btn', 'ext-test-btn', 'ext-run-btn'].map(id => document.getElementById(id))
};

// Loopback, private ranges, single-label and local host names count as a trusted network
const TRUSTED_HOST = /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.|\[(::1|f[cd]|fe80))|\.(local|lan|internal|home)$|^[^.]+$/i;

// Plain http is fine on a trusted network, not across the internet
function updateUrlWarning() {
    let warn = false;
    try {
        const url = new URL(ext.url.value.trim());
        warn = url.protocol === 'http:' && !TRUSTED_HOST.test(url.hostname);
    } catch { /* not a URL yet */ }
    ext.warning.classList.toggle('hidden', !warn);
}

function formatTime(iso) {
    return iso ? new Date(iso).toLocaleString() : '—';
}

function formatSize(bytes) {
    return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function renderExternal({ settings, status }) {
    ext.enabled.checked = settings.enabled;
    ext.url.value = settings.url;
    ext.token.value = '';
    ext.token.placeholder = settings.token_set ? 'saved — leave empty to keep it' : '';
    ext.debounce.value = settings.debounceSeconds;
    ext.maxDelay.value = settings.maxDelaySeconds;
    updateUrlWarning();

    const rows = [
        ['Last backup', status.last_success
            ? `${formatTime(status.last_success)} — ${status.last_backup_name} (${formatSize(status.last_backup_size)})`
            : '—'],
        ['Waiting to be sent', status.pending ? 'Yes, there are changes that are not backed up yet' : 'No']
    ];
    if (status.last_error) {
        rows.push(['Last error', `${status.last_error} (${formatTime(status.last_error_at)})`]);
    }
    ext.status.replaceChildren(...rows.flatMap(([label, value]) => {
        const dt = document.createElement('dt');
        dt.className = 'text-gray-500';
        dt.textContent = label;
        const dd = document.createElement('dd');
        dd.textContent = value;
        if (label === 'Last error') dd.className = 'text-red-700';
        return [dt, dd];
    }));
}

function formSettings() {
    return {
        enabled: ext.enabled.checked,
        url: ext.url.value.trim(),
        token: ext.token.value.trim(),
        debounceSeconds: Number(ext.debounce.value),
        maxDelaySeconds: Number(ext.maxDelay.value)
    };
}

async function loadExternal() {
    try {
        const response = await fetch(`${API}/backup/external`, { cache: 'no-store' });
        if (!response.ok) throw new Error(await errorOf(response));
        renderExternal(await response.json());
    } catch (error) {
        showMessage(`Could not load the external backup settings: ${error.message}`);
    }
}

async function externalRequest(method, path, body, successText) {
    ext.buttons.forEach(button => { button.disabled = true; });
    try {
        const response = await fetch(`${API}/backup/external${path}`, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined
        });
        if (!response.ok) throw new Error(await errorOf(response));
        const result = await response.json();
        if (result.settings) renderExternal(result);
        showMessage(successText, 'success');
    } catch (error) {
        showMessage(error.message);
        if (path === '/run') await loadExternal();
    } finally {
        ext.buttons.forEach(button => { button.disabled = false; });
    }
}

ext.form.addEventListener('submit', event => {
    event.preventDefault();
    externalRequest('PUT', '', formSettings(), 'External backup settings saved.');
});
document.getElementById('ext-test-btn').addEventListener('click', () =>
    externalRequest('POST', '/test', formSettings(), 'The receiver accepted the connection.'));
document.getElementById('ext-run-btn').addEventListener('click', () =>
    externalRequest('POST', '/run', null, 'Backup sent.'));
ext.url.addEventListener('input', updateUrlWarning);
loadExternal();

document.getElementById('download-btn').addEventListener('click', downloadBackup);
document.getElementById('restore-form').addEventListener('submit', restoreBackup);
