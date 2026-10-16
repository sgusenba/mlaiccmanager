// Team ranking table and helpers, used by the Ranking page (/ranking) and the Todos page (/todos)

import { locale, t, tOr } from './i18n.js';

export const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

/** "Miquelet (original)": the event alone is ambiguous, it exists once per type. */
export const disciplineDisplayName = (event, type) => (type ? `${event} (${tOr(`type.${type}`, type)})` : event);

/** Whether a ranking ranks several entries together (e.g. Whitworth_O and Whitworth_R): each row then says which. */
export const isCombined = (ranking) => !!ranking?.pooled;

/** Header of the column with the entry a row's result is from. */
export const typeHeader = () => t('ranking.col.disc');

/** Whether a ranking adds up disciplines (e.g. Remington_O = Colt + Donald Malson_O) in one of its entries. */
export const hasSums = (ranking) => (ranking?.entries || []).some(e => e.discipline_ids.length > 1);

/** The entry a row's result is from, e.g. "WHITO" or "Remington_O". */
export const typeTag = (row) => row.discipline_tag || '';

/** What a ranking ranks, e.g. "WHITO | WHITR" or "Remington_O (COLT + MALO)". */
export const entriesLine = (ranking) => (ranking?.entries || [])
    .map(e => (e.discipline_ids.length > 1 ? `${e.label} (${e.short_names.join(' + ')})` : e.short_names[0]))
    .join(' | ');

const RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];

export const formatScore = (value) => (value === null || value === undefined ? '-' : Number(value).toLocaleString(locale, { maximumFractionDigits: 2 }));

/** A ranking's category (rifle, pistol, or both as "rifle / pistol") in the page's language. */
export const categoryLabel = (category) => String(category ?? '').split(' / ')
    .map(part => tOr(`category.${part}`, part)).join(' / ');

/** The column header of a ring, e.g. "10s". */
export const ringHeader = (ring) => t('ranking.col.ring', { ring });

export function rankBadge(rank) {
    const icon = rank === 1 ? '🥇' : rank === 2 ? '🥈' : rank === 3 ? '🥉' : rank;
    const color = rank === 1 ? 'bg-yellow-100 text-yellow-800 border-yellow-300'
        : rank === 2 ? 'bg-gray-100 text-gray-800 border-gray-300'
        : rank === 3 ? 'bg-orange-100 text-orange-800 border-orange-300'
        : 'bg-blue-50 text-blue-800 border-blue-200';
    return `<div class="flex items-center justify-center w-8 h-8 rounded-full border-2 ${color}"><span class="font-bold text-sm">${icon}</span></div>`;
}

function memberLine(member) {
    if (member.missing) {
        return `<div class="text-sm text-red-600">${escapeHtml(member.start_id)} · ${t('ranking.startDeleted')}</div>`;
    }
    return `
        <div class="text-sm flex justify-between gap-4">
            <span><span class="font-mono text-xs text-gray-500">${escapeHtml(member.start_id)}</span> ${escapeHtml(member.name)}</span>
            <span class="${member.has_result ? 'text-gray-900' : 'text-gray-400 italic'}">${member.has_result ? formatScore(member.score) : t('ranking.noResult')}</span>
        </div>`;
}

/** Tie-break cell of a row whose tie-break still has to be entered. */
export const missingTieBreakCell = () => `<span class="inline-block px-2 py-0.5 rounded bg-yellow-100 text-yellow-800 text-xs font-medium">${t('ranking.missing')}</span>`;

/**
 * Card with the ranked teams of one team ranking (response of /api/rankings/{id}/result).
 * showRank: false leaves out the rank column. rowClass(team, index): extra
 * classes for a row. markMissingTieBreak: shows a missing tie-break as such instead of "-".
 */
export function teamRankingCard(data, { extraClass = '', showRank = true, rowClass = () => '', markMissingTieBreak = false } = {}) {
    const ranking = data.ranking;
    const pooled = isCombined(ranking);
    const header = `
        <div class="px-6 py-4 border-b border-gray-200">
            <h3 class="text-lg font-semibold text-gray-900">
                ${escapeHtml(ranking.name)}
                <span class="text-sm text-gray-500 ml-2">${escapeHtml(t('ranking.categoryTeam', { category: categoryLabel(ranking.category) }))}</span>
            </h3>
            <p class="text-sm text-gray-500">${pooled ? `${escapeHtml(entriesLine(ranking))} · ` : ''}${escapeHtml(t('ranking.teamFrom', { composition: (ranking.composition || []).join(' + ') || '-' }))} · ${escapeHtml(t('ranking.shootersPerTeam', { count: ranking.team_size }))}</p>
        </div>`;

    if (!data.rankings || data.rankings.length === 0) {
        return `
            <div class="bg-white rounded-lg shadow-md ${extraClass}">
                ${header}
                <div class="p-6 text-center text-gray-500">${t('ranking.noTeams')}</div>
            </div>`;
    }

    return `
        <div class="bg-white rounded-lg shadow-md ${extraClass}">
            ${header}
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                ${showRank ? `<th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">${t('ranking.col.rank')}</th>` : ''}
                                <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">${t('ranking.col.team')}</th>
                                ${pooled ? `<th class="px-2 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">${typeHeader()}</th>` : ''}
                                <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">${t('ranking.col.members')}</th>
                                <th class="px-4 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">${t('ranking.col.total')}</th>
                                ${RINGS.map(ring => `<th class="px-2 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">${ringHeader(ring)}</th>`).join('')}
                                <th class="px-4 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider" title="${t('ranking.tieBreakHint')}">${t('ranking.col.tieBreak')}</th>
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${data.rankings.map((team, i) => `
                                <tr class="hover:bg-gray-50 align-top ${showRank && team.rank <= 3 ? 'font-bold' : ''} ${rowClass(team, i)}">
                                    ${showRank ? `<td class="px-4 py-3 whitespace-nowrap">${rankBadge(team.rank)}</td>` : ''}
                                    <td class="px-4 py-3">
                                        <div class="text-sm font-medium text-gray-900">${escapeHtml(team.name)}</div>
                                        ${team.complete ? '' : `<span class="inline-block mt-1 text-xs font-normal px-2 py-0.5 rounded bg-yellow-100 text-yellow-800">${t('ranking.incomplete')}</span>`}
                                        ${team.notes ? `<div class="text-xs font-normal text-gray-500 mt-1">${escapeHtml(team.notes)}</div>` : ''}
                                    </td>
                                    ${pooled ? `<td class="px-2 py-3 text-center text-sm font-normal">${escapeHtml(team.discipline_tag || '')}</td>` : ''}
                                    <td class="px-4 py-3 font-normal min-w-[16rem]">${team.members.map(memberLine).join('') || '<span class="text-sm text-gray-400">-</span>'}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm">${formatScore(team.total)}</td>
                                    ${RINGS.map(ring => `<td class="px-2 py-3 text-center text-sm font-normal">${team.freq_counts[ring] ?? 0}</td>`).join('')}
                                    <td class="px-4 py-3 text-center text-sm font-normal">${team.tie_break ?? (markMissingTieBreak ? missingTieBreakCell() : '-')}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}
