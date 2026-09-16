// Relay management page: schedule builder, lane assignment grid and competitor overview.
// All relay data comes from /api/rmgmt (stored in relays.json); a lane holds one
// registered start (e.g. "1-52-1") from data.json.

const API = '/api/rmgmt';

const state = {
    data: null,          // GET /api/rmgmt: config, ranges, disciplines, days, relays, assignments
    relayId: null,       // relay shown in the assignment grid
    overview: null
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

// Runs a change; on failure shows the reason and reloads, since a 404/409
// usually means someone else changed the data meanwhile
async function perform(action, reload) {
    try {
        await action();
    } catch (error) {
        console.error(error);
        showMessage(error.message);
    }
    await reload();
}

function addMinutes(time, minutes) {
    if (!time) return '';
    const [h, m] = time.split(':').map(Number);
    const total = (h * 60 + m + minutes) % (24 * 60);
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

function formatDate(isoDate) {
    if (!isoDate) return '?';
    const date = new Date(`${isoDate}T00:00:00`);
    return isNaN(date) ? isoDate : date.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: '2-digit', day: '2-digit' });
}

const duration = () => state.data.config.relay_duration_min;
const dayById = (id) => state.data.days.find(d => d.id === id);

// Relays in schedule order (days are kept sorted by the server)
function orderedRelays() {
    const dayOrder = new Map(state.data.days.map((d, i) => [d.id, i]));
    return [...state.data.relays].sort((a, b) =>
        (dayOrder.get(a.day_id) ?? 0) - (dayOrder.get(b.day_id) ?? 0) || a.sequence_no - b.sequence_no);
}

function relayLabel(relay) {
    const day = dayById(relay.day_id);
    return `${formatDate(day?.date)} · Relay ${relay.sequence_no} · ${relay.start_time}–${addMinutes(relay.start_time, duration())}`;
}

// "Anna Muster (#1) – No 7 Colt (original) · 1-52-1"
function startLabel(entry) {
    const competitor = entry.competitor;
    const who = competitor ? `${competitor.name} (#${competitor.id})` : 'Unknown competitor';
    return `${who} – ${entry.discipline_name ?? '?'} · ${entry.start_id}`;
}

async function loadData() {
    state.data = await api('');
}

// --- navigation ------------------------------------------------------------

const sections = ['schedule', 'assignment', 'overview'];

async function showSection(name) {
    if (!sections.includes(name)) name = 'schedule';
    sections.forEach(s => document.getElementById(`${s}-section`).classList.toggle('hidden', s !== name));
    document.querySelectorAll('.nav-link').forEach(link =>
        link.classList.toggle('bg-blue-700', link.getAttribute('href') === `#${name}`));

    try {
        if (name === 'schedule') await refreshSchedule();
        if (name === 'assignment') await refreshAssignment();
        if (name === 'overview') await refreshOverview();
    } catch (error) {
        console.error(error);
        showMessage(`Could not load data: ${error.message}`);
    }
}

// --- schedule --------------------------------------------------------------

async function refreshSchedule() {
    await loadData();
    renderSchedule();
}

function renderSchedule() {
    const { config, ranges, disciplines, days, assignments } = state.data;
    document.getElementById('relay-duration').value = config.relay_duration_min;
    document.getElementById('range-summary').textContent =
        'Lanes per relay: ' + ranges.map(r => `${r.name} × ${r.lane_count}`).join(', ');

    document.getElementById('discipline-ranges-list').innerHTML = disciplines.length
        ? disciplines.map(discipline => `
            <label class="flex items-center justify-between gap-2 text-sm border border-gray-200 rounded-md px-3 py-2">
                <span class="truncate" title="${escapeHtml(discipline.name)}">${escapeHtml(discipline.name)}</span>
                <select class="discipline-range px-2 py-1 border border-gray-300 rounded-md text-sm" data-discipline-id="${discipline.id}">
                    <option value="">any range</option>
                    ${ranges.map(r => `<option value="${escapeHtml(r.id)}" ${r.id === discipline.range_id ? 'selected' : ''}>${escapeHtml(r.name)}</option>`).join('')}
                </select>
            </label>`).join('')
        : '<p class="text-sm text-gray-500">No disciplines yet. Activate disciplines and register starts in the competition management first.</p>';

    const filled = new Map();
    assignments.forEach(a => {
        const key = `${a.relay_id}/${a.range_id}`;
        filled.set(key, (filled.get(key) || 0) + 1);
    });

    document.getElementById('no-days').classList.toggle('hidden', days.length > 0);
    document.getElementById('days-list').innerHTML = days.map(day => {
        const relays = state.data.relays.filter(r => r.day_id === day.id).sort((a, b) => a.sequence_no - b.sequence_no);
        const lastEnd = relays.length ? addMinutes(relays[relays.length - 1].start_time, config.relay_duration_min) : null;
        return `
        <div class="bg-white rounded-lg shadow-md p-6" data-day-id="${escapeHtml(day.id)}">
            <div class="flex flex-wrap justify-between items-center gap-3 mb-4">
                <div>
                    <h3 class="text-lg font-semibold">${escapeHtml(formatDate(day.date))}</h3>
                    <p class="text-sm text-gray-600">Start ${escapeHtml(day.start_time)} · ${relays.length} relay${relays.length === 1 ? '' : 's'}${lastEnd ? ` · ends ${lastEnd}` : ''}</p>
                </div>
                <div class="flex flex-wrap items-center gap-2 no-print">
                    <input type="date" class="day-edit-date px-2 py-1 border border-gray-300 rounded-md text-sm" value="${escapeHtml(day.date)}" aria-label="Date">
                    <input type="time" class="day-edit-start px-2 py-1 border border-gray-300 rounded-md text-sm" value="${escapeHtml(day.start_time)}" aria-label="Start time">
                    <button class="day-save-btn px-3 py-1 border border-gray-300 rounded-md text-sm hover:bg-gray-50">Update</button>
                    <button class="day-delete-btn px-3 py-1 text-sm text-red-600 border border-red-200 rounded-md hover:bg-red-50">Delete day</button>
                </div>
            </div>
            <div class="overflow-x-auto">
                <table class="min-w-full divide-y divide-gray-200">
                    <thead class="bg-gray-50">
                        <tr>
                            <th class="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Relay</th>
                            <th class="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Time</th>
                            ${ranges.map(r => `<th class="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">${escapeHtml(r.name)}</th>`).join('')}
                            <th class="px-4 py-2 text-left text-xs font-medium text-gray-500 uppercase tracking-wider no-print">Actions</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-200">
                        ${relays.map(relay => `
                        <tr data-relay-id="${escapeHtml(relay.id)}">
                            <td class="px-4 py-2 text-sm font-medium">${relay.sequence_no}</td>
                            <td class="px-4 py-2 text-sm">${escapeHtml(relay.start_time)}–${addMinutes(relay.start_time, config.relay_duration_min)}</td>
                            ${ranges.map(r => {
                                const n = filled.get(`${relay.id}/${r.id}`) || 0;
                                return `<td class="px-4 py-2 text-sm ${n === r.lane_count ? 'text-green-700 font-medium' : 'text-gray-600'}">${n} / ${r.lane_count}</td>`;
                            }).join('')}
                            <td class="px-4 py-2 text-sm whitespace-nowrap no-print">
                                <button class="relay-open-btn text-blue-600 hover:text-blue-800 mr-3">Assign lanes</button>
                                <button class="relay-delete-btn text-red-600 hover:text-red-800">Delete</button>
                            </td>
                        </tr>`).join('')}
                        ${relays.length === 0 ? `<tr><td colspan="${3 + ranges.length}" class="px-4 py-4 text-sm text-gray-500 text-center">No relays on this day yet.</td></tr>` : ''}
                    </tbody>
                </table>
            </div>
            <div class="flex items-center gap-2 mt-4 no-print">
                <input type="number" class="relay-count px-2 py-1 border border-gray-300 rounded-md text-sm w-20" min="1" max="100" value="1" aria-label="Number of relays">
                <button class="relay-add-btn bg-green-600 text-white px-3 py-1 rounded-md text-sm hover:bg-green-700">Add relays</button>
            </div>
        </div>`;
    }).join('');
}

function setupScheduleListeners() {
    document.getElementById('config-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const minutes = parseInt(document.getElementById('relay-duration').value, 10);
        perform(async () => {
            await api('/config', 'PUT', { relay_duration_min: minutes });
            showMessage('Relay duration saved, start times recalculated', 'success');
        }, refreshSchedule);
    });

    document.getElementById('discipline-ranges-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const mapping = {};
        document.querySelectorAll('.discipline-range').forEach(select => {
            mapping[select.dataset.disciplineId] = select.value || null;
        });
        perform(async () => {
            await api('/discipline-ranges', 'PUT', { discipline_ranges: mapping });
            showMessage('Ranges saved', 'success');
        }, refreshSchedule);
    });

    document.getElementById('day-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const date = document.getElementById('day-date').value;
        const start = document.getElementById('day-start').value;
        perform(() => api('/days', 'POST', { date, start_time: start }), refreshSchedule);
    });

    document.getElementById('days-list').addEventListener('click', (event) => {
        const button = event.target.closest('button');
        const dayEl = event.target.closest('[data-day-id]');
        if (!button || !dayEl) return;
        const dayId = dayEl.dataset.dayId;
        const relayId = event.target.closest('[data-relay-id]')?.dataset.relayId;

        if (button.classList.contains('day-save-btn')) {
            const date = dayEl.querySelector('.day-edit-date').value;
            const start = dayEl.querySelector('.day-edit-start').value;
            perform(() => api(`/days/${encodeURIComponent(dayId)}`, 'PUT', { date, start_time: start }), refreshSchedule);
        } else if (button.classList.contains('day-delete-btn')) {
            if (!confirm('Delete this day with all its relays and lane assignments?')) return;
            perform(() => api(`/days/${encodeURIComponent(dayId)}`, 'DELETE'), refreshSchedule);
        } else if (button.classList.contains('relay-add-btn')) {
            const count = parseInt(dayEl.querySelector('.relay-count').value, 10) || 1;
            perform(() => api(`/days/${encodeURIComponent(dayId)}/relays`, 'POST', { count }), refreshSchedule);
        } else if (button.classList.contains('relay-delete-btn') && relayId) {
            if (!confirm('Delete this relay and its lane assignments? Later relays of the day move up.')) return;
            perform(() => api(`/relays/${encodeURIComponent(relayId)}`, 'DELETE'), refreshSchedule);
        } else if (button.classList.contains('relay-open-btn') && relayId) {
            state.relayId = relayId;
            location.hash = '#assignment';
        }
    });
}

// --- lane assignment -------------------------------------------------------

async function refreshAssignment() {
    await loadData();
    const relays = orderedRelays();
    document.getElementById('no-relays').classList.toggle('hidden', relays.length > 0);

    const select = document.getElementById('relay-select');
    select.innerHTML = relays.map(r => `<option value="${escapeHtml(r.id)}">${escapeHtml(relayLabel(r))}</option>`).join('');

    if (!relays.some(r => r.id === state.relayId)) {
        state.relayId = relays[0]?.id ?? null;
    }
    if (!state.relayId) {
        document.getElementById('relay-detail').innerHTML = '';
        return;
    }
    select.value = state.relayId;
    await renderRelay();
}

async function renderRelay() {
    const relayId = state.relayId;
    const [relay, ...available] = await Promise.all([
        api(`/relays/${encodeURIComponent(relayId)}`),
        ...state.data.ranges.map(r =>
            api(`/relays/${encodeURIComponent(relayId)}/available-starts?range_id=${encodeURIComponent(r.id)}`))
    ]);
    if (relayId !== state.relayId) return; // user switched relay meanwhile

    const availableByRange = new Map(state.data.ranges.map((r, i) => [r.id, available[i]]));
    const end = addMinutes(relay.start_time, relay.relay_duration_min);

    document.getElementById('relay-detail').innerHTML = `
        <h2 class="text-2xl font-bold text-gray-900 mb-4">
            Relay ${relay.sequence_no}
            <span class="text-base font-normal text-gray-600">${escapeHtml(formatDate(relay.day?.date))} · ${escapeHtml(relay.start_time)}–${end}</span>
        </h2>
        <div class="relay-grid grid grid-cols-1 lg:grid-cols-3 gap-6 items-start">
            ${relay.ranges.map(block => laneBlock(block, availableByRange.get(block.id) || [])).join('')}
        </div>`;
}

function laneBlock(block, available) {
    const taken = block.lanes.filter(l => l.assignment).length;
    const optionsFor = (assignment) => {
        const current = assignment
            ? `<option value="${escapeHtml(assignment.start_id)}" selected>${escapeHtml(startLabel(assignment))}</option>`
            : '';
        return `<option value="">— empty —</option>${current}` + available
            .map(start => `<option value="${escapeHtml(start.start_id)}">${escapeHtml(startLabel(start))}</option>`)
            .join('');
    };

    return `
        <div class="bg-white rounded-lg shadow-md overflow-hidden">
            <div class="px-4 py-3 bg-gray-50 border-b border-gray-200 flex justify-between items-center">
                <h3 class="text-lg font-semibold">${escapeHtml(block.name)}</h3>
                <span class="text-sm text-gray-600">${taken} / ${block.lane_count} lanes</span>
            </div>
            <table class="min-w-full divide-y divide-gray-100">
                <tbody>
                    ${block.lanes.map(lane => `
                    <tr data-range-id="${escapeHtml(block.id)}" data-lane-no="${lane.lane_no}"
                        data-assignment-id="${escapeHtml(lane.assignment?.id ?? '')}">
                        <td class="px-3 py-1 text-sm font-medium text-gray-500 w-12 text-right">${lane.lane_no}</td>
                        <td class="px-3 py-1">
                            <select class="lane-select w-full px-2 py-1 border rounded-md text-sm ${lane.assignment ? 'border-blue-300 bg-blue-50' : 'border-gray-300'}">
                                ${optionsFor(lane.assignment)}
                            </select>
                            <span class="print-only text-sm">${lane.assignment ? escapeHtml(startLabel(lane.assignment)) : ''}</span>
                        </td>
                    </tr>`).join('')}
                </tbody>
            </table>
        </div>`;
}

function setupAssignmentListeners() {
    const select = document.getElementById('relay-select');
    select.addEventListener('change', () => {
        state.relayId = select.value;
        perform(async () => {}, renderRelay);
    });

    const step = (delta) => {
        const relays = orderedRelays();
        const index = relays.findIndex(r => r.id === state.relayId);
        const next = relays[index + delta];
        if (!next) return;
        state.relayId = next.id;
        select.value = next.id;
        perform(async () => {}, renderRelay);
    };
    document.getElementById('prev-relay-btn').addEventListener('click', () => step(-1));
    document.getElementById('next-relay-btn').addEventListener('click', () => step(1));
    document.getElementById('print-relay-btn').addEventListener('click', () => window.print());

    document.getElementById('relay-detail').addEventListener('change', (event) => {
        if (!event.target.classList.contains('lane-select')) return;
        const row = event.target.closest('tr');
        const assignmentId = row.dataset.assignmentId || null;
        const startId = event.target.value;
        event.target.disabled = true;

        perform(async () => {
            if (startId) {
                await api('/assignments', 'POST', {
                    relay_id: state.relayId,
                    range_id: row.dataset.rangeId,
                    lane_no: parseInt(row.dataset.laneNo, 10),
                    start_id: startId,
                    // the assignment this user saw, so a lane changed by someone else is not overwritten
                    expected_assignment_id: assignmentId
                });
            } else if (assignmentId) {
                await api(`/assignments/${encodeURIComponent(assignmentId)}`, 'DELETE');
            }
        }, async () => {
            await loadData();
            await renderRelay();
        });
    });
}

// --- competitor overview ---------------------------------------------------

async function refreshOverview() {
    await loadData();
    state.overview = await api('/overview');
    renderOverview();
}

function renderOverview() {
    const { rows, data_issues: dataIssues } = state.overview;
    const filter = document.getElementById('overview-filter').value.trim().toLowerCase();
    const issuesOnly = document.getElementById('overview-issues-only').checked;
    const multipleDays = state.data.days.length > 1;

    const problemCount = rows.filter(r => r.issues.length).length + dataIssues.length;
    document.getElementById('overview-issues').innerHTML = problemCount
        ? `<div class="mb-4 px-4 py-3 rounded-md bg-red-100 text-red-800 border border-red-200">
               ${problemCount} problem${problemCount === 1 ? '' : 's'} found in the stored relay data.
               ${dataIssues.map(i => `<div class="text-sm">${escapeHtml(i)}</div>`).join('')}
           </div>`
        : '';

    const th = (text) => `<th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">${escapeHtml(text)}</th>`;
    document.getElementById('overview-head').innerHTML =
        `<tr>${th('Competitor')}${th('Club')}${th('Scheduled starts')}${th('Not yet scheduled')}${th('Problems')}</tr>`;

    const visible = rows.filter(row => {
        if (issuesOnly && row.issues.length === 0) return false;
        if (!filter) return true;
        const haystack = [`${row.competitor.name} ${row.competitor.club ?? ''} #${row.competitor.id}`,
            ...row.scheduled.map(e => `${e.discipline_name} ${e.start_id}`),
            ...row.unscheduled.map(e => `${e.discipline_name} ${e.start_id}`)].join(' ').toLowerCase();
        return haystack.includes(filter);
    });

    const scheduledLine = (entry) => {
        const when = `${multipleDays ? `${escapeHtml(formatDate(entry.date))}, ` : ''}${escapeHtml(entry.start_time ?? '?')}`;
        return `<div>
            <span class="text-gray-900">${escapeHtml(entry.discipline_name ?? '?')}</span>
            <span class="text-gray-400">${escapeHtml(entry.start_id)}</span> →
            <a href="#assignment" class="overview-relay-link text-blue-600 hover:underline" data-relay-id="${escapeHtml(entry.relay_id)}">Relay ${entry.sequence_no ?? '?'}</a>
            · ${escapeHtml(entry.range_name ?? '?')} lane ${entry.lane_no} <span class="text-gray-500">(${when})</span>
        </div>`;
    };

    document.getElementById('overview-body').innerHTML = visible.map(row => `
        <tr class="${row.issues.length ? 'bg-red-50' : ''}">
            <td class="px-4 py-2 text-sm font-medium whitespace-nowrap">${escapeHtml(row.competitor.name)} <span class="text-gray-400">#${row.competitor.id}</span></td>
            <td class="px-4 py-2 text-sm text-gray-600">${escapeHtml(row.competitor.club ?? '')}</td>
            <td class="px-4 py-2 text-sm">${row.scheduled.length ? row.scheduled.map(scheduledLine).join('') : '<span class="text-gray-300">–</span>'}</td>
            <td class="px-4 py-2 text-sm text-amber-700">${row.unscheduled.length
                ? row.unscheduled.map(s => `<div>${escapeHtml(s.discipline_name ?? '?')} <span class="text-gray-400">${escapeHtml(s.start_id)}</span></div>`).join('')
                : '<span class="text-gray-300">–</span>'}</td>
            <td class="px-4 py-2 text-sm text-red-700">${row.issues.map(i => `<div>${escapeHtml(i)}</div>`).join('')}</td>
        </tr>`).join('')
        || '<tr><td colspan="5" class="px-4 py-6 text-center text-sm text-gray-500">No competitors to show.</td></tr>';
}

function setupOverviewListeners() {
    document.getElementById('overview-filter').addEventListener('input', () => state.overview && renderOverview());
    document.getElementById('overview-issues-only').addEventListener('change', () => state.overview && renderOverview());
    document.getElementById('overview-body').addEventListener('click', (event) => {
        const link = event.target.closest('.overview-relay-link');
        if (link) state.relayId = link.dataset.relayId; // hash change then opens the grid
    });
}

// --- init ------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', () => {
    setupScheduleListeners();
    setupAssignmentListeners();
    setupOverviewListeners();
    window.addEventListener('hashchange', () => showSection(location.hash.substring(1)));
    showSection(location.hash.substring(1));
});
