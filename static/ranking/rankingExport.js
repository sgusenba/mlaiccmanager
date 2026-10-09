// The ranking as a Word document (laid out like the printout: A4 portrait,
// optional cover and statistics pages) or as an Excel workbook (one sheet
// per discipline, optionally a statistics sheet).

import { disciplineDisplayName, formatScore, isCombined, typeTag } from '../js/teamRanking.js';
import { docx, logoParagraph, paragraph, table, xlsx } from '../js/officeFiles.js';

const RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];
// Competitors who started but have no result yet come after the ranked ones, without a rank
const hasResult = (row) => row.has_result !== false;
const GREY = '555555';
// A4 portrait with 10mm margins leaves 190mm
const PAGE_WIDTH = 190;

/** The current date and time to the minute, e.g. "29.9.2026, 22:15" */
export const nowText = () => new Date().toLocaleString(undefined, {
    year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
});

/** "All disciplines · as of …" */
export const timestampLine = (scope) => `${scope} · as of ${nowText()}`;

const disciplineTitle = (discipline) => disciplineDisplayName(discipline.name, discipline.type);
const memberText = (member) => member.missing
    ? `${member.start_id} · start deleted`
    : `${member.start_id} ${member.name}: ${member.has_result ? formatScore(member.score) : 'no result'}`;

// --- Word ------------------------------------------------------------------

function coverPage(meet, scope) {
    const facts = [['Venue', meet.location], ['Date', meet.dateText], ['Host', meet.host]].filter(([, value]) => value);
    return [
        paragraph('Results', { size: 30, bold: true, caps: true, align: 'center', spaceBefore: 100 }),
        paragraph(meet.name || 'Ranking', { size: 28, bold: true, align: 'center', spaceBefore: 50 }),
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
                paragraph(meet.name || 'Ranking', { bold: true, size: 9 }),
                paragraph([meet.dateText, meet.location].filter(Boolean).join(' · '), { size: 9 }),
                ...(meet.host ? [paragraph(meet.host, { size: 9 })] : [])
            ],
            borders: { bottom: 1.5 }
        },
        {
            paragraphs: [
                paragraph('Starters and entries', { bold: true, size: 9, align: 'right' }),
                paragraph(`as of ${nowText()}`, { size: 9, align: 'right' })
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
        body.push(paragraph('No starts registered.', { italic: true, color: GREY }));
        return body;
    }

    // Like the printout: tables of 8 discipline columns each, the later ones
    // without the persons and starts columns
    const allRows = [{ country: 'TOTAL', total: true, ...stats.total }, ...stats.rows];
    for (let i = 0; i < stats.columns.length || i === 0; i += STATS_COLUMNS_PER_TABLE) {
        const columns = stats.columns.slice(i, i + STATS_COLUMNS_PER_TABLE);
        const first = i === 0;
        const numberColumns = first ? [STATS_WIDTHS.number, STATS_WIDTHS.number] : [];
        const header = {
            size: 6.5,
            cells: [
                { text: 'Country', vAlign: 'bottom' },
                ...(first ? ['Persons', 'Starts'].map(text => ({ text, align: 'center', vAlign: 'bottom' })) : []),
                ...columns.map(c => ({
                    vAlign: 'bottom',
                    paragraphs: [
                        paragraph(c.event, { size: 6.5, bold: true, align: 'center' }),
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
    const combined = isCombined(data.discipline);
    const ringWidths = RINGS.map(() => 6);
    // A combined ranking has an O/R column, taken from the name column
    const nameWidth = (hasNotes ? 34 : 40) - (combined ? 7 : 0);
    const widths = [9, nameWidth, ...(combined ? [7] : []),
        ...(hasNotes ? [24, 15, 12, ...ringWidths, 12, 18] : [31, 20, 12, ...ringWidths, 12])];
    const center = (text) => ({ text, align: 'center' });
    // An aggregate (Remington): below each competitor its disciplines' results with their own ring counts
    const componentRows = (row) => (row.components || []).map(c => ({
        size: 7,
        color: GREY,
        cells: [
            '',
            `\u00a0\u00a0\u00a0\u00a0${c.label}`, // indented; non-breaking spaces are kept by Word
            ...(combined ? [''] : []),
            { text: c.short_name || '', mono: true },
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
            cells: ['Rank', 'Name', ...(combined ? [center('O/R')] : []), 'Club', 'Country',
                center('Result'), ...RINGS.map(ring => center(`${ring}s`)),
                center('Tie-break'), ...(hasNotes ? ['Notes'] : [])]
        },
        ...rows.flatMap(row => [hasResult(row) ? {
            bold: row.rank <= 3,
            cells: [
                center(row.rank),
                nameCell(row, { bold: row.rank <= 3 }),
                ...typeCells(row, { bold: false }),
                { text: row.competitor.club || '', bold: false },
                { text: row.competitor.country || '', bold: false },
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
                { text: row.competitor.country || '', color: GREY },
                { text: 'no result', align: 'center', italic: true, color: GREY },
                ...RINGS.map(() => ''),
                '',
                ...(hasNotes ? [''] : [])
            ]
        }, ...componentRows(row)])
    ], { widths, header: true, size: 8, borderColor: 'BBBBBB', padding: 0.7 });
}

function teamTable(data) {
    if (!data.rankings?.length) {
        return paragraph('No teams entered for this discipline yet.', { italic: true, color: GREY });
    }
    const center = (text) => ({ text, align: 'center' });
    return table([
        { cells: ['Rank', 'Team', 'Members', center('Total'), ...RINGS.map(ring => center(`${ring}s`)), center('Tie-break')] },
        ...data.rankings.map(team => ({
            bold: team.rank <= 3,
            cells: [
                center(team.rank),
                {
                    paragraphs: [
                        paragraph(team.name, { size: 8, bold: true }),
                        ...(team.complete ? [] : [paragraph('incomplete', { size: 7, italic: true, color: '92400E' })]),
                        ...(team.notes ? [paragraph(team.notes, { size: 7, color: GREY })] : [])
                    ]
                },
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
    ], { widths: [9, 34, 54, 13, ...RINGS.map(() => 6), 14], header: true, size: 8, borderColor: 'BBBBBB', padding: 0.7 });
}

function disciplineSection(data, pageBreakBefore) {
    const discipline = data.discipline;
    const team = data.kind === 'team';
    return [
        paragraph([
            { text: disciplineTitle(discipline) },
            { text: `   ${discipline.category || ''}${team ? ' team' : ''}`, bold: false, size: 9, color: GREY }
        ], { size: 12, bold: true, keepNext: true, spaceAfter: team ? 1 : 4, pageBreakBefore }),
        ...(team
            ? [paragraph(`From ${(discipline.composition || []).join(' + ') || '-'} · ${discipline.team_size} shooters per team`,
                { size: 9, color: GREY, keepNext: true, spaceAfter: 4 })]
            : []),
        team ? teamTable(data) : individualTable(data),
        paragraph('', { spaceAfter: 14 })
    ];
}

/**
 * rankings: the disciplines with data, as shown on the page (individual:
 * /api/ranking/best, team: /api/teams/ranking). scope: "All disciplines" or
 * the chosen one. resultLabel: "Intermediate Result", "Final Result" or ''
 * (none), a watermark across the ranking pages (not the cover and statistics).
 */
export function rankingDocx({ logo, meet, scope, resultLabel, rankings, stats, withCover, withStats, pagePerDiscipline }) {
    const frontMatter = [];
    if (withCover && meet) frontMatter.push(...coverPage(meet, scope));
    if (withStats && stats) frontMatter.push(...statisticsPage(meet || {}, stats, frontMatter.length > 0));
    const title = [
        paragraph(meet?.name ? `${meet.name} · Ranking` : 'Ranking', { size: 16, bold: true }),
        paragraph(timestampLine(scope), { size: 9, color: GREY })
    ];
    // The logo beside the title when there is one; the page is 210 - 2 * 10 mm wide
    const body = logo
        ? [table([[{ paragraphs: [logoParagraph(16)] }, { paragraphs: title }]],
            { widths: [20, 170], borders: 'none', padding: 0, vAlign: 'center' }), paragraph('', { spaceAfter: 12 })]
        : [title[0], paragraph(timestampLine(scope), { size: 9, color: GREY, spaceAfter: 12 })];
    rankings.forEach((data, i) => body.push(...disciplineSection(data, pagePerDiscipline && i > 0)));
    return docx(body, { margin: 10, watermark: resultLabel, frontMatter, logo });
}

// --- Excel -----------------------------------------------------------------

const number = (value) => (value === null || value === undefined || value === '' ? '' : Number(value));

function individualSheet(data) {
    const rows = data.rankings || [];
    const hasNotes = rows.some(row => row.notes);
    const combined = isCombined(data.discipline);
    const type = (row) => (combined ? [typeTag(row)] : []);
    // An aggregate (Remington): below each competitor its disciplines' results with their own ring counts
    const componentRows = (row) => (row.components || []).map(c => [
        '', c.start_id || '', `  ${c.label}`, ...(combined ? [''] : []), c.short_name || '', '',
        c.score === null ? '' : number(c.score), ...RINGS.map(ring => (c.freq_counts ? (c.freq_counts[ring] ?? 0) : '')),
        number(c.override_value), ...(hasNotes ? [''] : [])
    ]);
    return {
        name: disciplineTitle(data.discipline),
        columns: [
            { header: 'Rank', width: 6 }, { header: 'Start', width: 12 }, { header: 'Name', width: 28 },
            ...(combined ? [{ header: 'O/R', width: 5 }] : []),
            { header: 'Club', width: 24 }, { header: 'Country', width: 16 }, { header: 'Result', width: 9 },
            ...RINGS.map(ring => ({ header: `${ring}s`, width: 6 })), { header: 'Tie-break', width: 9 },
            ...(hasNotes ? [{ header: 'Notes', width: 30 }] : [])
        ],
        rows: rows.flatMap(row => [hasResult(row)
            ? [
                row.rank, row.start_id, row.competitor.name, ...type(row), row.competitor.club || '', row.competitor.country || '',
                number(row.score), ...RINGS.map(ring => row.freq_counts?.[ring] ?? 0),
                number(row.override_value),
                ...(hasNotes ? [row.notes || ''] : [])
            ]
            : [
                '', row.start_id, row.competitor.name, ...type(row), row.competitor.club || '', row.competitor.country || '',
                'no result', ...RINGS.map(() => ''), '',
                ...(hasNotes ? [''] : [])
            ], ...componentRows(row)])
    };
}

function teamSheet(data) {
    return {
        name: `${disciplineTitle(data.discipline)} Team`,
        columns: [
            { header: 'Rank', width: 6 }, { header: 'Team', width: 26 }, { header: 'Complete', width: 10 },
            { header: 'Members', width: 45 }, { header: 'Total', width: 9 },
            ...RINGS.map(ring => ({ header: `${ring}s`, width: 6 })), { header: 'Tie-break', width: 9 },
            { header: 'Notes', width: 30 }
        ],
        rows: (data.rankings || []).map(team => [
            team.rank, team.name, team.complete ? 'yes' : 'no', team.members.map(memberText).join('\n'),
            number(team.total), ...RINGS.map(ring => team.freq_counts?.[ring] ?? 0), number(team.tie_break),
            team.notes || ''
        ])
    };
}

function statisticsSheet(stats) {
    const rows = [{ country: 'TOTAL', total: true, ...stats.total }, ...stats.rows];
    return {
        name: 'Statistics',
        columns: [
            { header: 'Country', width: 20 }, { header: 'Persons', width: 9 }, { header: 'Starts', width: 9 },
            ...stats.columns.map(c => ({ header: c.type ? `${c.event} (${c.type})` : c.event, width: 14 }))
        ],
        rows: stats.total.persons === 0 ? [] : rows.map(row => ({
            bold: row.total,
            cells: [row.country, row.persons, row.starts, ...stats.columns.map(c => row.perDiscipline.get(c.id) || 0)]
        }))
    };
}

/** One sheet per discipline, plus the statistics sheet when asked for. */
export function rankingXlsx({ rankings, stats, withStats }) {
    const sheets = rankings.map(data => (data.kind === 'team' ? teamSheet(data) : individualSheet(data)));
    if (withStats && stats) sheets.push(statisticsSheet(stats));
    return xlsx(sheets);
}
