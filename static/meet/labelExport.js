// Target labels: one label per lane assignment (or a left and a right one),
// tiled on A4 sheets, printed (or saved as PDF from the print dialog) or
// exported as a Word document. Built from the rows of the lane assignments
// CSV (laneExport.js), so they come in the same time, range and lane order.
//
//   +---------------------------+-------------+-----------+
//   | 50m / 12 L                |     52      |    QR     |
//   |                           |             |  52-3-1   |
//   +---------------------------+-------------+-----------+
//   | Time: Sa 12.10. 14:30     | Cal: .45    | Miquelet  |
//   +===+===+===+===+===+===+===+===+===+===+===+===+=====+
//   | Z | 9 | 8 | 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
//   |   |   |   |   |   |   |   |   |   |   |   |
//   +---+---+---+---+---+---+---+---+---+---+---+
//
// Sizes are in mm and pt. The QR code is always 10 x 10 mm; everything else
// scales with the label size.

import { TINY_PARAGRAPH, docx, imageRelId, imageRun, paragraph } from '../js/officeFiles.js';
import { qrPng, qrSvg } from '../js/qrCode.js';
import { escapeHtml } from '../js/meet.js';
import { locale, t } from '../js/i18n.js';

export const DEFAULT_LABEL_SIZE = { width: 105, height: 48 };
export const LABEL_SIZE_LIMITS = { width: [60, 210], height: [34, 297] };

const PAGE = { width: 210, height: 297 };
const INSET = 2; // the frame is drawn this far inside the label's edge
const QR_MM = 10;
// The first column is the inner ten (Z in German, X in English)
const SCORE_COLUMNS = [t('meet.labels.innerTen'), '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];

/** "Sa 12.10. 14:30" in German, "Sat 10/12 14:30" in English */
function dayAndTime(isoDate, time) {
    const [year, month, day] = String(isoDate ?? '').split('-').map(Number);
    let date = '';
    if (year) {
        const d = new Date(year, month - 1, day);
        const weekday = d.toLocaleDateString(locale, { weekday: 'short' }).replace(/\.$/, '');
        date = `${weekday} ${d.toLocaleDateString(locale, { day: '2-digit', month: '2-digit' })}`;
    }
    return [date, time].filter(Boolean).join(' ');
}

/**
 * The labels of the scheduled rows of laneRows(); with pairs a left (L) and a
 * right (R) one per start; with day (YYYY-MM-DD) only those of that meet day.
 */
export function targetLabels(rows, { pairs = false, day = '' } = {}) {
    return rows.filter(row => row.scheduled && (!day || row.date === day)).flatMap(row => {
        const label = side => ({
            distance: `${row.range} / ${row.lane}${side ? ` ${side}` : ''}`,
            number: String(row.starterId),
            startId: String(row.startId),
            time: dayAndTime(row.date, row.startTime),
            caliber: row.caliber || '',
            discipline: row.disciplineShort || ''
        });
        return pairs ? [label('L'), label('R')] : [label('')];
    });
}

/** Width and height in mm within the limits; the default where not a number. */
export function labelSize(width, height) {
    const clamp = (value, [min, max], fallback) => {
        const number = Number(value);
        return Number.isFinite(number) && number > 0 ? Math.min(max, Math.max(min, number)) : fallback;
    };
    return {
        width: clamp(width, LABEL_SIZE_LIMITS.width, DEFAULT_LABEL_SIZE.width),
        height: clamp(height, LABEL_SIZE_LIMITS.height, DEFAULT_LABEL_SIZE.height)
    };
}

/**
 * Where everything goes for a label size: the frame's columns and rows (mm),
 * the grid both column sets fit in, the font scale, and the labels per sheet.
 */
function layout({ width, height }) {
    const frameWidth = width - 2 * INSET;
    const frameHeight = height - 2 * INSET;
    const qr = Math.max(frameWidth * 0.23, QR_MM + 5);
    const number = frameWidth * 0.25;
    const top = [frameWidth - qr - number, number, qr];
    const score = SCORE_COLUMNS.map(() => frameWidth / SCORE_COLUMNS.length);

    const rows = { time: frameHeight * 0.16, scoreHead: frameHeight * 0.14, score: frameHeight * 0.2 };
    rows.main = frameHeight - rows.time - rows.scoreHead - rows.score;

    // One grid for both rows of three and rows of eleven cells: every edge of either
    const edges = widths => widths.reduce((list, w) => [...list, list[list.length - 1] + w], [0]);
    const grid = [...new Set([...edges(top), ...edges(score)].map(edge => Math.round(edge * 100) / 100))].sort((a, b) => a - b);
    const spans = widths => edges(widths).slice(1).map((end, i, ends) => {
        const start = i === 0 ? 0 : ends[i - 1];
        return grid.filter(edge => edge > start + 0.005 && edge <= end + 0.005).length;
    });

    const columns = Math.max(1, Math.floor(PAGE.width / width));
    const lines = Math.max(1, Math.floor(PAGE.height / height));
    return {
        width, height, frameWidth, frameHeight, rows,
        grid: grid.slice(1).map((edge, i) => edge - grid[i]),
        topSpans: spans(top),
        scoreSpans: spans(score),
        scale: Math.min(width / DEFAULT_LABEL_SIZE.width, height / DEFAULT_LABEL_SIZE.height),
        columns, lines, perSheet: columns * lines,
        marginLeft: (PAGE.width - columns * width) / 2,
        marginTop: (PAGE.height - lines * height) / 2
    };
}

/** How many labels of a size fit on an A4 sheet: { columns, lines }. */
export function labelsPerSheet(size) {
    const { columns, lines } = layout(size);
    return { columns, lines };
}

// Font sizes (pt) at the default label size
const numberSize = (number) => (number.length >= 5 ? 22 : number.length === 4 ? 26 : 30);
const FONT = { distance: 15, text: 12, discipline: 12, score: 10, startId: 12 };
// A discipline short name longer than this many characters gets smaller, so it stays on one line below the QR code
const DISCIPLINE_CHARS = 9;
const disciplineSize = (discipline) => FONT.discipline * Math.min(1, DISCIPLINE_CHARS / Math.max(1, discipline.length));

const sheets = (labels, perSheet) =>
    Array.from({ length: Math.ceil(labels.length / perSheet) }, (_, i) => labels.slice(i * perSheet, (i + 1) * perSheet));

// --- print -----------------------------------------------------------------

export const LABEL_PAGE_SIZE = '@page { size: A4 portrait; margin: 0; }';

function labelHtml(label, l) {
    const pt = (size) => `${(size * l.scale).toFixed(2)}pt`;
    const td = (span, className, content, style = '') =>
        `<td colspan="${span}" class="${className}"${style ? ` style="${style}"` : ''}>${content}</td>`;
    const [distanceSpan, numberSpan, qrSpan] = l.topSpans;
    return `
        <div class="target-label" style="padding:${INSET}mm">
            <table style="width:${l.frameWidth}mm;height:${l.frameHeight}mm">
                <colgroup>${l.grid.map(w => `<col style="width:${w.toFixed(2)}mm">`).join('')}</colgroup>
                <tr style="height:${l.rows.main.toFixed(2)}mm">
                    ${td(distanceSpan, 'tl-distance', escapeHtml(label.distance), `font-size:${pt(FONT.distance)}`)}
                    ${td(numberSpan, 'tl-number', escapeHtml(label.number), `font-size:${pt(numberSize(label.number))}`)}
                    ${td(qrSpan, 'tl-qr', `${qrSvg(label.startId, QR_MM)}<div class="tl-id" style="font-size:${pt(FONT.startId)}">${escapeHtml(label.startId)}</div>`)}
                </tr>
                <tr style="height:${l.rows.time.toFixed(2)}mm;font-size:${pt(FONT.text)}">
                    ${td(distanceSpan, 'tl-text', `${escapeHtml(t('meet.labels.time'))} <b>${escapeHtml(label.time)}</b>`)}
                    ${td(numberSpan, 'tl-text', label.caliber ? `${escapeHtml(t('meet.labels.caliber'))} <b>${escapeHtml(label.caliber)}</b>` : '')}
                    ${td(qrSpan, 'tl-discipline', escapeHtml(label.discipline), `font-size:${pt(disciplineSize(label.discipline))}`)}
                </tr>
                <tr class="tl-score-head" style="height:${l.rows.scoreHead.toFixed(2)}mm;font-size:${pt(FONT.score)}">
                    ${SCORE_COLUMNS.map((text, i) => td(l.scoreSpans[i], 'tl-score', text)).join('')}
                </tr>
                <tr style="height:${l.rows.score.toFixed(2)}mm">
                    ${SCORE_COLUMNS.map((_, i) => td(l.scoreSpans[i], 'tl-score', '')).join('')}
                </tr>
            </table>
        </div>`;
}

/** The print pages: A4 sheets with the labels in rows, left to right. */
export function targetLabelsHtml(labels, size) {
    const l = layout(size);
    return sheets(labels, l.perSheet).map(sheet => `
        <section class="print-page label-sheet" style="padding:${l.marginTop.toFixed(2)}mm 0 0 ${l.marginLeft.toFixed(2)}mm;`
            + `grid-template-columns:repeat(${l.columns},${l.width}mm);grid-auto-rows:${l.height}mm">
            ${sheet.map(label => labelHtml(label, l)).join('')}
        </section>`).join('');
}

// --- Word ------------------------------------------------------------------
// One flat table for all labels, no table inside a table (Word lays nested
// tables out slowly): every label takes six rows and its grid columns plus a
// border-less column of INSET on either side; the rows above and below a
// label are border-less too. The lines are the table's own; only the
// border-less cells say so, as Word draws a line between two cells where
// either of them has one. Rows and columns add up exactly to the label size,
// so Word starts each sheet on a new page.

const twips = (mm) => Math.round(mm * 56.6929);
const LINE = '<w:{side} w:val="single" w:sz="6" w:space="0" w:color="000000"/>';
const lineBorders = (sides) => sides.map(side => LINE.replace('{side}', side)).join('');
const FRAME = ''; // the table's lines
// a double line between the label's details and the score grid, as on the printout
const FRAME_DOUBLE_TOP = '<w:top w:val="double" w:sz="6" w:space="0" w:color="000000"/>';
const NO_BORDERS = ['top', 'left', 'bottom', 'right'].map(side => `<w:${side} w:val="nil"/>`).join('');
// An empty cell needs a paragraph; the rows have an exact height, so a taller one is cut off, never grows the row
const EMPTY = '<w:p/>';
const keepNext = (xml) => (xml === EMPTY ? '<w:p><w:pPr><w:keepNext/></w:pPr></w:p>' : xml.replace('<w:pPr>', '<w:pPr><w:keepNext/>'));

/** Rounded to twips so that they add up to the rounded total: each one is a step of the running sum. */
function exactTwips(sizes) {
    let sum = 0;
    return sizes.map(size => {
        const before = twips(sum);
        sum += size;
        return twips(sum) - before;
    });
}

// Only cells with text are centred vertically
const tc = (width, span, body, borders) =>
    `<w:tc><w:tcPr><w:tcW w:w="${width}" w:type="dxa"/>${span > 1 ? `<w:gridSpan w:val="${span}"/>` : ''}`
    + `${borders ? `<w:tcBorders>${borders}</w:tcBorders>` : ''}${body === EMPTY ? '' : '<w:vAlign w:val="center"/>'}</w:tcPr>${body}</w:tc>`;

const tr = (height, cells) =>
    `<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val="${height}" w:hRule="exact"/></w:trPr>${cells}</w:tr>`;

/** The cells of one label (or of an empty slot) in one of its six rows, as [span, body, borders] in grid order. */
function labelRowCells(label, rowIndex, l, image, drawingId) {
    const gridColumns = l.grid.length;
    if (!label || rowIndex === 0 || rowIndex === 5) return [[gridColumns + 2, EMPTY, NO_BORDERS]];
    const size = (pt) => pt * l.scale;
    const [distanceSpan, numberSpan, qrSpan] = l.topSpans;
    const inset = [1, EMPTY, NO_BORDERS];
    let content;
    if (rowIndex === 1) {
        const qr = `<w:p><w:pPr><w:spacing w:after="20"/><w:jc w:val="center"/></w:pPr>`
            + `${imageRun(imageRelId(image), QR_MM, QR_MM, { id: drawingId, name: `qr${drawingId}.png`, description: t('meet.labels.qr', { id: label.startId }) })}</w:p>`
            + paragraph(label.startId, { size: size(FONT.startId), bold: true, align: 'center' });
        content = [
            [distanceSpan, paragraph(label.distance, { size: size(FONT.distance), bold: true }), FRAME],
            [numberSpan, paragraph(label.number, { size: size(numberSize(label.number)), bold: true, align: 'center' }), FRAME],
            [qrSpan, qr, FRAME]
        ];
    } else if (rowIndex === 2) {
        content = [
            [distanceSpan, paragraph([{ text: `${t('meet.labels.time')} ` }, { text: label.time, bold: true }], { size: size(FONT.text) }), FRAME],
            // no caliber: an empty cell, not "Cal:" alone
            [numberSpan, paragraph(label.caliber ? [{ text: `${t('meet.labels.caliber')} ` }, { text: label.caliber, bold: true }] : '', { size: size(FONT.text) }), FRAME],
            [qrSpan, paragraph(label.discipline, { size: size(disciplineSize(label.discipline)), bold: true, align: 'center' }), FRAME]
        ];
    } else if (rowIndex === 3) {
        content = SCORE_COLUMNS.map((text, i) => [l.scoreSpans[i], paragraph(text, { size: size(FONT.score), align: 'center' }), FRAME_DOUBLE_TOP]);
    } else {
        content = SCORE_COLUMNS.map((_, i) => [l.scoreSpans[i], EMPTY, FRAME]);
    }
    return [inset, ...content, inset];
}

/** The labels as a Word document: A4 pages with the labels in rows, left to right. */
export async function targetLabelsDocx(labels, size) {
    const l = layout(size);
    const startIds = [...new Set(labels.map(label => label.startId))];
    const imageOf = new Map(startIds.map((id, i) => [id, i]));
    const images = await Promise.all(startIds.map(id => qrPng(id)));

    // The grid: per label column an inset, the label's own grid, an inset
    const labelColumns = [INSET, ...l.grid, INSET];
    const widths = exactTwips(Array.from({ length: l.columns }, () => labelColumns).flat());
    const rowHeights = exactTwips([INSET, l.rows.main, l.rows.time, l.rows.scoreHead, l.rows.score, INSET]);

    let drawingId = 0;
    const drawingIds = new Map();
    const rows = [];
    for (let first = 0; first < labels.length; first += l.columns) {
        const slots = Array.from({ length: l.columns }, (_, i) => labels[first + i]);
        slots.forEach(label => { if (label) drawingIds.set(label, ++drawingId); });
        rowHeights.forEach((height, rowIndex) => {
            let column = 0;
            const cells = slots.flatMap(label => labelRowCells(label, rowIndex, l, label && imageOf.get(label.startId), label && drawingIds.get(label)))
                .map(([span, body, borders], i) => {
                    const width = widths.slice(column, column + span).reduce((a, b) => a + b, 0);
                    column += span;
                    // the rows of a label stay together on one page (the sizes already see to it; this is in case of rounding)
                    return tc(width, span, i === 0 && rowIndex < 5 ? keepNext(body) : body, borders);
                }).join('');
            rows.push(tr(height, cells));
        });
    }

    const pad = twips(0.8);
    const body = '<w:tbl><w:tblPr>'
        + `<w:tblW w:w="${widths.reduce((a, b) => a + b, 0)}" w:type="dxa"/>`
        + `<w:tblBorders>${lineBorders(['top', 'left', 'bottom', 'right', 'insideH', 'insideV'])}</w:tblBorders>`
        + '<w:tblLayout w:type="fixed"/>'
        + `<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="${pad}" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="${pad}" w:type="dxa"/></w:tblCellMar>`
        + `</w:tblPr><w:tblGrid>${widths.map(w => `<w:gridCol w:w="${w}"/>`).join('')}</w:tblGrid>`
        + rows.join('')
        + '</w:tbl>';

    // Room for exactly the sheet's rows of labels, so Word starts each sheet on a new page
    const bottom = Math.max(0, PAGE.height - l.marginTop - l.lines * l.height - 0.5);
    return docx([body], {
        margin: { top: l.marginTop, left: l.marginLeft, right: Math.max(0, PAGE.width - l.marginLeft - l.columns * l.width), bottom },
        images,
        lastParagraph: TINY_PARAGRAPH
    });
}
