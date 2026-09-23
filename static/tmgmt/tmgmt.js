// Team management page: teams of the team disciplines and their ranking.
// Teams come from /api/teams (stored in teams.json); a team member is one
// registered start in the individual discipline the team discipline is based on.

import { escapeHtml, teamRankingCard } from '../js/teamRanking.js';

const API = '/api/teams';
const STORAGE_KEY = 'tmgmt.discipline';
const MAX_SUGGESTIONS = 12;

const state = {
    disciplines: [],     // GET /api/teams/disciplines
    disciplineId: null,  // team discipline shown on the Teams tab
    teams: [],           // teams of that discipline
    candidates: [],      // competitors with eligible starts for that discipline
    editing: null,       // team being edited (null: new team)
    slots: [],           // one per member: { candidate, startId }
    nameEdited: false    // false: the name follows the default
};

// --- helpers ---------------------------------------------------------------

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

function readStoredDiscipline() {
    try { return Number(localStorage.getItem(STORAGE_KEY)) || null; } catch { return null; }
}

function storeDiscipline(id) {
    try { localStorage.setItem(STORAGE_KEY, String(id)); } catch { /* private mode */ }
}

const formatScore = (value) => (value === null || value === undefined ? '-' : Number(value).toLocaleString('en', { maximumFractionDigits: 2 }));
const currentDiscipline = () => state.disciplines.find(d => d.id === state.disciplineId);
const teamSize = () => currentDiscipline()?.team_size ?? 3;

function disciplineOptions(disciplines, selectedId) {
    const option = d => `<option value="${d.id}" ${d.id === selectedId ? 'selected' : ''}>${escapeHtml(d.name)} (${escapeHtml(d.category)}, ${escapeHtml(d.type)})</option>`;
    const active = disciplines.filter(d => d.active);
    const inactive = disciplines.filter(d => !d.active);
    return (active.length ? `<optgroup label="Active">${active.map(option).join('')}</optgroup>` : '')
        + (inactive.length ? `<optgroup label="Inactive">${inactive.map(option).join('')}</optgroup>` : '');
}

// --- navigation ------------------------------------------------------------

const sections = ['teams', 'ranking'];

async function showSection(name) {
    if (!sections.includes(name)) name = 'teams';
    sections.forEach(s => document.getElementById(`${s}-section`).classList.toggle('hidden', s !== name));
    document.querySelectorAll('.nav-link').forEach(link =>
        link.classList.toggle('bg-blue-700', link.getAttribute('href') === `#${name}`));

    try {
        if (!state.disciplines.length) await loadDisciplines();
        if (name === 'teams') await refreshTeams();
        if (name === 'ranking') await refreshRanking();
    } catch (error) {
        console.error(error);
        showMessage(`Could not load data: ${error.message}`);
    }
}

async function loadDisciplines() {
    state.disciplines = await api('/disciplines');
    const stored = readStoredDiscipline();
    const preferred = state.disciplines.find(d => d.id === stored)
        ?? state.disciplines.find(d => d.active)
        ?? state.disciplines[0];
    state.disciplineId = preferred?.id ?? null;
    document.getElementById('discipline-select').innerHTML = disciplineOptions(state.disciplines, state.disciplineId);
}

// --- teams -----------------------------------------------------------------

async function refreshTeams() {
    const discipline = currentDiscipline();
    const info = document.getElementById('discipline-info');
    if (!discipline) {
        info.textContent = 'There are no team disciplines in the catalog.';
        document.getElementById('new-team-btn').disabled = true;
        return;
    }
    const from = discipline.eligible_disciplines.map(d => d.name).join(', ') || 'none';
    info.innerHTML = `Based on <strong>${escapeHtml(discipline.based_on || '-')}</strong> · `
        + `${discipline.team_size} shooters per team · members come from starts in: ${escapeHtml(from)}`
        + (discipline.active ? '' : ' · <span class="text-yellow-700">discipline is not active</span>');

    [state.teams, state.candidates] = await Promise.all([
        api(`?discipline_id=${discipline.id}`),
        api(`/candidates?discipline_id=${discipline.id}`)
    ]);
    renderTeams();
}

function renderTeams() {
    const list = document.getElementById('teams-list');
    document.getElementById('no-teams').classList.toggle('hidden', state.teams.length > 0);
    list.innerHTML = state.teams.map(team => {
        const total = team.members.reduce((sum, m) => sum + (m.score ?? 0), 0);
        const members = team.members.map(m => m.missing
            ? `<li class="text-red-600">${escapeHtml(m.start_id)} · start deleted</li>`
            : `<li class="flex justify-between gap-4">
                   <span><span class="font-mono text-xs text-gray-500">${escapeHtml(m.start_id)}</span>
                   ${escapeHtml(m.name)} <span class="text-gray-500">${escapeHtml([m.club, m.country].filter(Boolean).join(' · '))}</span></span>
                   <span class="${m.has_result ? '' : 'text-gray-400 italic'}">${m.has_result ? formatScore(m.score) : 'no result'}</span>
               </li>`).join('');
        const missingSlots = team.team_size - team.members.length;
        return `
            <div class="bg-white rounded-lg shadow-md p-4" data-team-id="${team.id}">
                <div class="flex flex-wrap justify-between items-start gap-2 mb-2">
                    <div>
                        <h4 class="font-semibold text-gray-900">${escapeHtml(team.name)}</h4>
                        ${team.notes ? `<p class="text-xs text-gray-500">${escapeHtml(team.notes)}</p>` : ''}
                    </div>
                    <div class="flex items-center gap-3 text-sm">
                        <span>Total <strong>${formatScore(total)}</strong></span>
                        ${team.tie_break !== null && team.tie_break !== undefined ? `<span class="text-gray-500">Tie-break ${team.tie_break}</span>` : ''}
                        <button data-action="edit-team" class="text-blue-600 hover:text-blue-900">Edit</button>
                        <button data-action="delete-team" class="text-red-600 hover:text-red-900">Delete</button>
                    </div>
                </div>
                <ul class="text-sm space-y-1">${members}</ul>
                ${missingSlots > 0 ? `<p class="text-xs text-yellow-700 mt-2">${missingSlots} member${missingSlots === 1 ? '' : 's'} missing</p>` : ''}
            </div>`;
    }).join('');
}

async function deleteTeam(team) {
    if (!confirm(`Delete team "${team.name}"?`)) return;
    try {
        await api(`/${team.id}?version=${team.version ?? 0}`, 'DELETE');
        showMessage(`Team "${team.name}" deleted`, 'success');
        if (state.editing?.id === team.id) closeEditor();
    } catch (error) {
        console.error(error);
        showMessage(error.status === 409
            ? 'Someone else changed this team in the meantime. The latest data is now loaded - please check and delete again if needed.'
            : error.message);
    }
    await refreshTeams();
}

// --- editor ----------------------------------------------------------------

function openEditor(team) {
    state.editing = team ?? null;
    state.slots = Array.from({ length: teamSize() }, () => ({ candidate: null, startId: null }));
    const dropped = [];
    (team?.members ?? []).forEach((member, i) => {
        const candidate = state.candidates.find(c => c.competitor_id === member.competitor_id);
        if (member.missing || !candidate || i >= state.slots.length) {
            dropped.push(member.start_id);
            return;
        }
        state.slots[i] = { candidate, startId: member.start_id };
    });
    if (dropped.length) {
        showMessage(`Start${dropped.length === 1 ? '' : 's'} ${dropped.join(', ')} no longer exist${dropped.length === 1 ? 's' : ''} and ${dropped.length === 1 ? 'was' : 'were'} removed from the team. Save to keep the change.`);
    }

    document.getElementById('team-form-title').textContent = team ? `Edit Team "${team.name}"` : `New Team – ${currentDiscipline().name}`;
    state.nameEdited = Boolean(team) && team.name !== team.default_name;
    document.getElementById('team-name').value = team ? team.name : '';
    document.getElementById('team-tie-break').value = team?.tie_break ?? '';
    document.getElementById('team-notes').value = team?.notes ?? '';

    renderSlots();
    updateDefaultName();
    const form = document.getElementById('team-form');
    form.classList.remove('hidden');
    form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    form.querySelector('[data-slot-search]:not([disabled])')?.focus();
}

function closeEditor() {
    state.editing = null;
    state.slots = [];
    document.getElementById('team-form').classList.add('hidden');
}

function competitorLabel(candidate) {
    const details = [candidate.club, candidate.country].filter(Boolean).join(' · ');
    return `${candidate.name} (#${candidate.competitor_id})${details ? ` – ${details}` : ''}`;
}

function startOptionLabel(start) {
    const result = start.has_result ? `${formatScore(start.score)} pts` : 'no result yet';
    return `${start.start_id} · ${start.discipline_name} · ${result}`;
}

function renderSlots() {
    document.getElementById('member-slots').innerHTML = state.slots.map((slot, i) => {
        const candidate = slot.candidate;
        return `
            <div class="border border-gray-200 rounded-md p-3 relative" data-slot="${i}">
                <label class="block text-sm font-medium text-gray-700 mb-1">Shooter ${i + 1}</label>
                ${candidate ? `
                    <div class="flex justify-between items-start gap-2 mb-2">
                        <div class="text-sm">
                            <div class="font-medium">${escapeHtml(candidate.name)} <span class="text-gray-500">#${candidate.competitor_id}</span></div>
                            <div class="text-gray-500">${escapeHtml([candidate.club, candidate.country].filter(Boolean).join(' · ') || '–')}</div>
                        </div>
                        <button type="button" data-action="clear-slot" class="text-gray-400 hover:text-red-600" title="Remove shooter">&times;</button>
                    </div>
                    <label class="block text-xs text-gray-600 mb-1">Start (counts for the team)</label>
                    <select data-slot-start class="w-full px-2 py-1 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" ${candidate.starts.length < 2 ? 'disabled' : ''}>
                        ${candidate.starts.map(s => `<option value="${escapeHtml(s.start_id)}" ${s.start_id === slot.startId ? 'selected' : ''}>${escapeHtml(startOptionLabel(s))}</option>`).join('')}
                    </select>
                ` : `
                    <input type="search" data-slot-search placeholder="Start id, competitor id or name" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">
                    <ul data-slot-suggestions class="absolute left-3 right-3 z-10 mt-1 bg-white border border-gray-200 rounded-md shadow-lg max-h-72 overflow-y-auto hidden"></ul>
                `}
            </div>`;
    }).join('');
}

// Competitors matching the query: a start id (prefix), the competitor id (exact) or the name
function searchCandidates(query) {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    return state.candidates.filter(c =>
        String(c.competitor_id) === q
        || String(c.name ?? '').toLowerCase().includes(q)
        || c.starts.some(s => s.start_id.toLowerCase().startsWith(q))
    ).slice(0, MAX_SUGGESTIONS);
}

// Why a candidate cannot be picked for slot i, or null
function blockedReason(candidate, slotIndex) {
    if (state.slots.some((s, i) => i !== slotIndex && s.candidate?.competitor_id === candidate.competitor_id)) {
        return 'already in this team';
    }
    if (candidate.team_id !== null && candidate.team_id !== undefined && candidate.team_id !== state.editing?.id) {
        return `in team ${candidate.team_name}`;
    }
    return null;
}

function renderSuggestions(slotEl, slotIndex, query) {
    const list = slotEl.querySelector('[data-slot-suggestions]');
    const matches = searchCandidates(query);
    if (!query.trim()) {
        list.classList.add('hidden');
        return;
    }
    list.innerHTML = matches.length ? matches.map(c => {
        const reason = blockedReason(c, slotIndex);
        const starts = c.starts.map(s => s.start_id).join(', ');
        return `
            <li data-competitor-id="${c.competitor_id}" class="px-3 py-2 text-sm ${reason ? 'text-gray-400 cursor-not-allowed' : 'cursor-pointer hover:bg-blue-50'}" ${reason ? 'data-blocked="true"' : ''}>
                <div class="font-medium">${escapeHtml(competitorLabel(c))}</div>
                <div class="text-xs ${reason ? '' : 'text-gray-500'}">${escapeHtml(starts)}${reason ? ` · ${escapeHtml(reason)}` : ''}</div>
            </li>`;
    }).join('') : '<li class="px-3 py-2 text-sm text-gray-500">No matching starter with a start in this discipline</li>';
    list.classList.remove('hidden');
}

function pickCandidate(slotIndex, competitorId, query = '') {
    const candidate = state.candidates.find(c => c.competitor_id === competitorId);
    if (!candidate || blockedReason(candidate, slotIndex)) return;
    // A typed start id picks that start; otherwise the first start is the default
    const q = query.trim().toLowerCase();
    const typed = candidate.starts.find(s => s.start_id.toLowerCase() === q);
    state.slots[slotIndex] = { candidate, startId: (typed ?? candidate.starts[0]).start_id };
    renderSlots();
    updateDefaultName();
    document.querySelector('#member-slots [data-slot-search]')?.focus();
}

function sharedValue(field) {
    const values = state.slots.map(s => s.candidate).filter(Boolean).map(m => String(m[field] ?? '').trim());
    return values.length && values.every(v => v && v.toLowerCase() === values[0].toLowerCase()) ? values[0] : null;
}

// The club all members share, else their shared country (same rule as the server)
function defaultName() {
    return sharedValue('club') ?? sharedValue('country') ?? (state.editing ? `Team ${state.editing.id}` : '');
}

function updateDefaultName() {
    const suggestion = defaultName();
    const nameInput = document.getElementById('team-name');
    if (!state.nameEdited) nameInput.value = suggestion;
    nameInput.placeholder = suggestion || 'Team <number> (no shared club or country)';
    const source = sharedValue('club') ? 'shared club' : sharedValue('country') ? 'shared country' : null;
    document.getElementById('team-name-hint').textContent = source
        ? `Default: ${suggestion} (${source})`
        : 'Default: shared club, else shared country, else "Team <number>"';
    document.getElementById('reset-name-btn').classList.toggle('hidden', !state.nameEdited);
}

async function saveTeam(event) {
    event.preventDefault();
    const tieBreakText = document.getElementById('team-tie-break').value.trim();
    const body = {
        discipline_id: state.disciplineId,
        name: document.getElementById('team-name').value.trim(),
        members: state.slots.filter(s => s.startId).map(s => s.startId),
        tie_break: tieBreakText === '' ? null : Number(tieBreakText),
        notes: document.getElementById('team-notes').value.trim()
    };
    try {
        let saved;
        if (state.editing) {
            body.version = state.editing.version ?? 0;
            saved = await api(`/${state.editing.id}`, 'PUT', body);
        } else {
            saved = await api('', 'POST', body);
        }
        showMessage(`Team "${saved.name}" saved`, 'success');
        closeEditor();
        await refreshTeams();
    } catch (error) {
        console.error(error);
        if (error.status === 409 && error.body?.current) {
            showMessage('Someone else changed this team in the meantime. The latest values are now loaded - please make your change again.');
            await refreshTeams();
            openEditor(state.teams.find(t => t.id === error.body.current.id));
        } else if (error.status === 404) {
            showMessage('This team was deleted in the meantime.');
            closeEditor();
            await refreshTeams();
        } else {
            showMessage(error.message);
            // Candidates may be outdated (e.g. someone joined another team meanwhile)
            state.candidates = await api(`/candidates?discipline_id=${state.disciplineId}`).catch(() => state.candidates);
        }
    }
}

// --- ranking ---------------------------------------------------------------

async function refreshRanking() {
    const select = document.getElementById('ranking-select');
    const selected = select.value;
    select.innerHTML = '<option value="">All team disciplines with teams</option>' + disciplineOptions(state.disciplines, Number(selected) || null);
    select.value = selected;

    const content = document.getElementById('ranking-content');
    const ids = selected ? [Number(selected)] : state.disciplines.map(d => d.id);
    const rankings = await Promise.all(ids.map(id => api(`/ranking/${id}`)));
    const shown = selected ? rankings : rankings.filter(r => r.rankings.length > 0);
    content.innerHTML = shown.length
        ? shown.map(r => teamRankingCard(r, { extraClass: 'print-break' })).join('')
        : '<div class="text-center py-12 text-gray-500">No teams entered yet.</div>';
}

// --- wiring ----------------------------------------------------------------

function setupEventListeners() {
    document.getElementById('discipline-select').addEventListener('change', async (event) => {
        state.disciplineId = Number(event.target.value);
        storeDiscipline(state.disciplineId);
        closeEditor();
        await refreshTeams().catch(error => showMessage(error.message));
    });
    document.getElementById('new-team-btn').addEventListener('click', () => openEditor(null));
    document.getElementById('close-team-form-btn').addEventListener('click', closeEditor);
    document.getElementById('cancel-team-btn').addEventListener('click', closeEditor);
    document.getElementById('team-form').addEventListener('submit', saveTeam);

    document.getElementById('team-name').addEventListener('input', (event) => {
        state.nameEdited = event.target.value.trim() !== '' && event.target.value.trim() !== defaultName();
        document.getElementById('reset-name-btn').classList.toggle('hidden', !state.nameEdited);
    });
    document.getElementById('reset-name-btn').addEventListener('click', () => {
        state.nameEdited = false;
        updateDefaultName();
    });

    const slots = document.getElementById('member-slots');
    slots.addEventListener('input', (event) => {
        if (!event.target.matches('[data-slot-search]')) return;
        const slotEl = event.target.closest('[data-slot]');
        renderSuggestions(slotEl, Number(slotEl.dataset.slot), event.target.value);
    });
    slots.addEventListener('keydown', (event) => {
        if (!event.target.matches('[data-slot-search]')) return;
        const slotEl = event.target.closest('[data-slot]');
        if (event.key === 'Enter') {
            // Enter picks the first pickable suggestion instead of submitting the form
            event.preventDefault();
            const first = slotEl.querySelector('[data-competitor-id]:not([data-blocked])');
            if (first) pickCandidate(Number(slotEl.dataset.slot), Number(first.dataset.competitorId), event.target.value);
        } else if (event.key === 'Escape') {
            slotEl.querySelector('[data-slot-suggestions]').classList.add('hidden');
        }
    });
    // mousedown so the pick happens before the input loses focus
    slots.addEventListener('mousedown', (event) => {
        const item = event.target.closest('[data-competitor-id]');
        if (!item || item.dataset.blocked) return;
        event.preventDefault();
        const slotEl = item.closest('[data-slot]');
        pickCandidate(Number(slotEl.dataset.slot), Number(item.dataset.competitorId), slotEl.querySelector('[data-slot-search]').value);
    });
    slots.addEventListener('focusout', (event) => {
        if (!event.target.matches('[data-slot-search]')) return;
        event.target.closest('[data-slot]').querySelector('[data-slot-suggestions]')?.classList.add('hidden');
    });
    slots.addEventListener('change', (event) => {
        if (!event.target.matches('[data-slot-start]')) return;
        state.slots[Number(event.target.closest('[data-slot]').dataset.slot)].startId = event.target.value;
    });
    slots.addEventListener('click', (event) => {
        if (!event.target.closest('[data-action="clear-slot"]')) return;
        const index = Number(event.target.closest('[data-slot]').dataset.slot);
        state.slots[index] = { candidate: null, startId: null };
        renderSlots();
        updateDefaultName();
        slots.querySelector(`[data-slot="${index}"] [data-slot-search]`)?.focus();
    });

    document.getElementById('teams-list').addEventListener('click', (event) => {
        const button = event.target.closest('[data-action]');
        if (!button) return;
        const team = state.teams.find(t => t.id === Number(button.closest('[data-team-id]').dataset.teamId));
        if (!team) return;
        if (button.dataset.action === 'edit-team') openEditor(team);
        if (button.dataset.action === 'delete-team') deleteTeam(team);
    });

    document.getElementById('ranking-select').addEventListener('change', () =>
        refreshRanking().catch(error => showMessage(error.message)));
    document.getElementById('print-ranking-btn').addEventListener('click', () => window.print());

    window.addEventListener('hashchange', () => showSection(location.hash.substring(1)));
}

setupEventListeners();
showSection(location.hash.substring(1));
