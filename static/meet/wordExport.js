// Start cards and race bibs as Word documents, laid out like the printouts
// (meet.css): a start card is an A4 portrait page, a race bib an A4
// landscape page.

import { docx, paragraph, table } from '../js/officeFiles.js';
import { countryLabel } from '../js/countries.js';
import { t } from '../js/i18n.js';

const GREY = '444444';
const meetLine = (meet) => [meet.location, meet.dateText].filter(Boolean).join(' · ');

// Start card: A4 portrait with 12mm margins leaves 186mm
const CARD_WIDTH = 186;
const START_COLUMNS = [38, 17, 18, 20, 14, 49, 30];

const idSize = (id) => (String(id).length >= 5 ? 32 : String(id).length === 4 ? 38 : 44);

const rankingNames = (rankingsByDiscipline, disciplineId) =>
    (rankingsByDiscipline?.[disciplineId] || []).map(r => r.name).join(', ');

// The discipline shot, with the rankings it counts in below it
function disciplineCell(start, rankingsByDiscipline, style = {}) {
    const rankings = rankingNames(rankingsByDiscipline, start.discipline_id);
    return {
        paragraphs: [
            paragraph(start.discipline_name ?? '', { size: 10, ...style }),
            ...(rankings ? [paragraph(t('meet.card.rankings', { rankings }), { size: 7.5, color: GREY, italic: false })] : [])
        ]
    };
}

function startCard(meet, competitor, row, printedAt, formatDay, first, { rankingsByDiscipline, teamsByCompetitor } = {}) {
    const scheduled = row?.scheduled || [];
    const unscheduled = row?.unscheduled || [];
    const noLane = row?.no_lane || [];
    const teams = teamsByCompetitor?.get(competitor.id) || [];
    const header = [
        paragraph(meet.name || t('meet.card.title'), { size: 20, bold: true, pageBreakBefore: !first, keepNext: true }),
        ...[meetLine(meet), meet.host ? t('meet.hostLine', { host: meet.host }) : ''].filter(Boolean)
            .map(text => paragraph(text, { size: 11, spaceBefore: 3, keepNext: true }))
    ];
    // a line under the meet's details, as on the printed card
    header.push(paragraph('', { size: 4, borderBottom: 1.5, spaceAfter: 14 }));

    const starter = table([[
        {
            vAlign: 'center',
            borders: { top: 3, left: 3, bottom: 3, right: 3 },
            paragraphs: [
                paragraph(t('meet.card.starterId'), { size: 8, caps: true, align: 'center' }),
                // smaller for long IDs, so they stay on one line in the box
                paragraph(String(competitor.id), { size: idSize(competitor.id), bold: true, align: 'center' })
            ]
        },
        {
            paragraphs: [
                paragraph(competitor.name, { size: 22, bold: true, spaceAfter: 4 }),
                ...[[t('competitors.club'), competitor.club], [t('competitors.country'), countryLabel(competitor.country)]].map(([label, value]) => paragraph([
                    { text: `${label}   `, color: GREY },
                    { text: value || '–', bold: true }
                ], { size: 12, spaceAfter: 2 }))
            ]
        }
    ]], { widths: [46, CARD_WIDTH - 46], borders: 'none', padding: 1.5 });

    const count = scheduled.length + unscheduled.length + noLane.length;
    const starts = count
        ? table([
            { cells: ['day', 'relay', 'time', 'range', 'lane', 'discipline', 'startId'].map(key => t(`meet.card.${key}`)), size: 8.5, caps: true },
            ...scheduled.map(entry => [
                formatDay(entry.date),
                { text: entry.sequence_no ?? '?', align: 'center' },
                { text: entry.start_time ?? '', align: 'center' },
                { text: entry.range_name ?? '', align: 'center' },
                { text: entry.lane_no ?? '', align: 'center' },
                disciplineCell(entry, rankingsByDiscipline),
                { text: entry.start_id, mono: true }
            ]),
            ...[...unscheduled.map(start => [t('meet.card.notScheduled'), start]), ...noLane.map(start => [t('meet.card.noLane'), start])]
                .map(([note, start]) => ({
                    italic: true,
                    color: '555555',
                    cells: [
                        { text: note, span: 5 },
                        disciplineCell(start, rankingsByDiscipline, { italic: true, color: '555555' }),
                        { text: start.start_id, mono: true, italic: false }
                    ]
                }))
        ], { widths: START_COLUMNS, header: true, size: 10, borderPt: 0.75, padding: 1.2 })
        : paragraph(t('stats.empty'), { italic: true, color: '555555' });

    const foot = table([[
        { text: `${competitor.name} · ${competitor.id}`, borders: { top: 0.75 } },
        { text: t('meet.card.printed', { time: printedAt }), align: 'right', borders: { top: 0.75 } }
    ]], { widths: [CARD_WIDTH / 2, CARD_WIDTH / 2], borders: 'none', size: 8, color: GREY });

    // The starter's teams, each with the team rankings it counts in
    const teamPart = teams.length ? [
        paragraph(t('meet.card.teams', { count: teams.length }), { size: 13, bold: true, spaceBefore: 14, spaceAfter: 5, keepNext: true }),
        table([
            { cells: [t('ranking.col.team'), t('teams.discipline'), t('meet.card.rankingsHeader')], size: 8.5, caps: true },
            ...teams.map(team => [team.name ?? '', team.discipline_name ?? '',
                rankingNames(rankingsByDiscipline, team.discipline_id)])
        ], { widths: [56, 60, CARD_WIDTH - 116], header: true, size: 10, borderPt: 0.75, padding: 1.2 })
    ] : [];

    return [
        ...header,
        starter,
        paragraph(t('meet.card.starts', { count }), { size: 13, bold: true, spaceBefore: 18, spaceAfter: 5, keepNext: true }),
        starts,
        ...teamPart,
        paragraph('', { spaceAfter: 18 }), // keeps the tables apart from the foot
        foot
    ];
}

/** One start card per starter; rows: the starter overview rows by competitor ID. */
export function startCardsDocx(meet, competitors, rows, { printedAt, formatDay, rankingsByDiscipline, teamsByCompetitor }) {
    const body = competitors.flatMap((c, i) => startCard(meet, c, rows.get(c.id), printedAt, formatDay, i === 0,
        { rankingsByDiscipline, teamsByCompetitor }));
    return docx(body, { margin: 12 });
}

// Race bib sizes follow meet.css: the fewer digits, the bigger the number
function bib(meet, competitor, first) {
    const number = String(competitor.id);
    const numberSize = number.length >= 5 ? 190 : number.length === 4 ? 240 : 300;
    const meetName = meet.name || '';
    const club = [competitor.club, countryLabel(competitor.country)].filter(Boolean).join(' · ');
    return [
        paragraph(meetName || ' ', {
            size: meetName.length > 45 ? 19 : 26, bold: true, caps: true, color: 'FFFFFF', align: 'center',
            shading: '000000', box: { pt: 0.5, space: 6 }, pageBreakBefore: !first
        }),
        paragraph(meetLine(meet) || ' ', { size: 15, align: 'center', spaceBefore: 8, borderBottom: 1.5 }),
        paragraph(number, { size: numberSize, bold: true, align: 'center', line: numberSize, spaceBefore: 6 }),
        paragraph(competitor.name, { size: competitor.name.length > 26 ? 30 : 40, bold: true, align: 'center', spaceBefore: 4 }),
        paragraph(club || ' ', { size: 22, align: 'center', spaceBefore: 2, spaceAfter: 8 }),
        paragraph(meet.host ? t('meet.hostLine', { host: meet.host }) : ' ', { size: 13, align: 'center', borderTop: 4 })
    ];
}

/** One A4 landscape race bib per starter. */
export function raceBibsDocx(meet, competitors) {
    const body = competitors.flatMap((c, i) => bib(meet, c, i === 0));
    return docx(body, { landscape: true, margin: 10, pageBorder: 6 });
}
