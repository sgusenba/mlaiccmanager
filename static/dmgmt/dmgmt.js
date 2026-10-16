import { withButtonFeedback } from '../js/buttonFeedback.js';

const API = '/api';

const state = {
    disciplines: [],
    ranges: [],
    maxDisciplines: 4
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
    el.className = `mx-4 mb-4 px-4 py-3 rounded-md no-print ${type === 'error'
        ? 'bg-red-100 text-red-800 border border-red-200'
        : 'bg-green-100 text-green-800 border border-green-200'}`;
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), type === 'error' ? 8000 : 3000);
}

async function loadData() {
    const [disciplines, relayData, settings] = await Promise.all([
        api('/available-disciplines'),
        api('/rmgmt'),
        api('/disciplines/settings')
    ]);
    state.disciplines = disciplines;
    state.ranges = relayData.ranges;
    state.maxDisciplines = settings.max_disciplines;
    document.getElementById('max-disciplines').value = settings.max_disciplines;
}

/** A team's composition as short names (or names), e.g. "TANO + TANR". */
function compositionText(team) {
    const names = (team.team_of || [])
        .map(id => state.disciplines.find(d => d.id === id))
        .filter(Boolean)
        .map(d => escapeHtml(d.short_name || d.name));
    return names.join(' + ') || '<span class="text-red-600">none</span>';
}

/** The checkboxes of the individual disciplines a team of the form's category can count, ticked from checkedIds. */
function renderTeamOfOptions(checkedIds) {
    const category = document.getElementById('form-category').value;
    const checked = new Set(checkedIds || []);
    document.getElementById('team-of-hint').textContent =
        `The individual disciplines whose starts can be team members (1 to ${state.maxDisciplines}).`;
    document.getElementById('form-team-of').innerHTML = state.disciplines
        .filter(d => d.level !== 'team' && d.category === category && (d.active !== false || checked.has(d.id)))
        .map(d => `
            <label class="flex items-center gap-2 text-sm cursor-pointer">
                <input type="checkbox" class="team-of-option h-4 w-4" value="${d.id}" ${checked.has(d.id) ? 'checked' : ''}>
                <span>${escapeHtml(d.name)}${d.short_name ? ` <span class="text-gray-400">${escapeHtml(d.short_name)}</span>` : ''}</span>
            </label>`).join('') || '<p class="text-sm text-gray-500">No individual disciplines in this category.</p>';
}

function levelBadge(level) {
    if (level === 'team') return '<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-purple-100 text-purple-800">Team</span>';
    return '<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800">Individual</span>';
}

function categoryBadge(category) {
    if (category === 'pistol') return '<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-orange-100 text-orange-800">Pistol</span>';
    return '<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800">Rifle</span>';
}

function renderTable() {
    const tbody = document.getElementById('discipline-table-body');
    if (!state.disciplines.length) {
        tbody.innerHTML = '<tr><td colspan="9" class="px-4 py-8 text-sm text-gray-500 text-center">No disciplines yet.</td></tr>';
        return;
    }

    // Individual disciplines first, then teams; each by id
    const sorted = [...state.disciplines].sort((a, b) =>
        (a.level === 'team') - (b.level === 'team') || a.id - b.id);

    tbody.innerHTML = sorted.map(d => {
        const isTeam = d.level === 'team';
        const teamInfo = isTeam ? `${compositionText(d)}${d.team_size ? ` (${d.team_size} shooters)` : ''}` : '';
        const distanceOptions = state.ranges.map(r =>
            `<option value="${escapeHtml(r.id)}" ${r.id === d.shooting_distance ? 'selected' : ''}>${escapeHtml(r.name)}</option>`
        ).join('');

        return `
        <tr data-id="${d.id}" class="${d.active === false ? 'text-gray-400' : ''}">
            <td class="px-4 py-2 text-sm text-gray-500">${d.id}</td>
            <td class="px-4 py-2 text-sm font-medium">${escapeHtml(d.name)}${d.active === false
                ? ' <span class="ml-1 px-1.5 py-0.5 rounded bg-gray-100 text-gray-600 text-xs font-normal">archived</span>' : ''}</td>
            <td class="px-4 py-2 text-sm text-gray-600">${escapeHtml(d.short_name || '')}</td>
            <td class="px-4 py-2 text-sm">${categoryBadge(d.category)}</td>
            <td class="px-4 py-2 text-sm">${levelBadge(d.level)}</td>
            <td class="px-4 py-2 text-sm">${escapeHtml(d.type || '')}</td>
            <td class="px-4 py-2 text-sm text-gray-600">${teamInfo}</td>
            <td class="px-4 py-2 text-sm">
                <select class="shooting-distance px-2 py-1 border border-gray-300 rounded-md text-sm ${isTeam ? 'opacity-50' : ''}" data-discipline-id="${d.id}" ${isTeam ? 'disabled title="Team disciplines are not assigned to lanes"' : ''}>
                    <option value="">any distance</option>
                    ${distanceOptions}
                </select>
            </td>
            <td class="px-4 py-2 text-sm whitespace-nowrap no-print">
                <button class="edit-btn text-blue-600 hover:text-blue-800 mr-3">Edit</button>
                <button class="delete-btn text-red-600 hover:text-red-800">Delete</button>
            </td>
        </tr>`;
    }).join('');
}

/** Team fields for a team discipline; a team takes no lane. */
function toggleLevelFields() {
    const isTeam = document.getElementById('form-level').value === 'team';
    document.getElementById('team-of-group').classList.toggle('hidden', !isTeam);
    document.getElementById('team-size-group').classList.toggle('hidden', !isTeam);
    const sdSelect = document.getElementById('form-shooting-distance');
    sdSelect.disabled = isTeam;
    if (isTeam) sdSelect.value = '';
}

function populateDistanceOptions() {
    const select = document.getElementById('form-shooting-distance');
    const current = select.value;
    select.innerHTML = '<option value="">any distance</option>' +
        state.ranges.map(r => `<option value="${escapeHtml(r.id)}">${escapeHtml(r.name)}</option>`).join('');
    select.value = current;
}

function showForm(discipline) {
    const container = document.getElementById('add-form-container');
    const title = document.getElementById('form-title');
    container.classList.remove('hidden');
    populateDistanceOptions();

    if (discipline) {
        title.textContent = 'Edit Discipline';
        document.getElementById('form-id').value = discipline.id;
        document.getElementById('form-name').value = discipline.name || '';
        document.getElementById('form-short-name').value = discipline.short_name || '';
        document.getElementById('form-category').value = discipline.category || 'rifle';
        document.getElementById('form-level').value = discipline.level || 'individual';
        document.getElementById('form-type').value = discipline.type || 'original';
        document.getElementById('form-team-size').value = discipline.team_size || 3;
        document.getElementById('form-shooting-distance').value = discipline.shooting_distance || '';
        document.getElementById('form-active').checked = discipline.active !== false;
        renderTeamOfOptions(discipline.team_of);
    } else {
        title.textContent = 'Add Discipline';
        document.getElementById('form-id').value = '';
        document.getElementById('discipline-form').reset();
        document.getElementById('form-active').checked = true;
        renderTeamOfOptions([]);
    }
    toggleLevelFields();
    document.getElementById('form-name').focus();
}

function hideForm() {
    document.getElementById('add-form-container').classList.add('hidden');
    document.getElementById('discipline-form').reset();
    document.getElementById('form-id').value = '';
}

function formData() {
    const level = document.getElementById('form-level').value;
    const data = {
        name: document.getElementById('form-name').value.trim(),
        short_name: document.getElementById('form-short-name').value.trim() || null,
        category: document.getElementById('form-category').value,
        level,
        type: document.getElementById('form-type').value,
        active: document.getElementById('form-active').checked
    };
    if (level === 'team') {
        data.team_size = parseInt(document.getElementById('form-team-size').value, 10) || 3;
        data.team_of = [...document.querySelectorAll('.team-of-option:checked')].map(input => Number(input.value));
    } else {
        data.team_size = null;
        data.team_of = null;
    }
    data.shooting_distance = document.getElementById('form-shooting-distance').value || null;
    return data;
}

/** Offers a JSON value as a file to save. */
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

function setupListeners() {
    document.getElementById('add-btn').addEventListener('click', () => showForm(null));
    document.getElementById('cancel-btn').addEventListener('click', hideForm);
    document.getElementById('form-level').addEventListener('change', toggleLevelFields);
    // The disciplines a team can count depend on its category
    document.getElementById('form-category').addEventListener('change', () => {
        renderTeamOfOptions([...document.querySelectorAll('.team-of-option:checked')].map(input => Number(input.value)));
    });

    document.getElementById('discipline-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const id = document.getElementById('form-id').value;
        const data = formData();
        if (data.team_of && (data.team_of.length < 1 || data.team_of.length > state.maxDisciplines)) {
            showMessage(`Tick 1 to ${state.maxDisciplines} disciplines whose results count`);
            return;
        }
        try {
            await withButtonFeedback(event.submitter, () => id
                ? api(`/available-disciplines/${id}`, 'PUT', data)
                : api('/available-disciplines', 'POST', data));
            hideForm();
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('discipline-table-body').addEventListener('click', async (event) => {
        const row = event.target.closest('tr[data-id]');
        if (!row) return;
        const id = parseInt(row.dataset.id, 10);

        if (event.target.closest('.edit-btn')) {
            const discipline = state.disciplines.find(d => d.id === id);
            if (discipline) showForm(discipline);
        }

        if (event.target.closest('.delete-btn')) {
            if (!confirm('Delete this discipline? A discipline in use cannot be deleted; archive it instead.')) return;
            try {
                await withButtonFeedback(event.target.closest('.delete-btn'), () => api(`/available-disciplines/${id}`, 'DELETE'));
                await reload();
            } catch (error) {
                showMessage(error.message);
            }
        }
    });

    document.getElementById('save-distances-btn').addEventListener('click', async () => {
        const mapping = {};
        document.querySelectorAll('.shooting-distance:not([disabled])').forEach(select => {
            mapping[select.dataset.disciplineId] = select.value || null;
        });
        try {
            await withButtonFeedback(document.getElementById('save-distances-btn'),
                () => api('/available-disciplines/shooting-distances', 'PUT', { shooting_distances: mapping }));
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('discipline-table-body').addEventListener('change', async (event) => {
        if (event.target.classList.contains('shooting-distance')) {
            document.getElementById('save-distances-btn').classList.remove('hidden');
        }
    });

    document.getElementById('save-max-btn').addEventListener('click', async () => {
        try {
            const max = parseInt(document.getElementById('max-disciplines').value, 10);
            await withButtonFeedback(document.getElementById('save-max-btn'),
                () => api('/disciplines/settings', 'PUT', { max_disciplines: max }));
            await reload();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('export-btn').addEventListener('click', async () => {
        try {
            await withButtonFeedback(document.getElementById('export-btn'),
                async () => download(await api('/disciplines/export'), `disciplines-${today()}.json`));
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('import-btn').addEventListener('click', () => document.getElementById('import-file').click());
    document.getElementById('import-file').addEventListener('change', async (event) => {
        const file = event.target.files[0];
        event.target.value = '';
        if (!file) return;
        const mode = document.getElementById('import-mode').value;
        if (mode === 'replace' && !confirm('Replace the catalog? Disciplines the file lacks are removed (refused for any in use).')) return;
        try {
            const content = JSON.parse(await file.text());
            const summary = await api(`/disciplines/import?mode=${mode}`, 'POST', content);
            showMessage(`Imported: ${summary.added.length} added, ${summary.updated.length} updated, `
                + `${summary.removed.length} removed`, 'success');
            await reload();
        } catch (error) {
            showMessage(error instanceof SyntaxError ? 'The file is not valid JSON' : error.message);
        }
    });
}

async function init() {
    try {
        await reload();
        setupListeners();
    } catch (error) {
        console.error(error);
        showMessage(`Could not load data: ${error.message}`);
    }
}

document.addEventListener('DOMContentLoaded', init);
