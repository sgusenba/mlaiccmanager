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

import { docx, imageRelId, imageRun, paragraph } from '../js/officeFiles.js';
import { qrPng, qrSvg } from '../js/qrCode.js';
import { escapeHtml } from '../js/meet.js';

export const DEFAULT_LABEL_SIZE = { width: 105, height: 48 };
export const LABEL_SIZE_LIMITS = { width: [60, 210], height: [34, 297] };

const PAGE = { width: 210, height: 297 };
const INSET = 2; // the frame is drawn this far inside the label's edge
const QR_MM = 10;
const SCORE_COLUMNS = ['Z', '9', '8', '7', '6', '5', '4', '3', '2', '1', '0'];
const WEEKDAYS = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

/** "Sa 12.10. 14:30" */
function dayAndTime(isoDate, time) {
    const [year, month, day] = String(isoDate ?? '').split('-').map(Number);
    const date = year ? `${WEEKDAYS[new Date(year, month - 1, day).getDay()]} ${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}.` : '';
    return [date, time].filter(Boolean).join(' ');
}

/** The labels of the scheduled rows of laneRows(); with pairs a left (L) and a right (R) one per start. */
export function targetLabels(rows, { pairs = false } = {}) {
    return rows.filter(row => row.scheduled).flatMap(row => {
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
const FONT = { distance: 15, text: 10, discipline: 8, score: 10, startId: 5.5 };

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
                    ${td(qrSpan, 'tl-qr', `${qrSvg(label.startId, QR_MM)}<div class="tl-id" style="font-size:${FONT.startId}pt">${escapeHtml(label.startId)}</div>`)}
                </tr>
                <tr style="height:${l.rows.time.toFixed(2)}mm;font-size:${pt(FONT.text)}">
                    ${td(distanceSpan, 'tl-text', `Time: <b>${escapeHtml(label.time)}</b>`)}
                    ${td(numberSpan, 'tl-text', label.caliber ? `Cal: <b>${escapeHtml(label.caliber)}</b>` : '')}
                    ${td(qrSpan, 'tl-discipline', escapeHtml(label.discipline), `font-size:${pt(FONT.discipline)}`)}
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
// Each sheet row is a row of a borderless table with the label's own height;
// every label is a table of its own inside its cell.

const twips = (mm) => Math.round(mm * 56.6929);
const LINE = '<w:{side} w:val="single" w:sz="6" w:space="0" w:color="000000"/>';
const lineBorders = (sides) => sides.map(side => LINE.replace('{side}', side)).join('');
// Word wants a paragraph after a table in a cell; this one is 1pt high
const tinyParagraph = '<w:p><w:pPr><w:spacing w:after="0" w:line="20" w:lineRule="exact"/><w:rPr><w:sz w:val="2"/></w:rPr></w:pPr></w:p>';

function cell(l, span, startColumn, body, { vAlign = 'center', borders = '' } = {}) {
    const width = l.grid.slice(startColumn, startColumn + span).reduce((a, b) => a + b, 0);
    return `<w:tc><w:tcPr><w:tcW w:w="${twips(width)}" w:type="dxa"/>${span > 1 ? `<w:gridSpan w:val="${span}"/>` : ''}`
        + (borders ? `<w:tcBorders>${borders}</w:tcBorders>` : '')
        + `<w:vAlign w:val="${vAlign}"/></w:tcPr>${body}</w:tc>`;
}

/** Cells of a row from [span, body, options] in grid order. */
function cells(l, specs) {
    let column = 0;
    return specs.map(([span, body, options]) => {
        const xmlCell = cell(l, span, column, body, options);
        column += span;
        return xmlCell;
    }).join('');
}

const row = (heightMm, content) =>
    `<w:tr><w:trPr><w:cantSplit/><w:trHeight w:val="${twips(heightMm)}" w:hRule="exact"/></w:trPr>${content}</w:tr>`;

function labelDocx(label, l, image, drawingId) {
    const size = (pt) => pt * l.scale;
    const [distanceSpan, numberSpan, qrSpan] = l.topSpans;
    const qr = `<w:p><w:pPr><w:spacing w:after="20"/><w:jc w:val="center"/></w:pPr>`
        + `${imageRun(imageRelId(image), QR_MM, QR_MM, { id: drawingId, name: `qr${drawingId}.png`, description: `QR code ${label.startId}` })}</w:p>`
        + paragraph(label.startId, { size: FONT.startId, align: 'center' });
    // a double line between the label's details and the score grid, as on the printout
    const double = `<w:top w:val="double" w:sz="6" w:space="0" w:color="000000"/>`;

    // a little less high than the frame, so the label never outgrows its exact-height cell
    const h = (mm) => mm * (l.frameHeight - 0.6) / l.frameHeight;
    const rows = [
        row(h(l.rows.main), cells(l, [
            [distanceSpan, paragraph(label.distance, { size: size(FONT.distance), bold: true })],
            [numberSpan, paragraph(label.number, { size: size(numberSize(label.number)), bold: true, align: 'center' })],
            [qrSpan, qr]
        ])),
        row(h(l.rows.time), cells(l, [
            [distanceSpan, paragraph([{ text: 'Time: ' }, { text: label.time, bold: true }], { size: size(FONT.text) })],
            // no caliber: an empty cell, not "Cal:" alone
            [numberSpan, paragraph(label.caliber ? [{ text: 'Cal: ' }, { text: label.caliber, bold: true }] : '', { size: size(FONT.text) })],
            [qrSpan, paragraph(label.discipline, { size: size(FONT.discipline), align: 'center' })]
        ])),
        row(h(l.rows.scoreHead), cells(l, SCORE_COLUMNS.map((text, i) =>
            [l.scoreSpans[i], paragraph(text, { size: size(FONT.score), align: 'center' }), { borders: double }]))),
        row(h(l.rows.score), cells(l, SCORE_COLUMNS.map((_, i) => [l.scoreSpans[i], paragraph('')])))
    ];

    const pad = twips(0.8);
    return `<w:tbl><w:tblPr><w:tblW w:w="${twips(l.frameWidth)}" w:type="dxa"/><w:jc w:val="center"/>`
        + `<w:tblBorders>${lineBorders(['top', 'left', 'bottom', 'right', 'insideH', 'insideV'])}</w:tblBorders>`
        + '<w:tblLayout w:type="fixed"/>'
        + `<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="${pad}" w:type="dxa"/>`
        + `<w:bottom w:w="0" w:type="dxa"/><w:right w:w="${pad}" w:type="dxa"/></w:tblCellMar></w:tblPr>`
        + `<w:tblGrid>${l.grid.map(w => `<w:gridCol w:w="${twips(w)}"/>`).join('')}</w:tblGrid>`
        + rows.join('') + '</w:tbl>';
}

/** The labels as a Word document: A4 pages with the labels in rows, left to right. */
export async function targetLabelsDocx(labels, size) {
    const l = layout(size);
    const startIds = [...new Set(labels.map(label => label.startId))];
    const imageOf = new Map(startIds.map((id, i) => [id, i]));
    const images = await Promise.all(startIds.map(id => qrPng(id)));

    const sheetRows = [];
    for (let i = 0; i < labels.length; i += l.columns) sheetRows.push(labels.slice(i, i + l.columns));

    let drawingId = 0;
    const outerCell = (label) => `<w:tc><w:tcPr><w:tcW w:w="${twips(l.width)}" w:type="dxa"/><w:vAlign w:val="center"/></w:tcPr>`
        + (label ? labelDocx(label, l, imageOf.get(label.startId), ++drawingId) : '')
        + `${tinyParagraph}</w:tc>`;
    const body = '<w:tbl><w:tblPr>'
        + `<w:tblW w:w="${twips(l.width * l.columns)}" w:type="dxa"/>`
        + `<w:tblBorders>${['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(side => `<w:${side} w:val="nil"/>`).join('')}</w:tblBorders>`
        + '<w:tblLayout w:type="fixed"/>'
        + '<w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="0" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="0" w:type="dxa"/></w:tblCellMar>'
        + `</w:tblPr><w:tblGrid>${Array.from({ length: l.columns }, () => `<w:gridCol w:w="${twips(l.width)}"/>`).join('')}</w:tblGrid>`
        + sheetRows.map(labelsInRow => row(l.height,
            Array.from({ length: l.columns }, (_, i) => outerCell(labelsInRow[i])).join(''))).join('')
        + '</w:tbl>';

    // Room for exactly the sheet's rows of labels, so Word starts each sheet on a new page
    const bottom = Math.max(0, PAGE.height - l.marginTop - l.lines * l.height - 0.5);
    return docx([body], {
        margin: { top: l.marginTop, left: l.marginLeft, right: Math.max(0, PAGE.width - l.marginLeft - l.columns * l.width), bottom },
        images
    });
}
