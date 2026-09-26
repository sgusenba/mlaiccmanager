// The ranking as a Word document (laid out like the printout: A4 portrait,
// optional cover and statistics pages) or as an Excel workbook (one sheet
// per discipline, optionally a statistics sheet).

import { disciplineDisplayName, formatScore } from '../js/teamRanking.js';
import { docx, paragraph, table, xlsx } from '../js/officeFiles.js';

const RINGS = ['10', '9', '8', '7'];
const GREY = '555555';
// A4 portrait with 10mm margins leaves 190mm
const PAGE_WIDTH = 190;

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
                paragraph(`as of ${new Date().toLocaleString()}`, { size: 9, align: 'right' })
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
    const widths = hasNotes
        ? [11, 22, 32, 26, 18, 15, 9, 9, 9, 9, 14, 16]
        : [11, 22, 38, 32, 22, 15, 9, 9, 9, 9, 14];
    const center = (text) => ({ text, align: 'center' });
    return table([
        {
            cells: ['Rank', 'Start', 'Name', 'Club', 'Country', center('Result'), ...RINGS.map(ring => center(`${ring}s`)),
                center('Tie-break'), ...(hasNotes ? ['Notes'] : [])]
        },
        ...rows.map(row => ({
            bold: row.rank <= 3,
            cells: [
                center(row.rank),
                { text: row.start_id, mono: true, size: 7.5 },
                row.competitor.name,
                { text: row.competitor.club || '', bold: false },
                { text: row.competitor.country || '', bold: false },
                center(formatScore(row.score)),
                ...RINGS.map(ring => ({ text: row.freq_counts?.[ring] ?? 0, align: 'center', bold: false })),
                { text: row.override_value ?? '-', align: 'center', bold: false },
                ...(hasNotes ? [{ text: row.notes || '', bold: false, color: GREY }] : [])
            ]
        }))
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
    ], { widths: [11, 40, 74, 15, 9, 9, 9, 9, 14], header: true, size: 8, borderColor: 'BBBBBB', padding: 0.7 });
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
            ? [paragraph(`Based on ${discipline.based_on || '-'} · ${discipline.team_size} shooters per team`,
                { size: 9, color: GREY, keepNext: true, spaceAfter: 4 })]
            : []),
        team ? teamTable(data) : individualTable(data),
        paragraph('', { spaceAfter: 14 })
    ];
}

/**
 * rankings: the disciplines with data, as shown on the page (individual:
 * /api/ranking/best, team: /api/teams/ranking). scope: "All disciplines" or
 * the chosen one.
 */
export function rankingDocx({ meet, scope, rankings, stats, withCover, withStats, pagePerDiscipline }) {
    const body = [];
    if (withCover && meet) body.push(...coverPage(meet, scope));
    if (withStats && stats) body.push(...statisticsPage(meet || {}, stats, body.length > 0));
    body.push(
        paragraph(meet?.name ? `${meet.name} · Ranking` : 'Ranking', { size: 16, bold: true, pageBreakBefore: body.length > 0 }),
        paragraph(`${scope} · as of ${new Date().toLocaleString()}`, { size: 9, color: GREY, spaceAfter: 12 })
    );
    rankings.forEach((data, i) => body.push(...disciplineSection(data, pagePerDiscipline && i > 0)));
    return docx(body, { margin: 10 });
}

// --- Excel -----------------------------------------------------------------

const number = (value) => (value === null || value === undefined || value === '' ? '' : Number(value));

function individualSheet(data) {
    const rows = data.rankings || [];
    const hasNotes = rows.some(row => row.notes);
    return {
        name: disciplineTitle(data.discipline),
        columns: [
            { header: 'Rank', width: 6 }, { header: 'Start', width: 12 }, { header: 'Name', width: 28 },
            { header: 'Club', width: 24 }, { header: 'Country', width: 16 }, { header: 'Result', width: 9 },
            ...RINGS.map(ring => ({ header: `${ring}s`, width: 6 })), { header: 'Tie-break', width: 9 },
            ...(hasNotes ? [{ header: 'Notes', width: 30 }] : [])
        ],
        rows: rows.map(row => [
            row.rank, row.start_id, row.competitor.name, row.competitor.club || '', row.competitor.country || '',
            number(row.score), ...RINGS.map(ring => row.freq_counts?.[ring] ?? 0), number(row.override_value),
            ...(hasNotes ? [row.notes || ''] : [])
        ])
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
