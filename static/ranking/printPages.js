// Optional pages printed in front of the ranking: a cover page with the
// meet's details and a statistics page with the starters and starts per
// country and discipline.

import { escapeHtml } from '../js/meet.js';
import { nowText } from './rankingExport.js';
import { countryLabel } from '../js/countries.js';
import { locale, t, tOr } from '../js/i18n.js';

// Discipline columns per table; more are continued in another table below.
// Column widths in mm: 26 + 2 × 12 + 8 × 16 = 178, fits an A4 page with margins.
const COLUMNS_PER_BLOCK = 8;
const WIDTH_MM = { country: 26, number: 12, discipline: 16 };
const NO_COUNTRY = '';

/** Page 1: "Results", the meet's name, what the ranking covers, venue, date and host. */
export function coverPage(meet, scope) {
    const facts = [
        [t('cover.venue'), meet.location],
        [t('cover.date'), meet.dateText],
        [t('cover.host'), meet.host]
    ].filter(([, value]) => value);
    return `
        <div class="cover-kicker">${escapeHtml(t('cover.kicker'))}</div>
        <div class="cover-title">${escapeHtml(meet.name || t('ranking.ranking'))}</div>
        <div class="cover-scope">${escapeHtml(scope)}</div>
        <dl class="cover-facts">
            ${facts.map(([label, value]) => `<dt>${escapeHtml(label)}:</dt><dd>${escapeHtml(value)}</dd>`).join('')}
        </dl>`;
}

/**
 * Starters and starts per country: persons (competitors with at least one
 * start), starts, and starts per discipline, with the total first.
 */
export function entryStatistics(competitors, disciplines) {
    const byId = new Map(disciplines.map(d => [d.id, d]));
    const total = { persons: 0, starts: 0, perDiscipline: new Map() };
    const groups = new Map();

    for (const competitor of competitors) {
        const counts = Object.entries(competitor.starts || {})
            .map(([disciplineId, starts]) => [Number(disciplineId), starts?.length || 0])
            .filter(([, count]) => count > 0);
        if (counts.length === 0) continue;

        const country = competitor.country?.trim() || NO_COUNTRY;
        if (!groups.has(country)) groups.set(country, { persons: 0, starts: 0, perDiscipline: new Map() });
        for (const row of [total, groups.get(country)]) {
            row.persons += 1;
            for (const [disciplineId, count] of counts) {
                row.starts += count;
                row.perDiscipline.set(disciplineId, (row.perDiscipline.get(disciplineId) || 0) + count);
            }
        }
    }

    // Disciplines in catalog order; ones no longer in the catalog at the end
    const columns = [...total.perDiscipline.keys()]
        .sort((a, b) => {
            const ia = disciplines.indexOf(byId.get(a));
            const ib = disciplines.indexOf(byId.get(b));
            return (ia < 0 ? Infinity : ia) - (ib < 0 ? Infinity : ib) || a - b;
        })
        .map(id => {
            const d = byId.get(id);
            return d
                ? { id, name: d.short_name || d.name, type: d.type && tOr(`type.${d.type}`, d.type) }
                : { id, name: t('stats.unknownDiscipline', { id }) };
        });
    // Countries by their name in the page's language, competitors without one last
    const rows = [...groups.entries()]
        .map(([country, row]) => ({ none: country === NO_COUNTRY, ...row, country: country === NO_COUNTRY ? t('stats.noCountry') : countryLabel(country) }))
        .sort((a, b) => a.none - b.none || a.country.localeCompare(b.country, locale));
    return { total, rows, columns };
}

/** Page 2: the statistics as tables of COLUMNS_PER_BLOCK discipline columns each. */
export function statisticsPage(meet, stats) {
    const cell = value => (value ? value : '');
    const blocks = [];
    for (let i = 0; i < Math.max(stats.columns.length, 1); i += COLUMNS_PER_BLOCK) {
        blocks.push(stats.columns.slice(i, i + COLUMNS_PER_BLOCK));
    }
    const allRows = [{ country: t('stats.total'), total: true, ...stats.total }, ...stats.rows];

    // Fixed column widths keep every block's discipline columns under the first block's;
    // later blocks leave the persons and starts columns empty, as in a results booklet
    const table = (columns, first) => `
        <table class="stats-table" style="width: ${WIDTH_MM.country + 2 * WIDTH_MM.number + columns.length * WIDTH_MM.discipline}mm">
            <colgroup>
                <col style="width: ${WIDTH_MM.country}mm">
                <col style="width: ${WIDTH_MM.number}mm"><col style="width: ${WIDTH_MM.number}mm">
                ${columns.map(() => `<col style="width: ${WIDTH_MM.discipline}mm">`).join('')}
            </colgroup>
            <thead>
                <tr>
                    <th class="text-left">${t('stats.country')}</th>
                    ${first ? `<th>${t('stats.persons')}</th><th>${t('stats.starts')}</th>` : '<th class="stats-gap"></th><th class="stats-gap"></th>'}
                    ${columns.map(c => `<th>${escapeHtml(c.name)}${c.type ? `<span class="stats-type">(${escapeHtml(c.type)})</span>` : ''}</th>`).join('')}
                </tr>
            </thead>
            <tbody>
                ${allRows.map(row => `
                    <tr class="${row.total ? 'stats-total' : ''}">
                        <td class="text-left">${escapeHtml(row.country)}</td>
                        ${first ? `<td>${row.persons}</td><td>${row.starts}</td>` : '<td class="stats-gap"></td><td class="stats-gap"></td>'}
                        ${columns.map(c => `<td>${cell(row.perDiscipline.get(c.id))}</td>`).join('')}
                    </tr>`).join('')}
            </tbody>
        </table>`;

    return `
        <div class="stats-head">
            <div>
                <div class="font-bold">${escapeHtml(meet.name || t('ranking.ranking'))}</div>
                <div>${escapeHtml([meet.dateText, meet.location].filter(Boolean).join(' · '))}</div>
                ${meet.host ? `<div>${escapeHtml(meet.host)}</div>` : ''}
            </div>
            <div class="text-right">
                <div class="font-bold">${t('stats.title')}</div>
                <div>${escapeHtml(t('ranking.asOf', { time: nowText() }))}</div>
            </div>
        </div>
        ${stats.total.persons === 0
            ? `<p class="text-gray-500">${t('stats.empty')}</p>`
            : blocks.map((columns, i) => table(columns, i === 0)).join('')}`;
}
