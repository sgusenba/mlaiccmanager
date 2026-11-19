// Todos page: the entries of the rankings (/api/rankings/results) that still need
// a tie-break value. Entries tie when they have the same result and the same
// number of 10s, 9s, ... 1s; the tie-break (lower wins) then decides, so a
// group of tied entries is a todo while one of them has no tie-break or two
// of them have the same one. Shown like the Ranking page, without the rank.
// Above them: the teams with fewer starters than the team size (e.g. 2 of 3).

import { escapeHtml, rankingColumns, teamRankingCard } from '../js/teamRanking.js';
import { hasResult, individualCard } from '../ranking/rankingCard.js';
import { t } from '../js/i18n.js';

// The rings of the countback; misses (0s) do not count
const COUNTBACK_RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1'];

async function api(path) {
    const response = await fetch(`/api${path}`);
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new Error(json?.error || t('common.httpError', { status: response.status }));
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

const isTeam = (data) => data.kind === 'team';
const scoreOf = (data, row) => (isTeam(data) ? row.total : row.score);
const tieBreakOf = (data, row) => (isTeam(data) ? row.tie_break : row.override_value) ?? null;

function needsTieBreak(data, group) {
    const tieBreaks = group.map(row => tieBreakOf(data, row));
    return tieBreaks.includes(null) || new Set(tieBreaks).size < tieBreaks.length;
}

/** The groups of tied entries of one ranking that still need a tie-break, in ranking order. */
function tieBreakTodos(data) {
    const groups = new Map();
    for (const row of data.rankings || []) {
        if (!isTeam(data) && !hasResult(row)) continue;
        const key = [scoreOf(data, row), ...COUNTBACK_RINGS.map(ring => row.freq_counts?.[ring] ?? 0)].join('|');
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(row);
    }
    return [...groups.values()].filter(group => group.length > 1 && needsTieBreak(data, group));
}

/**
 * The teams with fewer starters than the team size, each once (a team can be
 * in several team rankings): { team, size, starters, rankings }. A member
 * whose start was deleted does not count as a starter.
 */
export function incompleteTeams(rankings) {
    const byTeam = new Map();
    for (const data of rankings) {
        if (!isTeam(data)) continue;
        for (const team of data.rankings || []) {
            const size = team.team_size ?? data.ranking?.team_size;
            const starters = (team.members || []).filter(member => !member.missing);
            if (!size || starters.length >= size) continue;
            const key = team.id ?? team.name;
            if (!byTeam.has(key)) byTeam.set(key, { team, size, starters, rankings: [] });
            byTeam.get(key).rankings.push(data.ranking?.name || '');
        }
    }
    return [...byTeam.values()];
}

function incompleteTeamsCard(entries) {
    if (entries.length === 0) return '';
    const th = (label, align = 'left') =>
        `<th class="px-4 py-3 text-${align} text-xs font-medium text-gray-500 uppercase tracking-wider">${label}</th>`;
    return `
        <div class="bg-white rounded-lg shadow-md">
            <div class="px-6 py-4 border-b border-gray-200">
                <h3 class="text-lg font-semibold text-gray-900">${t('todos.teams.title')}</h3>
                <p class="text-sm text-gray-500">${t('todos.teams.intro')}</p>
            </div>
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                ${th(t('ranking.col.team'))}
                                ${th(t('todos.teams.rankings'))}
                                ${th(t('todos.teams.starters'), 'center')}
                                ${th(t('ranking.col.members'))}
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${entries.map(({ team, size, starters, rankings }) => `
                                <tr class="align-top">
                                    <td class="px-4 py-3 text-sm font-medium text-gray-900">${escapeHtml(team.name)}</td>
                                    <td class="px-4 py-3 text-sm text-gray-700">${rankings.map(escapeHtml).join('<br>')}</td>
                                    <td class="px-4 py-3 text-center whitespace-nowrap">
                                        <span class="inline-block px-2 py-0.5 rounded bg-yellow-100 text-yellow-800 text-sm font-medium">${t('todos.teams.count', { count: starters.length, size })}</span>
                                    </td>
                                    <td class="px-4 py-3 text-sm text-gray-700">${starters.length
                                        ? starters.map(m => `<div><span class="font-mono text-xs text-gray-500">${escapeHtml(m.start_id)}</span> ${escapeHtml(m.name)}</div>`).join('')
                                        : '<span class="text-gray-400">-</span>'}</td>
                                </tr>`).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}

function render(rankings, columns) {
    const content = document.getElementById('todos-content');
    const tieBreakCards = rankings.map(data => {
        const groups = tieBreakTodos(data);
        if (groups.length === 0) return '';
        // First row of every group but the first gets a thicker line above it
        const groupStarts = new Set();
        groups.reduce((index, group) => {
            if (index > 0) groupStarts.add(index);
            return index + group.length;
        }, 0);
        const options = {
            showRank: false,
            columns,
            markMissingTieBreak: true,
            rowClass: (row, i) => (groupStarts.has(i) ? 'tie-group-start' : '')
        };
        const shown = { ...data, rankings: groups.flat() };
        return isTeam(data) ? teamRankingCard(shown, options) : individualCard(shown, options);
    }).filter(Boolean);
    const cards = [incompleteTeamsCard(incompleteTeams(rankings)), ...tieBreakCards].filter(Boolean);

    content.innerHTML = cards.length
        ? cards.join('')
        : `<p class="text-center py-12 text-gray-500">${t('todos.nothing')}</p>`;
}

async function load() {
    try {
        // the club and country columns as set on the Meet page; both if it cannot be loaded
        const [rankings, meet] = await Promise.all([api('/rankings/results'), api('/meet').catch(() => null)]);
        render(rankings || [], rankingColumns(meet));
    } catch (error) {
        showMessage(t('ranking.error.load', { error: error.message }));
    }
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('refresh-btn').addEventListener('click', load);
    load();
});
