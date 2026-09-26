// Word (.docx) and Excel (.xlsx) files built in the browser, without any
// library: both formats are ZIP files of XML parts. The ZIP is written
// uncompressed ("stored"), which Word, Excel and LibreOffice all accept.

// --- ZIP -------------------------------------------------------------------

const CRC_TABLE = (() => {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
        table[n] = c >>> 0;
    }
    return table;
})();

function crc32(bytes) {
    let crc = 0xFFFFFFFF;
    for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 0xFF] ^ (crc >>> 8);
    return (crc ^ 0xFFFFFFFF) >>> 0;
}

/** ZIP archive of the given [path, text] entries as a Blob of the given type. */
export function zip(entries, type) {
    const encoder = new TextEncoder();
    const now = new Date();
    const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
    const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();
    const parts = [];
    const directory = [];
    let offset = 0;

    for (const [path, text] of entries) {
        const name = encoder.encode(path);
        const data = encoder.encode(text);
        const crc = crc32(data);

        const local = new DataView(new ArrayBuffer(30));
        local.setUint32(0, 0x04034b50, true);
        local.setUint16(4, 20, true);
        local.setUint16(10, dosTime, true);
        local.setUint16(12, dosDate, true);
        local.setUint32(14, crc, true);
        local.setUint32(18, data.length, true);
        local.setUint32(22, data.length, true);
        local.setUint16(26, name.length, true);
        parts.push(new Uint8Array(local.buffer), name, data);

        const central = new DataView(new ArrayBuffer(46));
        central.setUint32(0, 0x02014b50, true);
        central.setUint16(4, 20, true);
        central.setUint16(6, 20, true);
        central.setUint16(12, dosTime, true);
        central.setUint16(14, dosDate, true);
        central.setUint32(16, crc, true);
        central.setUint32(20, data.length, true);
        central.setUint32(24, data.length, true);
        central.setUint16(28, name.length, true);
        central.setUint32(42, offset, true);
        directory.push(new Uint8Array(central.buffer), name);

        offset += 30 + name.length + data.length;
    }

    const directorySize = directory.reduce((sum, part) => sum + part.length, 0);
    const end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(8, entries.length, true);
    end.setUint16(10, entries.length, true);
    end.setUint32(12, directorySize, true);
    end.setUint32(16, offset, true);
    return new Blob([...parts, ...directory, new Uint8Array(end.buffer)], { type });
}

// --- helpers ---------------------------------------------------------------

/** Text for XML content and attributes; drops control characters XML does not allow. */
export const xml = (value) => String(value ?? '')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));

/** Offers the Blob as a file download. */
export function downloadBlob(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** File name with the current date and time, e.g. start-cards-20260926-1430.docx */
export function exportFileName(prefix, extension) {
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    return `${prefix}-${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
        + `-${pad(now.getHours())}${pad(now.getMinutes())}.${extension}`;
}

const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n';
const REL_NS = 'http://schemas.openxmlformats.org/package/2006/relationships';
const DOC_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

// --- Word ------------------------------------------------------------------
// Sizes: mm for page and column widths, pt for fonts and spacing. Word uses
// twips (1/20 pt) for lengths, half points for font sizes and eighths of a
// point for border widths.

const W_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
const twips = (mm) => Math.round(mm * 56.6929);
const pt20 = (pt) => Math.round(pt * 20);

function runProperties(style) {
    const props = [
        style.mono ? '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>' : '',
        style.bold ? '<w:b/><w:bCs/>' : '',
        style.italic ? '<w:i/><w:iCs/>' : '',
        style.caps ? '<w:caps/>' : '',
        style.color ? `<w:color w:val="${style.color}"/>` : '',
        style.size ? `<w:sz w:val="${Math.round(style.size * 2)}"/><w:szCs w:val="${Math.round(style.size * 2)}"/>` : ''
    ].join('');
    return props ? `<w:rPr>${props}</w:rPr>` : '';
}

/** A run of text; line breaks in the text become line breaks in Word. */
function run(text, style) {
    const lines = String(text ?? '').split('\n');
    return `<w:r>${runProperties(style)}${lines
        .map(line => `<w:t xml:space="preserve">${xml(line)}</w:t>`)
        .join('<w:br/>')}</w:r>`;
}

const border = (side, pt, color = '000000', space = 0) =>
    `<w:${side} w:val="single" w:sz="${Math.round(pt * 8)}" w:space="${space}" w:color="${color}"/>`;

/**
 * A paragraph. content: text, or a list of runs { text, bold, italic, size, color, mono }.
 * style: align, size, bold, italic, caps, color, mono, spaceBefore/spaceAfter (pt),
 * line (exact line height in pt), shading (fill colour), box ({ pt, space, color }: a
 * border around the paragraph), borderTop/borderBottom (pt), keepNext, pageBreakBefore.
 */
export function paragraph(content, style = {}) {
    const runs = Array.isArray(content) ? content : [{ text: content }];
    const box = style.box;
    const borders = box
        ? ['top', 'left', 'bottom', 'right'].map(side => border(side, box.pt, box.color, box.space)).join('')
        : (style.borderTop ? border('top', style.borderTop, '000000', 4) : '')
            + (style.borderBottom ? border('bottom', style.borderBottom, '000000', 4) : '');
    const spacing = [
        style.spaceBefore ? `w:before="${pt20(style.spaceBefore)}"` : '',
        `w:after="${pt20(style.spaceAfter ?? 0)}"`,
        style.line ? `w:line="${pt20(style.line)}" w:lineRule="exact"` : ''
    ].filter(Boolean).join(' ');
    const props = [
        style.keepNext ? '<w:keepNext/>' : '',
        style.pageBreakBefore ? '<w:pageBreakBefore/>' : '',
        borders ? `<w:pBdr>${borders}</w:pBdr>` : '',
        style.shading ? `<w:shd w:val="clear" w:color="auto" w:fill="${style.shading}"/>` : '',
        `<w:spacing ${spacing}/>`,
        style.align ? `<w:jc w:val="${style.align === 'right' ? 'right' : style.align === 'center' ? 'center' : 'left'}"/>` : ''
    ].join('');
    return `<w:p><w:pPr>${props}</w:pPr>${runs.map(r => run(r.text, { ...style, ...r })).join('')}</w:p>`;
}

const CELL_STYLE_KEYS = ['align', 'size', 'bold', 'italic', 'caps', 'color', 'mono'];
const pick = (object, keys) => Object.fromEntries(keys.filter(k => object?.[k] !== undefined).map(k => [k, object[k]]));

/**
 * A table with fixed column widths (mm). rows: lists of cells, or { cells, header, ...style }.
 * A cell is a text or number, or { text | paragraphs, span, shading, borders, vAlign, ...style }.
 * options: widths, borders ('all' | 'none'), borderPt, header (the first row repeats on every
 * page and is shaded), padding (mm), plus the text style of all cells.
 */
export function table(rows, options = {}) {
    const widths = options.widths.map(twips);
    const tableStyle = pick(options, CELL_STYLE_KEYS);
    const lines = options.borders === 'none'
        ? ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(side => `<w:${side} w:val="nil"/>`).join('')
        : ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(side => border(side, options.borderPt ?? 0.5, options.borderColor)).join('');
    const pad = twips(options.padding ?? 1);

    const rowXml = (row, index) => {
        const cells = Array.isArray(row) ? row : row.cells;
        const header = options.header && index === 0;
        const rowStyle = { ...(header ? { bold: true } : {}), ...pick(Array.isArray(row) ? {} : row, CELL_STYLE_KEYS) };
        let column = 0;
        const cellXml = cells.map(cell => {
            const spec = cell !== null && typeof cell === 'object' ? cell : { text: cell };
            const span = spec.span || 1;
            const width = widths.slice(column, column + span).reduce((a, b) => a + b, 0);
            column += span;
            const style = { ...tableStyle, ...rowStyle, ...pick(spec, CELL_STYLE_KEYS) };
            const shading = spec.shading || (header ? options.headerShading || 'E5E7EB' : '');
            const cellBorders = spec.borders
                ? `<w:tcBorders>${['top', 'left', 'bottom', 'right']
                    .map(side => spec.borders[side] ? border(side, spec.borders[side]) : '').join('')}</w:tcBorders>`
                : '';
            const body = spec.paragraphs?.length ? spec.paragraphs.join('') : paragraph(spec.text ?? '', style);
            return `<w:tc><w:tcPr><w:tcW w:w="${width}" w:type="dxa"/>${span > 1 ? `<w:gridSpan w:val="${span}"/>` : ''}`
                + cellBorders
                + (shading ? `<w:shd w:val="clear" w:color="auto" w:fill="${shading}"/>` : '')
                + `<w:vAlign w:val="${spec.vAlign || options.vAlign || 'top'}"/></w:tcPr>${body}</w:tc>`;
        }).join('');
        return `<w:tr><w:trPr><w:cantSplit/>${header ? '<w:tblHeader/>' : ''}</w:trPr>${cellXml}</w:tr>`;
    };

    return `<w:tbl><w:tblPr><w:tblW w:w="${widths.reduce((a, b) => a + b, 0)}" w:type="dxa"/>`
        + `<w:tblBorders>${lines}</w:tblBorders><w:tblLayout w:type="fixed"/>`
        + `<w:tblCellMar><w:top w:w="${pad}" w:type="dxa"/><w:left w:w="${pad * 2}" w:type="dxa"/>`
        + `<w:bottom w:w="${pad}" w:type="dxa"/><w:right w:w="${pad * 2}" w:type="dxa"/></w:tblCellMar></w:tblPr>`
        + `<w:tblGrid>${widths.map(w => `<w:gridCol w:w="${w}"/>`).join('')}</w:tblGrid>`
        + rows.map(rowXml).join('') + '</w:tbl>';
}

/**
 * A Word document of one A4 section. body: paragraphs and tables in order.
 * options: landscape, margin (mm), pageBorder (pt): a border around every page.
 */
export function docx(body, { landscape = false, margin = 15, pageBorder = 0 } = {}) {
    const [width, height] = landscape ? [16838, 11906] : [11906, 16838];
    const m = twips(margin);
    const pageBorders = pageBorder
        ? `<w:pgBorders w:offsetFrom="text">${['top', 'left', 'bottom', 'right']
            .map(side => border(side, pageBorder, '000000', 4)).join('')}</w:pgBorders>`
        : '';
    // Word wants a paragraph after a table that ends the document
    const document = `${XML_HEAD}<w:document xmlns:w="${W_NS}" xmlns:r="${DOC_REL}"><w:body>${body.join('')}<w:p/>`
        + `<w:sectPr><w:pgSz w:w="${width}" w:h="${height}"${landscape ? ' w:orient="landscape"' : ''}/>`
        + `<w:pgMar w:top="${m}" w:right="${m}" w:bottom="${m}" w:left="${m}" w:header="0" w:footer="0" w:gutter="0"/>`
        + `${pageBorders}</w:sectPr></w:body></w:document>`;

    const styles = `${XML_HEAD}<w:styles xmlns:w="${W_NS}"><w:docDefaults>`
        + '<w:rPrDefault><w:rPr><w:rFonts w:ascii="Arial" w:eastAsia="Arial" w:hAnsi="Arial" w:cs="Arial"/>'
        + '<w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:rPrDefault>'
        + '<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault>'
        + '</w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>'
        + '<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/>'
        + '<w:tblPr><w:tblInd w:w="0" w:type="dxa"/></w:tblPr></w:style></w:styles>';

    return zip([
        ['[Content_Types].xml', `${XML_HEAD}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
            + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            + '<Default Extension="xml" ContentType="application/xml"/>'
            + '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            + '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
            + '</Types>'],
        ['_rels/.rels', `${XML_HEAD}<Relationships xmlns="${REL_NS}">`
            + `<Relationship Id="rId1" Type="${DOC_REL}/officeDocument" Target="word/document.xml"/></Relationships>`],
        ['word/_rels/document.xml.rels', `${XML_HEAD}<Relationships xmlns="${REL_NS}">`
            + `<Relationship Id="rId1" Type="${DOC_REL}/styles" Target="styles.xml"/></Relationships>`],
        ['word/document.xml', document],
        ['word/styles.xml', styles]
    ], 'application/vnd.openxmlformats-officedocument.wordprocessingml.document');
}

// --- Excel -----------------------------------------------------------------

const S_NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
// Cell styles, as defined in styles.xml below
const XF = { normal: 0, header: 1, wrap: 2, bold: 3, boldWrap: 4 };

function columnName(index) {
    let name = '';
    for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
    return name;
}

/** Excel's rules for sheet names: no []:*?/\, at most 31 characters, unique ignoring case. */
function sheetNames(names) {
    const used = new Set();
    return names.map(raw => {
        const base = String(raw ?? '').replace(/[[\]:*?/\\]/g, ' ').replace(/\s+/g, ' ').trim()
            .replace(/^'+|'+$/g, '') || 'Sheet';
        let name = base.slice(0, 31);
        for (let n = 2; used.has(name.toLowerCase()); n++) {
            const suffix = ` (${n})`;
            name = base.slice(0, 31 - suffix.length).trimEnd() + suffix;
        }
        used.add(name.toLowerCase());
        return name;
    });
}

function cellXml(value, ref, bold) {
    if (value === null || value === undefined || value === '') return '';
    if (typeof value === 'number' && Number.isFinite(value)) {
        return `<c r="${ref}"${bold ? ` s="${XF.bold}"` : ''}><v>${value}</v></c>`;
    }
    const text = String(value);
    const style = text.includes('\n') ? (bold ? XF.boldWrap : XF.wrap) : (bold ? XF.bold : XF.normal);
    return `<c r="${ref}" t="inlineStr"${style ? ` s="${style}"` : ''}><is><t xml:space="preserve">${xml(text)}</t></is></c>`;
}

function sheetXml(sheet, selected) {
    const columns = sheet.columns;
    const lastColumn = columnName(Math.max(columns.length, 1) - 1);
    const header = `<row r="1">${columns.map((column, i) =>
        `<c r="${columnName(i)}1" t="inlineStr" s="${XF.header}"><is><t xml:space="preserve">${xml(column.header)}</t></is></c>`).join('')}</row>`;
    const rows = sheet.rows.map((row, r) => {
        const cells = Array.isArray(row) ? row : row.cells;
        const bold = !Array.isArray(row) && row.bold;
        return `<row r="${r + 2}">${cells.map((value, i) => cellXml(value, `${columnName(i)}${r + 2}`, bold)).join('')}</row>`;
    }).join('');
    return `${XML_HEAD}<worksheet xmlns="${S_NS}" xmlns:r="${DOC_REL}">`
        + `<dimension ref="A1:${lastColumn}${sheet.rows.length + 1}"/>`
        + `<sheetViews><sheetView workbookViewId="0"${selected ? ' tabSelected="1"' : ''}>`
        + '<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
        + '<sheetFormatPr defaultRowHeight="15"/>'
        + `<cols>${columns.map((column, i) => `<col min="${i + 1}" max="${i + 1}" width="${column.width || 12}" customWidth="1"/>`).join('')}</cols>`
        + `<sheetData>${header}${rows}</sheetData>`
        + (sheet.rows.length ? `<autoFilter ref="A1:${lastColumn}${sheet.rows.length + 1}"/>` : '')
        + '</worksheet>';
}

/**
 * An Excel workbook. sheets: { name, columns: [{ header, width }], rows }; a row is a
 * list of values (numbers stay numbers; texts with line breaks wrap) or { cells, bold }.
 * The header row is bold, shaded, frozen and has a filter.
 */
export function xlsx(sheets) {
    const names = sheetNames(sheets.map(sheet => sheet.name));
    const filters = sheets
        .map((sheet, i) => sheet.rows.length
            ? `<definedName name="_xlnm._FilterDatabase" localSheetId="${i}" hidden="1">'${xml(names[i].replace(/'/g, "''"))}'!$A$1:$${columnName(Math.max(sheet.columns.length, 1) - 1)}$${sheet.rows.length + 1}</definedName>`
            : '')
        .join('');
    const workbook = `${XML_HEAD}<workbook xmlns="${S_NS}" xmlns:r="${DOC_REL}"><sheets>`
        + names.map((name, i) => `<sheet name="${xml(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')
        + `</sheets>${filters ? `<definedNames>${filters}</definedNames>` : ''}</workbook>`;
    const styles = `${XML_HEAD}<styleSheet xmlns="${S_NS}">`
        + '<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
        + '<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>'
        + '<fill><patternFill patternType="solid"><fgColor rgb="FFE5E7EB"/><bgColor indexed="64"/></patternFill></fill></fills>'
        + '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
        + '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        + '<cellXfs count="5">'
        + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
        + '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>'
        + '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>'
        + '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>'
        + '<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>'
        + '</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>';

    return zip([
        ['[Content_Types].xml', `${XML_HEAD}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
            + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            + '<Default Extension="xml" ContentType="application/xml"/>'
            + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
            + sheets.map((_, i) => `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`).join('')
            + '</Types>'],
        ['_rels/.rels', `${XML_HEAD}<Relationships xmlns="${REL_NS}">`
            + `<Relationship Id="rId1" Type="${DOC_REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
        ['xl/workbook.xml', workbook],
        ['xl/_rels/workbook.xml.rels', `${XML_HEAD}<Relationships xmlns="${REL_NS}">`
            + sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="${DOC_REL}/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')
            + `<Relationship Id="rId${sheets.length + 1}" Type="${DOC_REL}/styles" Target="styles.xml"/></Relationships>`],
        ['xl/styles.xml', styles],
        ...sheets.map((sheet, i) => [`xl/worksheets/sheet${i + 1}.xml`, sheetXml(sheet, i === 0)])
    ], 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
}
