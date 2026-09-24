// Ranking page: one result per competitor and discipline (the best one),
// from /api/ranking/best, laid out for printing. Team disciplines show the
// team ranking, which already has one total per team.

import { escapeHtml, disciplineDisplayName, formatScore, rankBadge, teamRankingCard } from '../js/teamRanking.js';

const DISCIPLINE_KEY = 'ranking.discipline';
const PAGE_BREAK_KEY = 'ranking.pagePerDiscipline';
const RINGS = ['10', '9', '8', '7'];

// --- helpers ---------------------------------------------------------------

async function api(path) {
    const response = await fetch(`/api${path}`);
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new Error(json?.error || `HTTP error! status: ${response.status}`);
    return json;
}

let messageTimer;
function showMessage(text) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = 'mx-4 mb-4 px-4 py-3 rounded-md no-print bg-red-100 text-red-800 border border-red-200';
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), 8000);
}

function readStored(key) {
    try { return localStorage.getItem(key); } catch { return null; }
}

function store(key, value) {
    try { localStorage.setItem(key, value); } catch { /* private mode: not remembered */ }
}

// --- rendering -------------------------------------------------------------

function individualCard(data, extraClass) {
    const rows = data.rankings || [];
    const discipline = data.discipline;
    const hasNotes = rows.some(row => row.notes);
    const th = (label, align = 'left', title = '') =>
        `<th class="px-4 py-3 text-${align} text-xs font-medium text-gray-500 uppercase tracking-wider"${title ? ` title="${title}"` : ''}>${label}</th>`;

    return `
        <div class="bg-white rounded-lg shadow-md ${extraClass}">
            <div class="px-6 py-4 border-b border-gray-200">
                <h3 class="text-lg font-semibold text-gray-900">
                    ${escapeHtml(disciplineDisplayName(discipline.name, discipline.type))}
                    <span class="text-sm text-gray-500 ml-2">${escapeHtml(discipline.category)}</span>
                </h3>
            </div>
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                ${th('Rank')}
                                ${th('Start')}
                                ${th('Name')}
                                ${th('Club')}
                                ${th('Country')}
                                ${th('Result', 'center')}
                                ${RINGS.map(ring => th(`${ring}s`, 'center')).join('')}
                                ${th('Tie-break', 'center', 'Distance of the furthest shot; the lower value wins')}
                                ${hasNotes ? th('Notes') : ''}
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${rows.map(row => `
                                <tr class="hover:bg-gray-50 ${row.rank <= 3 ? 'font-bold' : ''}">
                                    <td class="px-4 py-3 whitespace-nowrap">${rankBadge(row.rank)}</td>
                                    <td class="px-4 py-3 whitespace-nowrap font-mono text-xs text-gray-500">${escapeHtml(row.start_id)}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-sm text-gray-900">${escapeHtml(row.competitor.name)}</td>
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(row.competitor.club || '')}</td>
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(row.competitor.country || '')}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm text-gray-900">${formatScore(row.score)}</td>
                                    ${RINGS.map(ring => `<td class="px-4 py-3 text-center text-sm font-normal">${row.freq_counts?.[ring] ?? 0}</td>`).join('')}
                                    <td class="px-4 py-3 text-center text-sm font-normal">${row.override_value ?? '-'}</td>
                                    ${hasNotes ? `<td class="px-4 py-3 text-sm font-normal text-gray-500">${escapeHtml(row.notes || '')}</td>` : ''}
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}

function card(data, extraClass) {
    return data.kind === 'team' ? teamRankingCard(data, { extraClass }) : individualCard(data, extraClass);
}

function emptyState(text) {
    return `
        <div class="text-center py-12">
            <h3 class="text-lg font-medium text-gray-900 mb-2">No ranking data available</h3>
            <p class="text-gray-500">${text}</p>
        </div>`;
}

function render(rankings, emptyText) {
    const pagePerDiscipline = document.getElementById('page-per-discipline').checked;
    const content = document.getElementById('ranking-content');
    const withData = rankings.filter(data => data.kind === 'team' || data.rankings?.length);

    if (withData.length === 0) {
        content.innerHTML = emptyState(emptyText);
        return;
    }
    content.innerHTML = withData
        .map((data, i) => card(data, pagePerDiscipline && i < withData.length - 1 ? 'print-break' : 'keep-together'))
        .join('');
}

// --- loading ---------------------------------------------------------------

async function loadDisciplines() {
    const [active, available] = await Promise.all([api('/active-disciplines'), api('/available-disciplines')]);
    const select = document.getElementById('discipline-select');
    select.innerHTML = '<option value="">All disciplines</option>' + (active || [])
        .map(id => available.find(d => d.id === id))
        .filter(Boolean)
        .map(d => `<option value="${d.id}">${escapeHtml(disciplineDisplayName(d.event, d.type))}</option>`)
        .join('');

    const stored = readStored(DISCIPLINE_KEY);
    select.value = [...select.options].some(o => o.value === stored) ? stored : '';
}

async function loadRanking() {
    const disciplineId = document.getElementById('discipline-select').value;
    try {
        if (disciplineId) {
            render([await api(`/ranking/best/${disciplineId}`)], 'There are no results for this discipline yet.');
        } else {
            render(Object.values(await api('/ranking/best')), 'There are no active disciplines with results to display.');
        }
    } catch (error) {
        showMessage(`Error loading ranking: ${error.message}`);
    }
}

// Also runs for the browser's own print command (Ctrl+P)
function fillPrintHeader() {
    const select = document.getElementById('discipline-select');
    const scope = select.value ? select.options[select.selectedIndex].text : 'All disciplines';
    document.getElementById('print-date').textContent = `${scope} · as of ${new Date().toLocaleString()}`;
}

document.addEventListener('DOMContentLoaded', async () => {
    const select = document.getElementById('discipline-select');
    const pageBreak = document.getElementById('page-per-discipline');
    pageBreak.checked = readStored(PAGE_BREAK_KEY) === 'true';

    select.addEventListener('change', () => {
        store(DISCIPLINE_KEY, select.value);
        loadRanking();
    });
    pageBreak.addEventListener('change', () => {
        store(PAGE_BREAK_KEY, String(pageBreak.checked));
        loadRanking();
    });
    document.getElementById('refresh-btn').addEventListener('click', loadRanking);
    document.getElementById('print-btn').addEventListener('click', () => window.print());
    window.addEventListener('beforeprint', fillPrintHeader);

    try {
        await loadDisciplines();
    } catch (error) {
        showMessage(`Error loading disciplines: ${error.message}`);
    }
    loadRanking();
});
