// Rankings page: what this meet ranks (/api/rankings). A ranking ranks one or
// more entries together; an entry is one discipline or the sum of several.
// Exported and imported on their own, or loaded from the shipped defaults.

import { withButtonFeedback } from '../js/buttonFeedback.js';

const API = '/api';

const state = {
    config: { max_disciplines_per_ranking: 4, rankings: [] },
    disciplines: [],
    editing: null // the ranking being edited, null when adding
};

class ApiError extends Error {
    constructor(status, body) {
        super(body?.error || `HTTP error! status: ${status}`);
        this.status = status;
        this.body = body;
    }
}

async function api(path, method = 'GET', body) {
    const options = { method, headers: {} };
    if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
    }
    const response = await fetch(`${API}${path}`, options);
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new ApiError(response.status, json);
    return json;
}

const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let messageTimer;
function showMessage(text, type = 'error') {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = `mx-4 mb-4 px-4 py-3 rounded-md ${type === 'error'
        ? 'bg-red-100 text-red-800 border border-red-200'
        : 'bg-green-100 text-green-800 border border-green-200'}`;
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), type === 'error' ? 10000 : 3000);
}

const disciplineById = (id) => state.disciplines.find(d => d.id === id);
const label = (id) => {
    const d = disciplineById(id);
    return d ? d.short_name || d.name : `#${id}`;
};

async function loadData() {
    const [config, disciplines] = await Promise.all([api('/rankings'), api('/available-disciplines')]);
    state.config = config;
    state.disciplines = disciplines;
    document.getElementById('max-disciplines').value = config.max_disciplines_per_ranking;
}

// --- list --------------------------------------------------------------------

/** "WHITO | WHITR" for pooled entries, a sum as "Remington_O (COLT + MALO)". */
function entriesText(ranking) {
    return ranking.entries.map(e => {
        const names = e.discipline_ids.map(label).map(escapeHtml);
        return e.discipline_ids.length > 1
            ? `<span class="font-medium">${escapeHtml(e.label)}</span> (${names.join(' + ')})`
            : names[0];
    }).join(' <span class="text-gray-400">|</span> ');
}

function renderTable() {
    const tbody = document.getElementById('ranking-table-body');
    const rankings = state.config.rankings;
    if (!rankings.length) {
        tbody.innerHTML = `<tr><td colspan="7" class="px-4 py-8 text-sm text-gray-500 text-center">
            No rankings yet: add one, or load the defaults (one ranking per discipline, plus Remington and Schulhof).</td></tr>`;
        return;
    }
    tbody.innerHTML = rankings.map((r, i) => `
        <tr data-id="${r.id}">
            <td class="px-2 py-2 text-sm whitespace-nowrap text-gray-400">
                <button class="up-btn px-1 hover:text-gray-800 disabled:opacity-30" ${i === 0 ? 'disabled' : ''} title="Move up">▲</button>
                <button class="down-btn px-1 hover:text-gray-800 disabled:opacity-30" ${i === rankings.length - 1 ? 'disabled' : ''} title="Move down">▼</button>
            </td>
            <td class="px-4 py-2 text-sm font-medium">${escapeHtml(r.name)}</td>
            <td class="px-4 py-2 text-sm text-gray-600">${escapeHtml(r.short_name || '')}</td>
            <td class="px-4 py-2 text-sm">${r.level === 'team'
                ? '<span class="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-purple-100 text-purple-800">Team</span>'
                : '<span class="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">Individual</span>'}</td>
            <td class="px-4 py-2 text-sm text-gray-700">${entriesText(r)}</td>
            <td class="px-4 py-2 text-sm">${r.one_start_per_competitor ? 'yes' : ''}</td>
            <td class="px-4 py-2 text-sm whitespace-nowrap">
                <button class="edit-btn text-blue-600 hover:text-blue-800 mr-3">Edit</button>
                <button class="delete-btn text-red-600 hover:text-red-800">Delete</button>
            </td>
        </tr>`).join('');
}

// --- form ----------------------------------------------------------------------

const formLevel = () => document.getElementById('form-level').value;

/** The disciplines an entry can pick: of the ranking's level, active ones plus those already picked. */
function pickable(selected) {
    const team = formLevel() === 'team';
    return state.disciplines
        .filter(d => (d.level === 'team') === team && (d.active !== false || selected.has(d.id)))
        .sort((a, b) => (a.category || '').localeCompare(b.category || '') || a.id - b.id);
}

function entryRow(entry = { label: '', discipline_ids: [] }) {
    const selected = new Set(entry.discipline_ids);
    const options = pickable(selected).map(d => `
        <option value="${d.id}" ${selected.has(d.id) ? 'selected' : ''}>${escapeHtml(d.name)}${d.short_name
            ? ` (${escapeHtml(d.short_name)})` : ''} · ${escapeHtml(d.category)}</option>`).join('');
    return `
        <div class="entry flex flex-wrap items-start gap-3 p-3 border border-gray-200 rounded-md">
            <select multiple size="5" class="entry-disciplines flex-1 min-w-[16rem] px-2 py-1 border border-gray-300 rounded-md text-sm">${options}</select>
            <div class="w-56">
                <input type="text" class="entry-label w-full px-3 py-2 border border-gray-300 rounded-md text-sm"
                       placeholder="Name of the sum" value="${escapeHtml(entry.label || '')}">
                <p class="entry-kind mt-1 text-xs text-gray-500"></p>
            </div>
            <button type="button" class="remove-entry-btn text-red-600 hover:text-red-800 text-sm">Remove</button>
        </div>`;
}

function readEntries() {
    return [...document.querySelectorAll('#form-entries .entry')].map(el => ({
        label: el.querySelector('.entry-label').value.trim() || null,
        discipline_ids: [...el.querySelector('.entry-disciplines').selectedOptions].map(o => Number(o.value))
    }));
}

/** Shows per entry whether it is one discipline or a sum, and the count against the meet's limit. */
function updateEntryHints() {
    const team = formLevel() === 'team';
    document.querySelectorAll('#form-entries .entry').forEach(el => {
        const picked = [...el.querySelector('.entry-disciplines').selectedOptions].map(o => label(Number(o.value)));
        const n = picked.length;
        const input = el.querySelector('.entry-label');
        input.classList.toggle('hidden', n < 2);
        el.querySelector('.entry-kind').textContent = n === 0 ? 'Pick a discipline'
            : n === 1 ? `One discipline: ${picked[0]}`
            : team ? 'A team ranking cannot add up team disciplines'
            : `Sum of ${picked.join(' + ')}: name it`;
    });
    const distinct = new Set(readEntries().flatMap(e => e.discipline_ids));
    const max = state.config.max_disciplines_per_ranking;
    const count = document.getElementById('discipline-count');
    count.textContent = `${distinct.size} of at most ${max} disciplines`;
    count.className = `text-xs ${distinct.size > max ? 'text-red-600 font-medium' : 'text-gray-500'}`;
}

function updateLevelHints() {
    const team = formLevel() === 'team';
    document.getElementById('one-start-hint').textContent = team
        ? 'A competitor may be a member of only one team of this ranking. Off: of one team per team discipline.'
        : 'A competitor may start in only one of this ranking\'s disciplines. Off: several starts are allowed and the best result counts.';
    document.getElementById('entries-hint').textContent = team
        ? 'Each entry is one team discipline; several entries rank their teams together.'
        : 'Each entry is one discipline, or several (Ctrl/Cmd-click) whose best results are added up. '
          + 'Several entries are ranked together: each competitor once, on their best result.';
}

function renderEntries(entries) {
    document.getElementById('form-entries').innerHTML = (entries.length ? entries : [undefined]).map(entryRow).join('');
    updateEntryHints();
}

function showForm(ranking) {
    state.editing = ranking;
    document.getElementById('form-container').classList.remove('hidden');
    document.getElementById('form-title').textContent = ranking ? 'Edit Ranking' : 'Add Ranking';
    document.getElementById('form-name').value = ranking?.name || '';
    document.getElementById('form-short-name').value = ranking?.short_name || '';
    document.getElementById('form-level').value = ranking?.level || 'individual';
    document.getElementById('form-one-start').checked = !!ranking?.one_start_per_competitor;
    updateLevelHints();
    renderEntries(ranking ? ranking.entries : []);
    document.getElementById('form-container').scrollIntoView({ behavior: 'smooth', block: 'start' });
    document.getElementById('form-name').focus();
}

function hideForm() {
    state.editing = null;
    document.getElementById('form-container').classList.add('hidden');
}

// --- files -----------------------------------------------------------------------

function download(value, fileName) {
    const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

const today = () => new Date().toISOString().slice(0, 10);

async function reload() {
    await loadData();
    renderTable();
}

// --- events ------------------------------------------------------------------------

function setupListeners() {
    document.getElementById('add-btn').addEventListener('click', () => showForm(null));
    document.getElementById('cancel-btn').addEventListener('click', hideForm);
    document.getElementById('add-entry-btn').addEventListener('click', () => {
        document.getElementById('form-entries').insertAdjacentHTML('beforeend', entryRow());
        updateEntryHints();
    });
    document.getElementById('form-level').addEventListener('change', () => {
        updateLevelHints();
        renderEntries([]); // the disciplines to pick from differ
    });
    document.getElementById('form-entries').addEventListener('change', updateEntryHints);
    document.getElementById('form-entries').addEventListener('click', (event) => {
        if (event.target.closest('.remove-entry-btn')) {
            event.target.closest('.entry').remove();
            updateEntryHints();
        }
    });

    document.getElementById('ranking-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const entries = readEntries().filter(e => e.discipline_ids.length);
        entries.forEach(e => { if (e.discipline_ids.length < 2) e.label = null; });
        const data = {
            name: document.getElementById('form-name').value.trim(),
            short_name: document.getElementById('form-short-name').value.trim() || null,
            level: formLevel(),
            one_start_per_competitor: document.getElementById('form-one-start').checked,
            entries
        };
        try {
            await withButtonFeedback(event.submitter, () => state.editing
                ? api(`/rankings/${state.editing.id}`, 'PUT', { ...data, version: state.editing.version })
                : api('/rankings', 'POST', data));
            hideForm();
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('ranking-table-body').addEventListener('click', async (event) => {
        const row = event.target.closest('tr[data-id]');
        if (!row) return;
        const id = Number(row.dataset.id);
        const ranking = state.config.rankings.find(r => r.id === id);
        try {
            if (event.target.closest('.edit-btn')) {
                showForm(ranking);
            } else if (event.target.closest('.delete-btn')) {
                if (!confirm(`Delete the ranking ${ranking.name}?`)) return;
                await withButtonFeedback(event.target.closest('.delete-btn'),
                    () => api(`/rankings/${id}?version=${ranking.version}`, 'DELETE'));
                await reload();
            } else if (event.target.closest('.up-btn') || event.target.closest('.down-btn')) {
                const ids = state.config.rankings.map(r => r.id);
                const i = ids.indexOf(id);
                const j = event.target.closest('.up-btn') ? i - 1 : i + 1;
                [ids[i], ids[j]] = [ids[j], ids[i]];
                await api('/rankings/order', 'PUT', { ids });
                await reload();
            }
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('save-max-btn').addEventListener('click', async () => {
        try {
            const max = parseInt(document.getElementById('max-disciplines').value, 10);
            await withButtonFeedback(document.getElementById('save-max-btn'),
                () => api('/rankings/settings', 'PUT', { max_disciplines_per_ranking: max }));
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('defaults-btn').addEventListener('click', async () => {
        if (!confirm('Replace this meet\'s rankings with the default rankings?')) return;
        try {
            await withButtonFeedback(document.getElementById('defaults-btn'), () => api('/rankings/defaults', 'POST'));
            hideForm();
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('export-btn').addEventListener('click', async () => {
        try {
            await withButtonFeedback(document.getElementById('export-btn'),
                async () => download(await api('/rankings/export'), `rankings-${today()}.json`));
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('import-btn').addEventListener('click', () => document.getElementById('import-file').click());
    document.getElementById('import-file').addEventListener('change', async (event) => {
        const file = event.target.files[0];
        event.target.value = '';
        if (!file) return;
        if (!confirm('Replace this meet\'s rankings with those of the file?')) return;
        try {
            const content = JSON.parse(await file.text());
            const config = await api('/rankings/import', 'POST', content);
            showMessage(`Imported ${config.rankings.length} rankings`, 'success');
            hideForm();
            await reload();
        } catch (error) {
            showMessage(error instanceof SyntaxError ? 'The file is not valid JSON' : error.message);
        }
    });
}

document.addEventListener('DOMContentLoaded', async () => {
    try {
        await reload();
        setupListeners();
    } catch (error) {
        console.error(error);
        showMessage(`Could not load data: ${error.message}`);
    }
});
