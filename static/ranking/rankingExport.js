// The ranking as a Word document (laid out like the printout: A4 portrait,
// optional cover and statistics pages) or as an Excel workbook (one sheet
// per ranking, optionally a statistics sheet).

import { categoryLabel, entriesLine, formatScore, hasSums, isCombined, ringHeader, typeHeader, typeTag } from '../js/teamRanking.js';
import { countryLabel } from '../js/countries.js';
import { locale, t } from '../js/i18n.js';
import { docx, logoParagraph, paragraph, table, xlsx } from '../js/officeFiles.js';

const RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];
// Competitors who started but have no result yet come after the ranked ones, without a rank
const hasResult = (row) => row.has_result !== false;
const GREY = '555555';
// A4 portrait with 10mm margins leaves 190mm
const PAGE_WIDTH = 190;

/** The current date and time to the minute, e.g. "29.9.2026, 22:15" */
export const nowText = () => new Date().toLocaleString(locale, {
    year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
});

/** "All rankings · as of …" */
export const timestampLine = (scope) => `${scope} · ${t('ranking.asOf', { time: nowText() })}`;

const rankingTitle = (ranking) => ranking.name;
const memberText = (member) => member.missing
    ? `${member.start_id} · ${t('ranking.startDeleted')}`
    : `${member.start_id} ${member.name}: ${member.has_result ? formatScore(member.score) : t('ranking.noResult')}`;

// --- Word ------------------------------------------------------------------

function coverPage(meet, scope) {
    const facts = [[t('cover.venue'), meet.location], [t('cover.date'), meet.dateText], [t('cover.host'), meet.host]].filter(([, value]) => value);
    return [
        paragraph(t('cover.kicker'), { size: 30, bold: true, caps: true, align: 'center', spaceBefore: 100 }),
        paragraph(meet.name || t('ranking.ranking'), { size: 28, bold: true, align: 'center', spaceBefore: 50 }),
        paragraph(scope, { size: 16, align: 'center', spaceBefore: 28, spaceAfter: 340 }), // the facts near the bottom
        ...(facts.length
            ? [table(facts.map(([label, value]) => [`${label}:`, { text: value, bold: true }]),
                { widths: [30, 110], borders: 'none', size: 14, padding: 1 })]
            : [])
    ];
}

const STATS_COLUMNS_PER_TABLE = 8;
const STATS_WIDTHS = { country: 26, number: 12, discipline: 16 };

function statisticsPage(meet, stats, pageBreakBefore) {
    const head = table([[
        {
            paragraphs: [
                paragraph(meet.name || t('ranking.ranking'), { bold: true, size: 9 }),
                paragraph([meet.dateText, meet.location].filter(Boolean).join(' · '), { size: 9 }),
                ...(meet.host ? [paragraph(meet.host, { size: 9 })] : [])
            ],
            borders: { bottom: 1.5 }
        },
        {
            paragraphs: [
                paragraph(t('stats.title'), { bold: true, size: 9, align: 'right' }),
                paragraph(t('ranking.asOf', { time: nowText() }), { size: 9, align: 'right' })
            ],
            borders: { bottom: 1.5 }
        }
    ]], { widths: [PAGE_WIDTH / 2, PAGE_WIDTH / 2], borders: 'none' });
    const body = [
        paragraph('', { size: 1, pageBreakBefore }), // a table cannot start a new page itself
        head,
        paragraph('', { spaceAfter: 10 })
    ];
    if (stats.total.persons === 0) {
        body.push(paragraph(t('stats.empty'), { italic: true, color: GREY }));
        return body;
    }

    // Like the printout: tables of 8 discipline columns each, the later ones
    // without the persons and starts columns
    const allRows = [{ country: t('stats.total'), total: true, ...stats.total }, ...stats.rows];
    for (let i = 0; i < stats.columns.length || i === 0; i += STATS_COLUMNS_PER_TABLE) {
        const columns = stats.columns.slice(i, i + STATS_COLUMNS_PER_TABLE);
        const first = i === 0;
        const numberColumns = first ? [STATS_WIDTHS.number, STATS_WIDTHS.number] : [];
        const header = {
            size: 6.5,
            cells: [
                { text: t('stats.country'), vAlign: 'bottom' },
                ...(first ? [t('stats.persons'), t('stats.starts')].map(text => ({ text, align: 'center', vAlign: 'bottom' })) : []),
                ...columns.map(c => ({
                    vAlign: 'bottom',
                    paragraphs: [
                        paragraph(c.name, { size: 6.5, bold: true, align: 'center' }),
                        ...(c.type ? [paragraph(`(${c.type})`, { size: 5.5, align: 'center' })] : [])
                    ]
                }))
            ]
        };
        const rows = allRows.map(row => ({
            bold: row.total,
            cells: [
                { text: row.country, shading: row.total ? 'F3F4F6' : '' },
                ...(first ? [row.persons, row.starts].map(n => ({ text: n, align: 'center', shading: row.total ? 'F3F4F6' : '' })) : []),
                ...columns.map(c => ({ text: row.perDiscipline.get(c.id) || '', align: 'center', shading: row.total ? 'F3F4F6' : '' }))
            ]
        }));
        body.push(
            table([header, ...rows], {
                widths: [STATS_WIDTHS.country, ...numberColumns, ...columns.map(() => STATS_WIDTHS.discipline)],
                header: true, headerShading: 'F3F4F6', size: 8.5, borderColor: '999999', padding: 0.4
            }),
            paragraph('', { spaceAfter: 12 })
        );
    }
    return body;
}

function individualTable(data) {
    const rows = data.rankings || [];
    const hasNotes = rows.some(row => row.notes);
    const combined = isCombined(data.ranking);
    const ringWidths = RINGS.map(() => 6);
    // A ranking of several entries has a column with the entry shot (e.g. WHITO), taken from the name column
    const tagWidth = 11;
    const nameWidth = (hasNotes ? 34 : 40) - (combined ? tagWidth : 0);
    const widths = [9, nameWidth, ...(combined ? [tagWidth] : []),
        ...(hasNotes ? [24, 15, 12, ...ringWidths, 12, 18] : [31, 20, 12, ...ringWidths, 12])];
    const center = (text) => ({ text, align: 'center' });
    // Below each competitor: a sum's disciplines (e.g. Remington), then their other results
    const componentRows = (row) => (row.components || []).map(c => ({
        size: 7,
        color: GREY,
        cells: [
            '',
            `\u00a0\u00a0\u00a0\u00a0${c.label}`, // indented; non-breaking spaces are kept by Word
            // In a combined ranking the discipline shot goes into its column
            ...(combined ? [center(c.short_name || '')] : []),
            { text: combined ? '' : c.short_name || '', mono: true },
            '',
            center(c.score === null ? '-' : formatScore(c.score)),
            ...RINGS.map(ring => center(c.freq_counts ? (c.freq_counts[ring] ?? 0) : '')),
            center(c.override_value ?? ''),
            ...(hasNotes ? [''] : [])
        ]
    }));
    const typeCells = (row, style) => (combined ? [{ text: typeTag(row), align: 'center', ...style }] : []);
    // The name with the start id below it, much smaller
    const nameCell = (row, style) => ({
        paragraphs: [
            paragraph(row.competitor.name, { size: 8, ...style }),
            paragraph(row.start_id, { size: 5.5, mono: true, color: GREY })
        ]
    });
    return table([
        {
            cells: [t('ranking.col.rank'), t('ranking.col.name'), ...(combined ? [center(typeHeader(data.ranking))] : []),
                t('ranking.col.club'), t('ranking.col.country'),
                center(t('ranking.col.result')), ...RINGS.map(ring => center(ringHeader(ring))),
                center(t('ranking.col.tieBreak')), ...(hasNotes ? [t('ranking.col.notes')] : [])]
        },
        ...rows.flatMap(row => [hasResult(row) ? {
            bold: row.rank <= 3,
            cells: [
                center(row.rank),
                nameCell(row, { bold: row.rank <= 3 }),
                ...typeCells(row, { bold: false }),
                { text: row.competitor.club || '', bold: false },
                { text: countryLabel(row.competitor.country || ''), bold: false },
                center(formatScore(row.score)),
                ...RINGS.map(ring => ({ text: row.freq_counts?.[ring] ?? 0, align: 'center', bold: false })),
                { text: row.override_value ?? '-', align: 'center', bold: false },
                ...(hasNotes ? [{ text: row.notes || '', bold: false, color: GREY }] : [])
            ]
        } : {
            cells: [
                center('-'),
                nameCell(row, { color: GREY }),
                ...typeCells(row, { color: GREY }),
                { text: row.competitor.club || '', color: GREY },
                { text: countryLabel(row.competitor.country || ''), color: GREY },
                { text: t('ranking.noResult'), align: 'center', italic: true, color: GREY },
                ...RINGS.map(() => ''),
                '',
                ...(hasNotes ? [''] : [])
            ]
        }, ...componentRows(row)])
    ], { widths, header: true, size: 8, borderColor: 'BBBBBB', padding: 0.7 });
}

function teamTable(data) {
    if (!data.rankings?.length) {
        return paragraph(t('ranking.noTeams'), { italic: true, color: GREY });
    }
    const center = (text) => ({ text, align: 'center' });
    const pooled = isCombined(data.ranking);
    return table([
        { cells: [t('ranking.col.rank'), t('ranking.col.team'), ...(pooled ? [center(typeHeader())] : []), t('ranking.col.members'),
            center(t('ranking.col.total')), ...RINGS.map(ring => center(ringHeader(ring))), center(t('ranking.col.tieBreak'))] },
        ...data.rankings.map(team => ({
            bold: team.rank <= 3,
            cells: [
                center(team.rank),
                {
                    paragraphs: [
                        paragraph(team.name, { size: 8, bold: true }),
                        ...(team.complete ? [] : [paragraph(t('ranking.incomplete'), { size: 7, italic: true, color: '92400E' })]),
                        ...(team.notes ? [paragraph(team.notes, { size: 7, color: GREY })] : [])
                    ]
                },
                ...(pooled ? [{ text: team.discipline_tag || '', align: 'center', bold: false }] : []),
                {
                    paragraphs: team.members.length
                        ? team.members.map(member => paragraph(memberText(member), { size: 8, color: member.missing ? 'B91C1C' : undefined }))
                        : [paragraph('-', { size: 8 })]
                },
                center(formatScore(team.total)),
                ...RINGS.map(ring => ({ text: team.freq_counts?.[ring] ?? 0, align: 'center', bold: false })),
                { text: team.tie_break ?? '-', align: 'center', bold: false }
            ]
        }))
    ], { widths: [9, 34, ...(pooled ? [11] : []), pooled ? 43 : 54, 13, ...RINGS.map(() => 6), 14],
        header: true, size: 8, borderColor: 'BBBBBB', padding: 0.7 });
}

function rankingSection(data, pageBreakBefore) {
    const ranking = data.ranking;
    const team = data.kind === 'team';
    const entries = isCombined(ranking) || hasSums(ranking) ? entriesLine(ranking) : '';
    const subline = [entries, team
        ? `${t('ranking.teamFrom', { composition: (ranking.composition || []).join(' + ') || '-' })} · ${t('ranking.shootersPerTeam', { count: ranking.team_size })}` : '']
        .filter(Boolean).join(' · ');
    return [
        paragraph([
            { text: rankingTitle(ranking) },
            { text: `   ${team ? t('ranking.categoryTeam', { category: categoryLabel(ranking.category) }) : categoryLabel(ranking.category)}`, bold: false, size: 9, color: GREY }
        ], { size: 12, bold: true, keepNext: true, spaceAfter: subline ? 1 : 4, pageBreakBefore }),
        ...(subline ? [paragraph(subline, { size: 9, color: GREY, keepNext: true, spaceAfter: 4 })] : []),
        team ? teamTable(data) : individualTable(data),
        paragraph('', { spaceAfter: 14 })
    ];
}

/**
 * rankings: the rankings with data, as shown on the page (/api/rankings/results
 * or /api/rankings/{id}/result). scope: "All rankings" or the chosen one. resultLabel: "Intermediate Result", "Final Result" or ''
 * (none), a watermark across the ranking pages (not the cover and statistics).
 */
export function rankingDocx({ logo, meet, scope, resultLabel, rankings, stats, withCover, withStats, pagePerDiscipline }) {
    const frontMatter = [];
    if (withCover && meet) frontMatter.push(...coverPage(meet, scope));
    if (withStats && stats) frontMatter.push(...statisticsPage(meet || {}, stats, frontMatter.length > 0));
    const title = [
        paragraph(meet?.name ? `${meet.name} · ${t('ranking.ranking')}` : t('ranking.ranking'), { size: 16, bold: true }),
        paragraph(timestampLine(scope), { size: 9, color: GREY })
    ];
    // The logo beside the title when there is one; the page is 210 - 2 * 10 mm wide
    const body = logo
        ? [table([[{ paragraphs: [logoParagraph(16)] }, { paragraphs: title }]],
            { widths: [20, 170], borders: 'none', padding: 0, vAlign: 'center' }), paragraph('', { spaceAfter: 12 })]
        : [title[0], paragraph(timestampLine(scope), { size: 9, color: GREY, spaceAfter: 12 })];
    rankings.forEach((data, i) => body.push(...rankingSection(data, pagePerDiscipline && i > 0)));
    return docx(body, { margin: 10, watermark: resultLabel, frontMatter, logo });
}

// --- Excel -----------------------------------------------------------------

const number = (value) => (value === null || value === undefined || value === '' ? '' : Number(value));

function individualSheet(data) {
    const rows = data.rankings || [];
    const hasNotes = rows.some(row => row.notes);
    const combined = isCombined(data.ranking);
    const type = (row) => (combined ? [typeTag(row)] : []);
    // Below each competitor: a sum's disciplines (e.g. Remington), then their other results
    const componentRows = (row) => (row.components || []).map(c => [
        '', c.start_id || '', `  ${c.label}`, ...(combined ? [c.short_name || ''] : []), combined ? '' : c.short_name || '', '',
        c.score === null ? '' : number(c.score), ...RINGS.map(ring => (c.freq_counts ? (c.freq_counts[ring] ?? 0) : '')),
        number(c.override_value), ...(hasNotes ? [''] : [])
    ]);
    return {
        name: rankingTitle(data.ranking),
        columns: [
            { header: t('ranking.col.rank'), width: 6 }, { header: t('ranking.col.start'), width: 12 }, { header: t('ranking.col.name'), width: 28 },
            ...(combined ? [{ header: typeHeader(data.ranking), width: 12 }] : []),
            { header: t('ranking.col.club'), width: 24 }, { header: t('ranking.col.country'), width: 16 }, { header: t('ranking.col.result'), width: 9 },
            ...RINGS.map(ring => ({ header: ringHeader(ring), width: 6 })), { header: t('ranking.col.tieBreak'), width: 9 },
            ...(hasNotes ? [{ header: t('ranking.col.notes'), width: 30 }] : [])
        ],
        rows: rows.flatMap(row => [hasResult(row)
            ? [
                row.rank, row.start_id, row.competitor.name, ...type(row), row.competitor.club || '', countryLabel(row.competitor.country || ''),
                number(row.score), ...RINGS.map(ring => row.freq_counts?.[ring] ?? 0),
                number(row.override_value),
                ...(hasNotes ? [row.notes || ''] : [])
            ]
            : [
                '', row.start_id, row.competitor.name, ...type(row), row.competitor.club || '', countryLabel(row.competitor.country || ''),
                t('ranking.noResult'), ...RINGS.map(() => ''), '',
                ...(hasNotes ? [''] : [])
            ], ...componentRows(row)])
    };
}

function teamSheet(data) {
    const pooled = isCombined(data.ranking);
    return {
        name: rankingTitle(data.ranking),
        columns: [
            { header: t('ranking.col.rank'), width: 6 }, { header: t('ranking.col.team'), width: 26 },
            ...(pooled ? [{ header: typeHeader(), width: 10 }] : []), { header: t('ranking.col.complete'), width: 10 },
            { header: t('ranking.col.members'), width: 45 }, { header: t('ranking.col.total'), width: 9 },
            ...RINGS.map(ring => ({ header: ringHeader(ring), width: 6 })), { header: t('ranking.col.tieBreak'), width: 9 },
            { header: t('ranking.col.notes'), width: 30 }
        ],
        rows: (data.rankings || []).map(team => [
            team.rank, team.name, ...(pooled ? [team.discipline_tag || ''] : []), team.complete ? t('common.yes') : t('common.no'),
            team.members.map(memberText).join('\n'),
            number(team.total), ...RINGS.map(ring => team.freq_counts?.[ring] ?? 0), number(team.tie_break),
            team.notes || ''
        ])
    };
}

function statisticsSheet(stats) {
    const rows = [{ country: t('stats.total'), total: true, ...stats.total }, ...stats.rows];
    return {
        name: t('stats.sheet'),
        columns: [
            { header: t('stats.country'), width: 20 }, { header: t('stats.persons'), width: 9 }, { header: t('stats.starts'), width: 9 },
            ...stats.columns.map(c => ({ header: c.type ? `${c.name} (${c.type})` : c.name, width: 14 }))
        ],
        rows: stats.total.persons === 0 ? [] : rows.map(row => ({
            bold: row.total,
            cells: [row.country, row.persons, row.starts, ...stats.columns.map(c => row.perDiscipline.get(c.id) || 0)]
        }))
    };
}

/** One sheet per ranking, plus the statistics sheet when asked for. */
export function rankingXlsx({ rankings, stats, withStats }) {
    const sheets = rankings.map(data => (data.kind === 'team' ? teamSheet(data) : individualSheet(data)));
    if (withStats && stats) sheets.push(statisticsSheet(stats));
    return xlsx(sheets);
}
