// Meet page: the meet's name, venue, host and dates, and the printouts made
// from them: a start card per starter (A4 portrait) and a race bib per
// starter (A4 landscape).

import { escapeHtml, formatDateRange, loadMeet } from '../js/meet.js';

const PAGE_SIZES = {
    cards: '@page { size: A4 portrait; margin: 12mm; }',
    bibs: '@page { size: A4 landscape; margin: 10mm; }'
};

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

async function refreshStarterSelects() {
    const starters = await loadStarters();
    document.querySelectorAll('.starter-select').forEach(select => {
        const previous = select.value;
        select.innerHTML = `<option value="">All starters (${starters.length})</option>` + starters
            .map(c => `<option value="${c.id}">${c.id} · ${escapeHtml(c.name)}${c.club ? ` (${escapeHtml(c.club)})` : ''}</option>`)
            .join('');
        select.value = [...select.options].some(o => o.value === previous) ? previous : '';
    });
    document.querySelectorAll('.print-btn').forEach(button => { button.disabled = starters.length === 0; });
}

// --- printouts -------------------------------------------------------------

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

/** Fills the print area for 'cards' or 'bibs' with the selected starters' pages. */
async function renderPrintout(kind) {
    const selected = document.getElementById(kind === 'cards' ? 'cards-starter' : 'bibs-starter').value;
    const [starters, overview] = await Promise.all([
        loadStarters(),
        kind === 'cards' ? api('/rmgmt/overview') : null,
        refreshMeet()
    ]);
    const chosen = selected ? starters.filter(c => String(c.id) === selected) : starters;
    if (chosen.length === 0) throw new Error('There are no starters to print.');

    let pages;
    if (kind === 'cards') {
        const rows = new Map((overview?.rows || []).map(row => [row.competitor?.id, row]));
        const printedAt = new Date().toLocaleString();
        pages = chosen.map(c => startCard(c, rows.get(c.id), printedAt));
    } else {
        pages = chosen.map(bib);
    }
    document.getElementById('page-size').textContent = PAGE_SIZES[kind];
    document.getElementById('print-area').innerHTML = pages.join('');
    return chosen.length;
}

async function printPages(kind, button) {
    button.disabled = true;
    try {
        await renderPrintout(kind);
        window.print();
    } catch (error) {
        showMessage(`Could not prepare the printout: ${error.message}`);
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
    document.getElementById('print-cards-btn').addEventListener('click', e => printPages('cards', e.currentTarget));
    document.getElementById('print-bibs-btn').addEventListener('click', e => printPages('bibs', e.currentTarget));
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
