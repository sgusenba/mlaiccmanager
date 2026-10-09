// Card with the ranking table of one individual discipline (response of
// /api/ranking/best/{id}), used by the Ranking page and the Todos page.

import { escapeHtml, disciplineDisplayName, formatScore, isCombined, missingTieBreakCell, rankBadge, typeTag } from '../js/teamRanking.js';

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
    const discipline = data.discipline;
    const hasNotes = rows.some(row => row.notes);
    const combined = isCombined(discipline);
    const typeCell = (row) => combined
        ? `<td class="px-2 py-3 text-center text-sm" title="${row.discipline_type || ''}">${typeTag(row)}</td>` : '';
    const th = (label, align = 'left', title = '', padding = 'px-4') =>
        `<th class="${padding} py-3 text-${align} text-xs font-medium text-gray-500 uppercase tracking-wider"${title ? ` title="${title}"` : ''}>${label}</th>`;
    const tieBreak = (row) => row.override_value ?? (markMissingTieBreak ? missingTieBreakCell : '-');

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
                                ${showRank ? th('Rank') : ''}
                                ${th('Name')}
                                ${combined ? th('O/R', 'center', 'Original or reproduction', 'px-2') : ''}
                                ${th('Club')}
                                ${th('Country')}
                                ${th('Result', 'center')}
                                ${RINGS.map(ring => th(`${ring}s`, 'center', '', 'px-2')).join('')}
                                ${th('Tie-break', 'center', 'Distance of the furthest shot; the lower value wins')}
                                ${hasNotes ? th('Notes') : ''}
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${rows.map((row, i) => hasResult(row) ? `
                                <tr class="hover:bg-gray-50 ${showRank && row.rank <= 3 ? 'font-bold' : ''} ${rowClass(row, i)}">
                                    ${showRank ? `<td class="px-4 py-3 whitespace-nowrap">${rankBadge(row.rank)}</td>` : ''}
                                    <td class="px-4 py-3 whitespace-nowrap text-sm text-gray-900">${nameCell(row)}</td>
                                    ${typeCell(row)}
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(row.competitor.club || '')}</td>
                                    <td class="px-4 py-3 text-sm font-normal text-gray-700">${escapeHtml(row.competitor.country || '')}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm text-gray-900">${formatScore(row.score)}</td>
                                    ${RINGS.map(ring => `<td class="px-2 py-3 text-center text-sm font-normal">${row.freq_counts?.[ring] ?? 0}</td>`).join('')}
                                    <td class="px-4 py-3 text-center text-sm font-normal">${tieBreak(row)}</td>
                                    ${hasNotes ? `<td class="px-4 py-3 text-sm font-normal text-gray-500">${escapeHtml(row.notes || '')}</td>` : ''}
                                </tr>
                            ` : `
                                <tr class="hover:bg-gray-50 text-gray-500 ${rowClass(row, i)}">
                                    ${showRank ? '<td class="px-4 py-3 text-center text-sm">-</td>' : ''}
                                    <td class="px-4 py-3 whitespace-nowrap text-sm">${nameCell(row)}</td>
                                    ${typeCell(row)}
                                    <td class="px-4 py-3 text-sm">${escapeHtml(row.competitor.club || '')}</td>
                                    <td class="px-4 py-3 text-sm">${escapeHtml(row.competitor.country || '')}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm italic">no result</td>
                                    ${RINGS.map(() => '<td class="px-2 py-3"></td>').join('')}
                                    <td class="px-4 py-3"></td>
                                    ${hasNotes ? '<td class="px-4 py-3"></td>' : ''}
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}
