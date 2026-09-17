const API = '/api';

const state = {
    disciplines: [],
    ranges: []
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
    const [disciplines, relayData] = await Promise.all([
        api('/available-disciplines'),
        api('/rmgmt')
    ]);
    state.disciplines = disciplines;
    state.ranges = relayData.ranges;
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
        tbody.innerHTML = '<tr><td colspan="8" class="px-4 py-8 text-sm text-gray-500 text-center">No disciplines yet.</td></tr>';
        return;
    }

    const sorted = [...state.disciplines].sort((a, b) => a.id - b.id);

    tbody.innerHTML = sorted.map(d => {
        const isTeam = d.level === 'team';
        const teamInfo = isTeam
            ? `${escapeHtml(d.based_on || '')}${d.team_size ? ` (${d.team_size})` : ''}`
            : '';
        const distanceOptions = state.ranges.map(r =>
            `<option value="${escapeHtml(r.id)}" ${r.id === d.shooting_distance ? 'selected' : ''}>${escapeHtml(r.name)}</option>`
        ).join('');

        return `
        <tr data-id="${d.id}">
            <td class="px-4 py-2 text-sm text-gray-500">${d.id}</td>
            <td class="px-4 py-2 text-sm font-medium">${escapeHtml(d.event)}</td>
            <td class="px-4 py-2 text-sm">${categoryBadge(d.category)}</td>
            <td class="px-4 py-2 text-sm">${levelBadge(d.level)}</td>
            <td class="px-4 py-2 text-sm">${escapeHtml(d.type)}</td>
            <td class="px-4 py-2 text-sm text-gray-600">${teamInfo}</td>
            <td class="px-4 py-2 text-sm">
                <select class="shooting-distance px-2 py-1 border border-gray-300 rounded-md text-sm ${isTeam ? 'opacity-50' : ''}" data-discipline-id="${d.id}" ${isTeam ? 'disabled title="Team disciplines are not assigned to individual lanes"' : ''}>
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

function toggleTeamFields() {
    const level = document.getElementById('form-level').value;
    const isTeam = level === 'team';
    document.getElementById('based-on-group').classList.toggle('hidden', !isTeam);
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
        document.getElementById('form-event').value = discipline.event || '';
        document.getElementById('form-category').value = discipline.category || 'rifle';
        document.getElementById('form-level').value = discipline.level || 'individual';
        document.getElementById('form-type').value = discipline.type || 'original';
        document.getElementById('form-based-on').value = discipline.based_on || '';
        document.getElementById('form-team-size').value = discipline.team_size || 3;
        document.getElementById('form-shooting-distance').value = discipline.shooting_distance || '';
    } else {
        title.textContent = 'Add Discipline';
        document.getElementById('form-id').value = '';
        document.getElementById('discipline-form').reset();
    }
    toggleTeamFields();
    document.getElementById('form-event').focus();
}

function hideForm() {
    document.getElementById('add-form-container').classList.add('hidden');
    document.getElementById('discipline-form').reset();
    document.getElementById('form-id').value = '';
}

function formData() {
    const level = document.getElementById('form-level').value;
    const data = {
        event: document.getElementById('form-event').value.trim(),
        category: document.getElementById('form-category').value,
        level,
        type: document.getElementById('form-type').value
    };
    if (level === 'team') {
        data.based_on = document.getElementById('form-based-on').value.trim() || null;
        data.team_size = parseInt(document.getElementById('form-team-size').value, 10) || 3;
    } else {
        data.based_on = null;
        data.team_size = null;
    }
    const sd = document.getElementById('form-shooting-distance').value;
    data.shooting_distance = sd || null;
    return data;
}

function setupListeners() {
    document.getElementById('add-btn').addEventListener('click', () => showForm(null));
    document.getElementById('cancel-btn').addEventListener('click', hideForm);
    document.getElementById('form-level').addEventListener('change', toggleTeamFields);

    document.getElementById('discipline-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const id = document.getElementById('form-id').value;
        const data = formData();
        try {
            if (id) {
                await api(`/available-disciplines/${id}`, 'PUT', data);
                showMessage('Discipline updated', 'success');
            } else {
                await api('/available-disciplines', 'POST', data);
                showMessage('Discipline created', 'success');
            }
            hideForm();
            await loadData();
            renderTable();
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
            if (!confirm('Delete this discipline?')) return;
            try {
                await api(`/available-disciplines/${id}`, 'DELETE');
                showMessage('Discipline deleted', 'success');
                await loadData();
                renderTable();
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
            await api('/available-disciplines/shooting-distances', 'PUT', { shooting_distances: mapping });
            showMessage('Shooting distances saved', 'success');
            await loadData();
            renderTable();
        } catch (error) {
            showMessage(error.message);
        }
    });

    document.getElementById('discipline-table-body').addEventListener('change', (event) => {
        if (event.target.classList.contains('shooting-distance')) {
            document.getElementById('save-distances-btn').classList.remove('hidden');
        }
    });
}

async function init() {
    try {
        await loadData();
        renderTable();
        setupListeners();
    } catch (error) {
        console.error(error);
        showMessage(`Could not load data: ${error.message}`);
    }
}

document.addEventListener('DOMContentLoaded', init);
