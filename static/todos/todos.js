// Todos page: the entries of the ranking (/api/ranking/best) that still need
// a tie-break value. Entries tie when they have the same result and the same
// number of 10s, 9s, ... 1s; the tie-break (lower wins) then decides, so a
// group of tied entries is a todo while one of them has no tie-break or two
// of them have the same one. Shown like the Ranking page, without the rank.

import { teamRankingCard } from '../js/teamRanking.js';
import { hasResult, individualCard } from '../ranking/rankingCard.js';

// The rings of the countback; misses (0s) do not count
const COUNTBACK_RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1'];

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

const isTeam = (data) => data.kind === 'team';
const scoreOf = (data, row) => (isTeam(data) ? row.total : row.score);
const tieBreakOf = (data, row) => (isTeam(data) ? row.tie_break : row.override_value) ?? null;

function needsTieBreak(data, group) {
    const tieBreaks = group.map(row => tieBreakOf(data, row));
    return tieBreaks.includes(null) || new Set(tieBreaks).size < tieBreaks.length;
}

/** The groups of tied entries of one discipline that still need a tie-break, in ranking order. */
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

function render(rankings) {
    const content = document.getElementById('todos-content');
    const cards = rankings.map(data => {
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
            markMissingTieBreak: true,
            rowClass: (row, i) => (groupStarts.has(i) ? 'tie-group-start' : '')
        };
        const shown = { ...data, rankings: groups.flat() };
        return isTeam(data) ? teamRankingCard(shown, options) : individualCard(shown, options);
    }).filter(Boolean);

    content.innerHTML = cards.length
        ? cards.join('')
        : '<p class="text-center py-12 text-gray-500">Nothing to do: no entries need a tie-break.</p>';
}

async function load() {
    try {
        render(Object.values(await api('/ranking/best') || {}));
    } catch (error) {
        showMessage(`Error loading the ranking: ${error.message}`);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('refresh-btn').addEventListener('click', load);
    load();
});
