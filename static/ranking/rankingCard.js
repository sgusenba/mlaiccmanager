// Card with the table of one individual ranking (response of
// /api/rankings/{id}/result), used by the Ranking page and the Todos page.

import { categoryLabel, entriesLine, escapeHtml, formatScore, hasSums, isCombined, missingTieBreakCell, rankBadge, ringHeader, typeHeader, typeTag } from '../js/teamRanking.js';
import { countryLabel } from '../js/countries.js';
import { t } from '../js/i18n.js';

const RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];

// Competitors who started but have no result yet are listed after the ranked ones, without a rank
export const hasResult = (row) => row.has_result !== false;

// The name with the start id below it, much smaller
const nameCell = (row) => `
    <div>${escapeHtml(row.competitor.name)}</div>
    <div class="start-id font-mono font-normal text-gray-400" style="font-size: 0.65rem">${escapeHtml(row.start_id)}</div>`;

/**
 * showRank: false leaves out the rank column. rowClass(row, index): extra
 * classes for a row. markMissingTieBreak: shows a missing tie-break as such
 * instead of "-".
 */
export function individualCard(data, { extraClass = '', showRank = true, rowClass = () => '', markMissingTieBreak = false } = {}) {
    const rows = data.rankings || [];
    const ranking = data.ranking;
    const hasNotes = rows.some(row => row.notes);
    const combined = isCombined(ranking);
    const aggregate = hasSums(ranking);
    // Below each competitor: the disciplines added up in their result (a sum, e.g. Remington), then
    // their other results that did not count (another start, another entry of the ranking)
    const componentRows = (row, i) => (row.components || []).map(c => `
                                <tr class="text-xs font-normal text-gray-500 ${rowClass(row, i)}" style="border-top-width: 0">
                                    ${showRank ? '<td></td>' : ''}
                                    <td class="pl-8 pr-4 pb-2 whitespace-nowrap">${escapeHtml(c.label)}${c.start_id
                                        ? `<div class="font-mono text-gray-400" style="font-size: 0.65rem">${escapeHtml(c.start_id)}</div>` : ''}</td>
                                    ${combined ? `<td class="px-2 pb-2 text-center">${escapeHtml(c.short_name || '')}</td>` : ''}
                                    <td class="px-4 pb-2 font-mono">${combined ? '' : escapeHtml(c.short_name || '')}</td>
                                    <td></td>
                                    <td class="px-4 pb-2 text-center">${c.score === null ? '-' : formatScore(c.score)}</td>
                                    ${RINGS.map(ring => `<td class="px-2 pb-2 text-center">${c.freq_counts ? (c.freq_counts[ring] ?? 0) : ''}</td>`).join('')}
                                    <td class="px-4 pb-2 text-center">${c.override_value ?? ''}</td>
                                    ${hasNotes ? '<td></td>' : ''}
                                </tr>`).join('');
    const typeCell = (row) => combined
        ? `<td class="px-2 py-3 text-center text-sm">${escapeHtml(typeTag(row))}</td>` : '';
    const th = (label, align = 'left', title = '', padding = 'px-4') =>
        `<th class="${padding} py-3 text-${align} text-xs font-medium text-gray-500 uppercase tracking-wider"${title ? ` title="${title}"` : ''}>${label}</th>`;
    const tieBreak = (row) => row.override_value ?? (markMissingTieBreak ? missingTieBreakCell() : '-');

    return `
        <div class="bg-white rounded-lg shadow-md ${extraClass}">
            <div class="px-6 py-4 border-b border-gray-200">
                <h3 class="text-lg font-semibold text-gray-900">
                    ${escapeHtml(ranking.name)}
                    <span class="text-sm text-gray-500 ml-2">${escapeHtml(categoryLabel(ranking.category))}</span>
                </h3>
                ${combined || aggregate ? `<p class="text-sm text-gray-500">${escapeHtml(entriesLine(ranking))}</p>` : ''}
            </div>
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                ${showRank ? th(t('ranking.col.rank')) : ''}
                                ${th(t('ranking.col.name'))}
                                ${combined ? th(typeHeader(ranking), 'center', t('ranking.discHint'), 'px-2') : ''}
                                ${th(t('ranking.col.club'))}
                                ${th(t('ranking.col.country'))}
                                ${th(t('ranking.col.result'), 'center')}
                                ${RINGS.map(ring => th(ringHeader(ring), 'center', '', 'px-2')).join('')}
                                ${th(t('ranking.col.tieBreak'), 'center', aggregate
                                    ? t('ranking.tieBreakHintSum')
                                    : t('ranking.tieBreakHint'))}
                                ${hasNotes ? th(t('ranking.col.notes')) : ''}
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${rows.map((row, i) => hasResult(row) ? `
                                <tr class="hover:bg-gray-50 ${showRank && row.rank <= 3 ? 'font-bold' : ''} ${rowClass(row, i)}">
                                    ${showRank ? `<td class="px-4 py-3 whitespace-nowrap">${rankBadge(row.rank)}</td>` : ''}
                                    <td class="px-4 py-3 whitespace-nowrap text-sm text-gray-900">${nameCell(row)}</td>
                                    ${typeCell(row)}
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(row.competitor.club || '')}</td>
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(countryLabel(row.competitor.country || ''))}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm text-gray-900">${formatScore(row.score)}</td>
                                    ${RINGS.map(ring => `<td class="px-2 py-3 text-center text-sm font-normal">${row.freq_counts?.[ring] ?? 0}</td>`).join('')}
                                    <td class="px-4 py-3 text-center text-sm font-normal">${tieBreak(row)}</td>
                                    ${hasNotes ? `<td class="px-4 py-3 text-sm font-normal text-gray-500">${escapeHtml(row.notes || '')}</td>` : ''}
                                </tr>${componentRows(row, i)}
                            ` : `
                                <tr class="hover:bg-gray-50 text-gray-500 ${rowClass(row, i)}">
                                    ${showRank ? '<td class="px-4 py-3 text-center text-sm">-</td>' : ''}
                                    <td class="px-4 py-3 whitespace-nowrap text-sm">${nameCell(row)}</td>
                                    ${typeCell(row)}
                                    <td class="px-4 py-3 text-sm">${escapeHtml(row.competitor.club || '')}</td>
                                    <td class="px-4 py-3 text-sm">${escapeHtml(countryLabel(row.competitor.country || ''))}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm italic">${t('ranking.noResult')}</td>
                                    ${RINGS.map(() => '<td class="px-2 py-3"></td>').join('')}
                                    <td class="px-4 py-3"></td>
                                    ${hasNotes ? '<td class="px-4 py-3"></td>' : ''}
                                </tr>${componentRows(row, i)}
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}
