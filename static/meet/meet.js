// Meet page: the meet's name, venue, host and dates, and the printouts made
// from them: a start card per starter (A4 portrait) and a race bib per
// starter (A4 landscape), printed or exported as Word documents, plus the
// lane assignments as a CSV file.

import { escapeHtml, formatDateRange, loadMeet } from '../js/meet.js';
import { downloadBlob, exportFileName } from '../js/officeFiles.js';
import { laneRows, toCsv } from './laneExport.js';
import { raceBibsDocx, startCardsDocx } from './wordExport.js';

const PAGE_SIZES = {
    cards: '@page { size: A4 portrait; margin: 12mm; }',
    bibs: '@page { size: A4 landscape; margin: 10mm; }'
};

const SEPARATOR_KEY = 'meet.csvSeparator';
const FORMAT_KEY = 'meet.exportFormat';
const EXPORT_TITLES = { cards: 'Export Start Cards', bibs: 'Export Race Bibs' };
const FILE_PREFIXES = { cards: 'start-cards', bibs: 'race-bibs' };

let meet = null;

// --- helpers ---------------------------------------------------------------

class ApiError extends Error {
    constructor(status, body) {
        super(body?.error || `HTTP error! status: ${status}`);
        this.status = status;
    }
}

async function api(path, method = 'GET', body) {
    const response = await fetch(`/api${path}`, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined
    });
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new ApiError(response.status, json);
    return json;
}

let messageTimer;
function showMessage(text, type = 'error') {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = `mx-4 mb-4 px-4 py-3 rounded-md max-w-5xl ${type === 'error'
        ? 'bg-red-100 text-red-800 border border-red-200'
        : 'bg-green-100 text-green-800 border border-green-200'}`;
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), type === 'error' ? 8000 : 3000);
}

function formatDay(isoDate) {
    if (!isoDate) return '';
    const [year, month, day] = isoDate.split('-').map(Number);
    const date = new Date(year, month - 1, day);
    return isNaN(date) ? isoDate : date.toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: '2-digit', day: '2-digit' });
}

const startCount = competitor => Object.values(competitor.starts || {}).reduce((sum, list) => sum + (list?.length || 0), 0);

// --- meet details ----------------------------------------------------------

function fillForm() {
    document.getElementById('meet-name').value = meet.name || '';
    document.getElementById('meet-location').value = meet.location || '';
    document.getElementById('meet-host').value = meet.host || '';
    document.getElementById('meet-date-from').value = meet.date_from || '';
    document.getElementById('meet-date-to').value = meet.date_to || '';

    const fromDays = formatDateRange(meet.days.from, meet.days.to);
    document.getElementById('meet-date-hint').textContent = fromDays
        ? `Left empty, the Meet Days are printed: ${fromDays}.`
        : 'Left empty, the first and last Meet Day are printed.';
}

async function refreshMeet() {
    meet = await loadMeet();
    fillForm();
}

async function saveMeet(event) {
    event.preventDefault();
    const button = document.getElementById('meet-save-btn');
    button.disabled = true;
    try {
        await api('/meet', 'PUT', {
            name: document.getElementById('meet-name').value,
            location: document.getElementById('meet-location').value,
            host: document.getElementById('meet-host').value,
            date_from: document.getElementById('meet-date-from').value || null,
            date_to: document.getElementById('meet-date-to').value || null,
            version: meet.version
        });
        await refreshMeet();
        showMessage('Meet details saved.', 'success');
    } catch (error) {
        if (error.status === 409) {
            await refreshMeet();
            showMessage(`${error.message}. The latest details are shown now; please make your change again.`);
        } else {
            showMessage(`Could not save the meet details: ${error.message}`);
        }
    } finally {
        button.disabled = false;
    }
}

// --- starters --------------------------------------------------------------

/** Competitors with at least one start, by starter ID. */
async function loadStarters() {
    const competitors = await api('/competitors');
    return (competitors || []).filter(c => startCount(c) > 0).sort((a, b) => a.id - b.id);
}

// Case- and accent-insensitive: "wurfl" finds "Würflingsdobler"
const normalize = (text) => String(text ?? '').normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

/** Starters matching every word of the search text in their ID, name, club or country. */
function matchingStarters(starters, query) {
    const terms = normalize(query).split(/\s+/).filter(Boolean);
    return starters.filter(c => {
        const text = normalize([c.id, c.name, c.club, c.country].join(' '));
        return terms.every(term => text.includes(term));
    });
}

const starterLabel = (c) => {
    const from = [c.club, c.country].filter(Boolean).map(escapeHtml).join(', ');
    return `${c.id} · ${escapeHtml(c.name)}${from ? ` (${from})` : ''}`;
};

let starterList = [];

/** Lists the starters matching the search field above the select; "All" prints all of them. */
function renderStarterSelect(search) {
    const select = document.getElementById(search.dataset.select);
    const filtered = search.value.trim() !== '';
    const matches = matchingStarters(starterList, search.value);
    const previous = select.value;
    select.innerHTML = `<option value="">${filtered ? 'All matching starters' : 'All starters'} (${matches.length})</option>`
        + matches.map(c => `<option value="${c.id}">${starterLabel(c)}</option>`).join('');
    select.value = [...select.options].some(o => o.value === previous) ? previous : '';
    // one match: pick it, so printing a single starter is search + print
    if (filtered && matches.length === 1) select.value = String(matches[0].id);
    select.closest('div').querySelector('.print-btn').disabled = matches.length === 0;
}

async function refreshStarterSelects() {
    starterList = await loadStarters();
    document.querySelectorAll('.starter-search').forEach(renderStarterSelect);
}

// --- printouts -------------------------------------------------------------

/** Compares two texts case-insensitively; empty texts go last. */
function compareText(a, b) {
    const x = String(a || '').trim();
    const y = String(b || '').trim();
    return (!x) - (!y) || x.localeCompare(y, undefined, { sensitivity: 'base' });
}

/** Print order of start cards and race bibs: by country, club, name, then starter ID. */
function printOrder(a, b) {
    return compareText(a.country, b.country)
        || compareText(a.club, b.club)
        || compareText(a.name, b.name)
        || a.id - b.id;
}

function meetLine() {
    return [meet.location, meet.dateText].filter(Boolean).map(escapeHtml).join(' · ');
}

function scheduledRow(entry) {
    return `
        <tr>
            <td>${escapeHtml(formatDay(entry.date))}</td>
            <td class="num">${escapeHtml(entry.sequence_no ?? '?')}</td>
            <td class="num">${escapeHtml(entry.start_time ?? '')}</td>
            <td class="num">${escapeHtml(entry.range_name ?? '')}</td>
            <td class="num">${escapeHtml(entry.lane_no ?? '')}</td>
            <td>${escapeHtml(entry.discipline_name ?? '')}</td>
            <td class="mono">${escapeHtml(entry.start_id)}</td>
        </tr>`;
}

function unscheduledRow(start) {
    return `
        <tr class="unscheduled">
            <td colspan="5">not yet scheduled</td>
            <td>${escapeHtml(start.discipline_name ?? '')}</td>
            <td class="mono">${escapeHtml(start.start_id)}</td>
        </tr>`;
}

function startCard(competitor, row, printedAt) {
    const scheduled = row?.scheduled || [];
    const unscheduled = row?.unscheduled || [];
    const starts = scheduled.length + unscheduled.length
        ? `
            <table>
                <thead>
                    <tr><th>Day</th><th>Relay</th><th>Time</th><th>Range</th><th>Lane</th><th>Discipline</th><th>Start ID</th></tr>
                </thead>
                <tbody>
                    ${scheduled.map(scheduledRow).join('')}
                    ${unscheduled.map(unscheduledRow).join('')}
                </tbody>
            </table>`
        : '<p class="card-empty">No starts registered.</p>';

    return `
        <section class="print-page start-card">
            <header class="card-head">
                <div class="card-meet">${escapeHtml(meet.name || 'Start Card')}</div>
                ${meetLine() ? `<div class="card-meet-sub">${meetLine()}</div>` : ''}
                ${meet.host ? `<div class="card-meet-sub">Host: ${escapeHtml(meet.host)}</div>` : ''}
            </header>
            <div class="card-starter">
                <div class="card-number">
                    <span class="card-number-label">Starter ID</span>
                    <span class="card-number-value">${escapeHtml(competitor.id)}</span>
                </div>
                <div class="min-w-0">
                    <div class="card-name">${escapeHtml(competitor.name)}</div>
                    <dl>
                        <dt>Club</dt><dd>${escapeHtml(competitor.club || '–')}</dd>
                        <dt>Country</dt><dd>${escapeHtml(competitor.country || '–')}</dd>
                    </dl>
                </div>
            </div>
            <h4>Starts (${scheduled.length + unscheduled.length})</h4>
            ${starts}
            <footer class="card-foot">
                <span>${escapeHtml(competitor.name)} · ${escapeHtml(competitor.id)}</span>
                <span>Printed ${escapeHtml(printedAt)}</span>
            </footer>
        </section>`;
}

function bib(competitor) {
    const number = String(competitor.id);
    const club = [competitor.club, competitor.country].filter(Boolean).map(escapeHtml).join(' · ');
    const meetName = meet.name || '';
    return `
        <section class="print-page bib">
            <div class="bib-band${meetName.length > 45 ? ' long' : ''}">${escapeHtml(meetName) || '&nbsp;'}</div>
            <div class="bib-sub">${meetLine() || '&nbsp;'}</div>
            <div class="bib-number${number.length >= 5 ? ' digits-5' : number.length === 4 ? ' digits-4' : ''}">${escapeHtml(number)}</div>
            <div class="bib-name${competitor.name.length > 26 ? ' long' : ''}">${escapeHtml(competitor.name)}</div>
            <div class="bib-club">${club || '&nbsp;'}</div>
            <div class="bib-foot">${meet.host ? `Host: ${escapeHtml(meet.host)}` : '&nbsp;'}</div>
        </section>`;
}

/**
 * What to print or export for 'cards' or 'bibs': the selected starter, or all
 * starters matching the search, in print order; for cards also their starts.
 */
async function loadPrintout(kind) {
    const selected = document.getElementById(`${kind}-starter`).value;
    const query = document.getElementById(`${kind}-search`).value;
    const [starters, overview] = await Promise.all([
        loadStarters(),
        kind === 'cards' ? api('/rmgmt/overview') : null,
        refreshMeet()
    ]);
    const chosen = (selected ? starters.filter(c => String(c.id) === selected) : matchingStarters(starters, query))
        .sort(printOrder);
    if (chosen.length === 0) throw new Error('There are no starters to export.');
    const rows = new Map((overview?.rows || []).map(row => [row.competitor?.id, row]));
    return { chosen, rows, printedAt: new Date().toLocaleString() };
}

/** Fills the print area for 'cards' or 'bibs' with the selected starters' pages. */
async function renderPrintout(kind) {
    const { chosen, rows, printedAt } = await loadPrintout(kind);
    const pages = kind === 'cards'
        ? chosen.map(c => startCard(c, rows.get(c.id), printedAt))
        : chosen.map(bib);
    document.getElementById('page-size').textContent = PAGE_SIZES[kind];
    document.getElementById('print-area').innerHTML = pages.join('');
    return chosen.length;
}

async function exportWord(kind) {
    const { chosen, rows, printedAt } = await loadPrintout(kind);
    const file = kind === 'cards'
        ? startCardsDocx(meet, chosen, rows, { printedAt, formatDay })
        : raceBibsDocx(meet, chosen);
    downloadBlob(file, exportFileName(FILE_PREFIXES[kind], 'docx'));
    const what = kind === 'cards' ? 'start card' : 'race bib';
    showMessage(`Exported ${chosen.length} ${what}${chosen.length === 1 ? '' : 's'} as a Word document.`, 'success');
}

// The Export buttons open one dialog; it remembers which printout it is for
let exportKind = null;

function openExportDialog(kind) {
    exportKind = kind;
    document.getElementById('export-title').textContent = EXPORT_TITLES[kind];
    const format = readStored(FORMAT_KEY) === 'word' ? 'word' : 'print';
    document.querySelector(`#export-dialog input[name="export-format"][value="${format}"]`).checked = true;
    document.getElementById('export-dialog').showModal();
}

async function exportFromDialog() {
    const kind = exportKind;
    const format = document.querySelector('#export-dialog input[name="export-format"]:checked')?.value || 'print';
    store(FORMAT_KEY, format);
    const button = document.getElementById(`print-${kind}-btn`);
    button.disabled = true;
    try {
        if (format === 'word') {
            await exportWord(kind);
        } else {
            await renderPrintout(kind);
            window.print();
        }
    } catch (error) {
        showMessage(`Could not export: ${error.message}`);
    } finally {
        button.disabled = false;
    }
}

// --- lane assignments CSV --------------------------------------------------

function readStored(key) {
    try { return localStorage.getItem(key); } catch { return null; }
}

function store(key, value) {
    try { localStorage.setItem(key, value); } catch { /* private mode: not remembered */ }
}

async function exportLanes(button) {
    const separator = document.getElementById('csv-separator').value;
    store(SEPARATOR_KEY, separator);
    button.disabled = true;
    try {
        const [overview, relays, competitors, disciplines] = await Promise.all([
            api('/rmgmt/overview'), api('/rmgmt'), api('/competitors'), api('/available-disciplines'), refreshMeet()
        ]);
        const rows = laneRows({
            overview, relays, competitors, disciplines, meet,
            includeUnscheduled: document.getElementById('csv-unscheduled').checked
        });
        if (rows.length === 0) throw new Error('There are no lane assignments yet.');
        downloadBlob(new Blob([toCsv(rows, separator)], { type: 'text/csv;charset=utf-8' }), exportFileName('lane-assignments', 'csv'));
        const lanes = rows.filter(row => row.scheduled).length;
        showMessage(`Exported ${lanes} lane assignment${lanes === 1 ? '' : 's'}`
            + (rows.length > lanes ? ` and ${rows.length - lanes} start${rows.length - lanes === 1 ? '' : 's'} without a lane` : '')
            + '.', 'success');
    } catch (error) {
        showMessage(`Could not export the lane assignments: ${error.message}`);
    } finally {
        button.disabled = false;
    }
}

// Exposed for checking the layout on screen: meetPrintPreview('bibs'), meetPrintPreview(null)
window.meetPrintPreview = async (kind) => {
    document.body.classList.toggle('print-preview', Boolean(kind));
    if (kind) return renderPrintout(kind);
    document.getElementById('print-area').innerHTML = '';
    return 0;
};

document.addEventListener('DOMContentLoaded', async () => {
    document.getElementById('meet-form').addEventListener('submit', saveMeet);
    document.getElementById('print-cards-btn').addEventListener('click', () => openExportDialog('cards'));
    document.getElementById('print-bibs-btn').addEventListener('click', () => openExportDialog('bibs'));
    // The dialog's form closes it; its Export button also exports
    document.querySelector('#export-dialog form').addEventListener('submit', event => {
        if (event.submitter?.value === 'export') exportFromDialog();
    });
    document.getElementById('export-lanes-btn').addEventListener('click', e => exportLanes(e.currentTarget));
    document.querySelectorAll('.starter-search').forEach(search => {
        search.addEventListener('input', () => renderStarterSelect(search));
    });
    const separator = document.getElementById('csv-separator');
    separator.value = readStored(SEPARATOR_KEY) === 'comma' ? 'comma' : 'semicolon';
    window.addEventListener('afterprint', () => {
        if (!document.body.classList.contains('print-preview')) {
            document.getElementById('print-area').innerHTML = '';
        }
    });

    try {
        await Promise.all([refreshMeet(), refreshStarterSelects()]);
    } catch (error) {
        showMessage(`Error loading the meet: ${error.message}`);
    }
});
