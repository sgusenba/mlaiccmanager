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

document.getElementById('download-btn').addEventListener('click', downloadBackup);
document.getElementById('restore-form').addEventListener('submit', restoreBackup);
