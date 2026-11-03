// Add several competitors at once, e.g. a whole club: one row per starter,
// the rankings typed as short names (WHITO COLT), ticked in a matrix, or
// pasted from a spreadsheet. A row keeps the ranking entries it picked by
// their ids (ranking id and entry); the short names are only what is shown
// and typed, and saving uses the entries' discipline ids. "Add all" saves each row the way the single
// form does (POST /api/competitors) and then adds its starts the way the
// Starts section does (POST /api/competitors/{id}/starts), one discipline at
// a time. Rows that could not be saved stay in the table with the reason.

import { addStart, loadCompetitors, saveCompetitor } from '../api.js';
import { getCountries } from '../countries.js';
import { escapeHtml } from '../meet.js';
import { categoryLabel } from '../teamRanking.js';
import { t } from '../i18n.js';
import { hideElement, showElement } from '../utils.js';
import { withButtonFeedback } from '../buttonFeedback.js';
import { renderCompetitors } from './competitors.js';

const COUNTRIES = getCountries();
const GENDERS = ['male', 'female', 'other'];

const state = {
    view: 'table',
    rows: [],
    nextKey: 1,
    defaults: { club: '', country: '' },
    entries: new Map(),  // entry key "rankingId:index" -> { key, code, ranking, entry, kind, category, label, disciplineIds }
    byCode: new Map(),   // a short name as typed (normalised, see codeKey) -> entry key
    disciplines: new Map(),
    registered: new Set() // names of the competitors already registered, lower case
};

const $ = (id) => document.getElementById(id);

// --- data ----------------------------------------------------------------

async function getJson(path) {
    const response = await fetch(`/api${path}`);
    if (!response.ok) throw new Error(t('common.loadFailed', { path, status: response.status }));
    return response.json();
}

const disciplineName = (id) => state.disciplines.get(id)?.name ?? `#${id}`;

function rankingCategory(ranking) {
    const ids = ranking.entries.flatMap(e => e.discipline_ids);
    return [...new Set(ids.map(id => state.disciplines.get(id)?.category).filter(Boolean))].sort().join(' / ');
}

/** A short name for display: upper case, without spaces ("VET O" -> VETO). */
const compact = (text) => String(text || '').trim().toUpperCase().replace(/\s+/g, '');
/** How a typed short name is matched: case, spaces and underscores do not matter (vet_o = VETO). */
const codeKey = (text) => compact(text).replace(/_/g, '');

/**
 * The short name shown for each ranking entry, the most specific one there is:
 * the entry's own label (REMO, SCHULRS), for a ranking with one entry its short
 * name (COLT, VETO), for an entry of one discipline that discipline's short name
 * (PENO, PENR, MEIXRS); only if none is free the ranking's short name with the
 * entry's number. Each name is unique; the entry itself is known by its ids.
 */
function buildEntries(rankings) {
    state.entries.clear();
    state.byCode.clear();
    rankings.forEach((ranking, r) => {
        const kind = ranking.entries.length > 1 ? 'choice' : (ranking.entries[0].discipline_ids.length > 1 ? 'combo' : 'single');
        const category = rankingCategory(ranking);
        const base = compact(ranking.short_name || ranking.name);
        ranking.entries.forEach((entry, i) => {
            const key = `${ranking.id ?? `r${r}`}:${i}`;
            const ids = entry.discipline_ids;
            const candidates = [
                entry.label,
                ranking.entries.length === 1 ? base : null,
                ids.length === 1 ? state.disciplines.get(ids[0])?.short_name : null
            ].map(compact).filter(Boolean);
            let code = candidates.find(c => !state.byCode.has(codeKey(c)));
            for (let n = i + 1; !code; n++) {
                if (!state.byCode.has(codeKey(`${base}${n}`))) code = `${base}${n}`;
            }
            const label = kind === 'choice' ? `${ranking.name} – ${ids.map(disciplineName).join(' + ')}` : ranking.name;
            state.entries.set(key, { key, code, ranking, entry: i, kind, category, label, disciplineIds: ids });
            state.byCode.set(codeKey(code), key);
        });
    });
}

// Read fresh each time the panel opens: rankings and competitors may have changed meanwhile
async function load() {
    const [config, catalog, competitors] = await Promise.all([
        getJson('/rankings'),
        getJson('/available-disciplines'),
        getJson('/competitors').catch(() => [])
    ]);
    state.disciplines = new Map((catalog || []).map(d => [d.id, d]));
    buildEntries((config.rankings || [])
        .filter(r => String(r.level || 'individual').toLowerCase() !== 'team')
        .filter(r => (r.entries || []).length));
    state.registered = new Set((competitors || []).filter(c => c.name).map(c => c.name.trim().toLowerCase()));
}

// --- rows ----------------------------------------------------------------

function newRow(values = {}) {
    return {
        key: state.nextKey++,
        name: '', gender: '', year: '',
        club: state.defaults.club, country: state.defaults.country,
        countryText: '', // a pasted country that matched none, shown as an error
        picks: [],       // entry keys ("69:0"); a typed short name that matches none is kept as "?XYZ"
        error: '',       // why saving this row failed
        ...values
    };
}

const rowByKey = (key) => state.rows.find(r => r.key === Number(key));
const isEmpty = (row) => !row.name.trim() && !row.picks.length;
const isUnknown = (pick) => !state.entries.has(pick);
/** What a pick shows: the entry's short name, or the text typed. */
const pickCode = (pick) => state.entries.get(pick)?.code ?? pick.replace(/^\?/, '');
const filledRows = () => state.rows.filter(r => !isEmpty(r));

/** The disciplines a row starts in: every discipline of every entry picked, combined rankings expanded. */
function rowStarts(row) {
    const ids = new Set();
    row.picks.forEach(pick => state.entries.get(pick)?.disciplineIds.forEach(id => ids.add(id)));
    return ids;
}

// Only the name is required, as in the single form
function rowIssues(row) {
    const errors = [];
    const warnings = [];
    if (isEmpty(row)) return { errors, warnings };
    const name = row.name.trim().toLowerCase();
    if (row.error) errors.push(row.error);
    if (!name) errors.push(t('bulk.issue.noName'));
    // The same range as the single form's year field
    const year = String(row.year).trim();
    if (year && !(Number(year) >= 1900 && Number(year) <= new Date().getFullYear())) errors.push(t('bulk.issue.year', { max: new Date().getFullYear() }));
    if (row.countryText) errors.push(t('bulk.issue.unknownCountry', { country: row.countryText }));
    const unknown = row.picks.filter(isUnknown).map(pickCode);
    if (unknown.length) errors.push(t('bulk.issue.unknownCodes', { codes: unknown.join(', ') }));
    if (!row.picks.length) warnings.push(t('bulk.issue.noRankings'));
    if (name && state.rows.some(r => r !== row && r.name.trim().toLowerCase() === name)) warnings.push(t('bulk.issue.duplicate'));
    if (name && state.registered.has(name)) warnings.push(t('bulk.issue.registered'));
    return { errors, warnings };
}

/** Adds the entries for typed short names (separated by spaces, commas or semicolons). */
function addCodes(row, text) {
    String(text).split(/[\s,;]+/).map(c => c.trim()).filter(Boolean).forEach(code => {
        const pick = state.byCode.get(codeKey(code)) ?? `?${compact(code)}`;
        if (!row.picks.includes(pick)) row.picks.push(pick);
    });
}

function togglePick(row, key) {
    row.picks = row.picks.includes(key) ? row.picks.filter(p => p !== key) : [...row.picks, key];
}

/** Keeps an empty row at the end, ready for the next starter. */
function ensureBlankRow() {
    if (!state.rows.length || !isEmpty(state.rows[state.rows.length - 1])) state.rows.push(newRow());
}

// --- table view ------------------------------------------------------------

function chips(row) {
    return row.picks.map(pick => {
        const entry = state.entries.get(pick);
        const code = pickCode(pick);
        const cls = !entry ? 'is-unknown' : entry.kind === 'combo' ? 'is-combo' : '';
        const title = entry ? entry.label : t('bulk.issue.unknownCode');
        return `<span class="bulk-chip ${cls}" title="${escapeHtml(title)}">${escapeHtml(code)}<button type="button" data-remove-pick="${escapeHtml(pick)}" data-row="${row.key}" aria-label="${escapeHtml(t('bulk.removeCode', { code }))}">×</button></span>`;
    }).join('');
}

function noteHtml(row) {
    const { errors, warnings } = rowIssues(row);
    const starts = rowStarts(row).size;
    return `${errors.map(e => `<div class="bulk-note text-red-700">${escapeHtml(e)}</div>`).join('')}
        ${warnings.map(w => `<div class="bulk-note text-amber-700">${escapeHtml(w)}</div>`).join('')}
        ${!isEmpty(row) && !errors.length && !warnings.length ? `<div class="bulk-note text-green-700">✓</div>` : ''}
        ${starts ? `<div class="bulk-note text-gray-500">${escapeHtml(t('bulk.startCount', { count: starts }))}</div>` : ''}`;
}

function rowClass(row) {
    const { errors, warnings } = rowIssues(row);
    return errors.length ? 'has-error' : warnings.length ? 'has-warning' : '';
}

function rowHtml(row, index) {
    const countryOptions = COUNTRIES.map(c => `<option value="${escapeHtml(c.value)}" ${row.country === c.value ? 'selected' : ''}>${escapeHtml(c.label)}</option>`).join('');
    const genderOptions = GENDERS.map(g => `<option value="${g}" ${row.gender === g ? 'selected' : ''}>${escapeHtml(t(`gender.${g}`))}</option>`).join('');
    return `
        <tr data-row="${row.key}" class="${rowClass(row)}">
            <td class="num">${index + 1}</td>
            <td><input type="text" data-row="${row.key}" data-field="name" value="${escapeHtml(row.name)}" placeholder="${escapeHtml(t('bulk.namePlaceholder'))}" aria-label="${escapeHtml(t('competitors.name'))}" class="min-w-[9rem]"></td>
            <td><select data-row="${row.key}" data-field="gender" aria-label="${escapeHtml(t('competitors.gender'))}" class="min-w-[7rem]"><option value="">–</option>${genderOptions}</select></td>
            <td><input type="number" min="1900" max="${new Date().getFullYear()}" step="1" data-row="${row.key}" data-field="year" value="${escapeHtml(row.year)}" aria-label="${escapeHtml(t('competitors.yearOfBirth'))}" class="w-20"></td>
            <td><input type="text" data-row="${row.key}" data-field="club" value="${escapeHtml(row.club)}" aria-label="${escapeHtml(t('competitors.club'))}" class="min-w-[9rem]"></td>
            <td><select data-row="${row.key}" data-field="country" aria-label="${escapeHtml(t('competitors.country'))}" class="min-w-[8rem]"><option value="">–</option>${countryOptions}</select></td>
            <td><div class="bulk-tokens" data-tokens="${row.key}">${chips(row)}<input type="text" list="bulk-codes" data-row="${row.key}" data-field="codes" placeholder="${row.picks.length ? '' : escapeHtml(t('bulk.codesPlaceholder'))}" aria-label="${escapeHtml(t('bulk.col.rankings'))}" autocomplete="off"></div></td>
            <td class="min-w-[9rem]" data-note="${row.key}">${noteHtml(row)}</td>
            <td class="whitespace-nowrap">
                <button type="button" data-copy="${row.key}" class="text-gray-500 hover:text-blue-700 px-1 text-sm" title="${escapeHtml(t('bulk.copyRowTitle'))}">${escapeHtml(t('bulk.copyRow'))}</button>
                <button type="button" data-delete="${row.key}" class="text-gray-400 hover:text-red-600 px-1" aria-label="${escapeHtml(t('bulk.deleteRow'))}" title="${escapeHtml(t('bulk.deleteRow'))}">×</button>
            </td>
        </tr>`;
}

function tableView() {
    return `
        <div class="bulk-scroll">
            <table class="bulk-table">
                <thead><tr>
                    <th class="num">#</th>
                    <th>${escapeHtml(t('competitors.name'))} *</th>
                    <th>${escapeHtml(t('competitors.gender'))}</th>
                    <th>${escapeHtml(t('bulk.col.year'))}</th>
                    <th>${escapeHtml(t('competitors.club'))}</th>
                    <th>${escapeHtml(t('competitors.country'))}</th>
                    <th>${escapeHtml(t('bulk.col.rankings'))}</th>
                    <th>${escapeHtml(t('bulk.col.check'))}</th>
                    <th></th>
                </tr></thead>
                <tbody>${state.rows.map(rowHtml).join('')}</tbody>
            </table>
        </div>
        <datalist id="bulk-codes">${[...state.entries.values()].map(en => `<option value="${escapeHtml(en.code)}">${escapeHtml(en.label)}</option>`).join('')}</datalist>`;
}

/** Redraws one row's chips, check column and marker without touching the field being typed in. */
function refreshRow(row, { focusCodes = false } = {}) {
    const tr = document.querySelector(`#bulk-grid tr[data-row="${row.key}"]`);
    if (!tr) return;
    tr.className = rowClass(row);
    document.querySelector(`#bulk-grid [data-note="${row.key}"]`).innerHTML = noteHtml(row);
    const box = document.querySelector(`#bulk-grid [data-tokens="${row.key}"]`);
    const input = box.querySelector('input');
    box.querySelectorAll('.bulk-chip').forEach(chip => chip.remove());
    input.insertAdjacentHTML('beforebegin', chips(row));
    input.placeholder = row.picks.length ? '' : t('bulk.codesPlaceholder');
    if (focusCodes) input.focus();
}

/** Other rows may change too (duplicate names), so their markers are refreshed as well. */
function refreshAllNotes() {
    state.rows.forEach(row => {
        const tr = document.querySelector(`#bulk-grid tr[data-row="${row.key}"]`);
        if (!tr) return;
        tr.className = rowClass(row);
        document.querySelector(`#bulk-grid [data-note="${row.key}"]`).innerHTML = noteHtml(row);
    });
}

function focusField(key, field) {
    const el = document.querySelector(`#bulk-grid [data-row="${key}"][data-field="${field}"]`);
    if (el) { el.focus(); el.select?.(); }
}

// --- matrix view -------------------------------------------------------------

function matrixView() {
    const groups = [];
    [...state.entries.values()].forEach(en => {
        let group = groups.find(g => g.category === en.category);
        if (!group) groups.push(group = { category: en.category, entries: [] });
        group.entries.push(en);
    });
    const ordered = groups.flatMap(g => g.entries);
    const firstOfGroup = new Set(groups.map(g => g.entries[0].key));
    const rows = state.rows.filter(r => r.name.trim());

    if (!rows.length) return `<p class="text-sm text-gray-500 py-4">${escapeHtml(t('bulk.matrixEmpty'))}</p>`;

    const viaCombo = (row, tk) => tk.kind !== 'combo' && row.picks.some(pick => {
        const other = state.entries.get(pick);
        return other?.kind === 'combo' && tk.disciplineIds.every(id => other.disciplineIds.includes(id));
    });

    return `
        <div class="bulk-scroll" id="bulk-matrix-scroll">
            <table class="bulk-table bulk-matrix">
                <thead>
                    <tr>
                        <th class="sticky-col"></th>
                        ${groups.map(g => `<th class="cat" colspan="${g.entries.length}">${escapeHtml(g.category ? categoryLabel(g.category) : t('common.unknown'))}</th>`).join('')}
                        <th></th>
                    </tr>
                    <tr>
                        <th class="sticky-col">${escapeHtml(t('competitors.name'))}</th>
                        ${ordered.map(tk => `<th class="rot ${firstOfGroup.has(tk.key) ? 'first-of-cat' : ''}" title="${escapeHtml(tk.label)}"><span>${escapeHtml(tk.code)}${tk.kind === 'combo' ? ' Σ' : ''}</span></th>`).join('')}
                        <th>${escapeHtml(t('bulk.col.starts'))}</th>
                    </tr>
                </thead>
                <tbody>${rows.map(row => `
                    <tr class="${rowClass(row)}">
                        <td class="sticky-col font-medium">${escapeHtml(row.name)}<div class="bulk-note text-gray-500">${escapeHtml(row.club || '')}</div></td>
                        ${ordered.map(tk => {
                            const on = row.picks.includes(tk.key);
                            const via = !on && viaCombo(row, tk);
                            return `<td class="cell ${on ? 'is-on' : via ? 'is-via' : ''} ${firstOfGroup.has(tk.key) ? 'first-of-cat' : ''}">
                                <label title="${escapeHtml(`${row.name} · ${tk.label}`)}"><input type="checkbox" data-matrix-row="${row.key}" data-pick="${escapeHtml(tk.key)}" ${on ? 'checked' : ''} class="h-4 w-4"></label></td>`;
                        }).join('')}
                        <td class="text-center tabular-nums">${rowStarts(row).size}</td>
                    </tr>`).join('')}
                </tbody>
                <tfoot><tr>
                    <td class="sticky-col text-left">${escapeHtml(t('bulk.perRanking'))}</td>
                    ${ordered.map(tk => `<td class="${firstOfGroup.has(tk.key) ? 'first-of-cat' : ''}">${rows.filter(r => r.picks.includes(tk.key)).length || ''}</td>`).join('')}
                    <td></td>
                </tr></tfoot>
            </table>
        </div>`;
}

// --- totals, rendering -------------------------------------------------------

function renderTotals() {
    const rows = filledRows();
    const starts = rows.reduce((sum, row) => sum + rowStarts(row).size, 0);
    const errors = rows.filter(r => rowIssues(r).errors.length).length;
    const warnings = rows.filter(r => !rowIssues(r).errors.length && rowIssues(r).warnings.length).length;
    $('bulk-totals').innerHTML = `
        <span><strong>${rows.length}</strong> ${escapeHtml(t('bulk.totals.starters', { count: rows.length }))}</span>
        <span><strong>${starts}</strong> ${escapeHtml(t('bulk.totals.starts', { count: starts }))}</span>
        ${errors ? `<span class="text-red-700">${escapeHtml(t('bulk.totals.errors', { count: errors }))}</span>` : ''}
        ${warnings ? `<span class="text-amber-700">${escapeHtml(t('bulk.totals.warnings', { count: warnings }))}</span>` : ''}
        ${errors ? `<span class="text-gray-500">${escapeHtml(t('bulk.totals.fixFirst'))}</span>` : ''}`;
    // Rows that failed to save carry their error and can be saved again once fixed
    const blocking = rows.filter(r => rowIssues({ ...r, error: '' }).errors.length).length;
    $('bulk-save').disabled = !rows.length || blocking > 0;
}

function render() {
    ensureBlankRow();
    document.querySelectorAll('#bulk-form [data-view]').forEach(b => b.setAttribute('aria-pressed', b.dataset.view === state.view));
    $('bulk-view-hint').textContent = t(state.view === 'table' ? 'bulk.view.tableHint' : 'bulk.view.matrixHint');
    const scroll = $('bulk-matrix-scroll')?.scrollLeft ?? 0;
    $('bulk-grid').innerHTML = state.view === 'table' ? tableView() : matrixView();
    $('bulk-no-rankings').classList.toggle('hidden', state.entries.size > 0);
    const matrix = $('bulk-matrix-scroll');
    if (matrix) matrix.scrollLeft = scroll;
    $('bulk-add-row').hidden = state.view !== 'table';
    $('bulk-add-rows').hidden = state.view !== 'table';
    renderTotals();
}

function renderCodeList() {
    // The short name takes the width it needs, so long names never run into their description
    $('bulk-code-list').innerHTML = [...state.entries.values()].map(en => `
        <div class="flex gap-3 items-baseline"><span class="font-mono font-semibold whitespace-nowrap min-w-[4.5rem]">${escapeHtml(en.code)}</span><span class="text-gray-600 min-w-0">${escapeHtml(en.label)}${en.kind === 'combo' ? ` (${escapeHtml(en.disciplineIds.map(disciplineName).join(' + '))})` : ''}</span></div>`).join('');
}

// --- saving ------------------------------------------------------------------

const errorText = (error) => error?.body?.error || error?.message || t('competitors.error.save');

/**
 * Saves one row: the competitor as the single form does, then each start as the
 * Starts section does. Returns what happened; throws only if the competitor
 * itself could not be created.
 */
async function saveRow(row) {
    const competitor = await saveCompetitor({
        name: row.name.trim(),
        gender: row.gender,
        year_of_birth: row.year,
        club: row.club.trim(),
        country: row.country
    });
    const failed = [];
    let added = 0;
    for (const disciplineId of rowStarts(row)) {
        try {
            await addStart(competitor.id, disciplineId, null);
            added++;
        } catch (error) {
            failed.push(`${disciplineName(disciplineId)}: ${errorText(error)}`);
        }
    }
    return { name: competitor.name || row.name.trim(), added, failed };
}

async function saveAll() {
    const rows = filledRows();
    const done = [];
    for (const row of rows) {
        row.error = '';
        try {
            done.push(await saveRow(row));
            state.rows = state.rows.filter(r => r !== row);
        } catch (error) {
            row.error = t('bulk.saveFailed', { error: errorText(error) });
        }
    }
    await loadCompetitors();
    renderCompetitors();
    await load().catch(() => {}); // the names just added count as registered
    render();
    showResult(done, rows.length - done.length);
}

function showResult(done, failedRows) {
    const failedStarts = done.filter(d => d.failed.length);
    const starts = done.reduce((sum, d) => sum + d.added, 0);
    const parts = [t('bulk.result.saved', { starters: done.length, starts })];
    if (failedRows) parts.push(t('bulk.result.rowsFailed', { count: failedRows }));
    $('bulk-result').innerHTML = !done.length && !failedRows ? '' : `
        <div class="px-4 py-3 rounded-md border text-sm space-y-1 ${failedRows || failedStarts.length ? 'bg-amber-50 border-amber-200 text-amber-900' : 'bg-green-50 border-green-200 text-green-900'}">
            <p class="font-medium">${escapeHtml(parts.join(' · '))}</p>
            ${failedStarts.map(d => `<p>${escapeHtml(t('bulk.result.startsFailed', { name: d.name }))} ${d.failed.map(escapeHtml).join('; ')}</p>`).join('')}
        </div>`;
}

// A problem that concerns the whole panel (loading failed, saving stopped), shown in it
function showProblem(text) {
    $('bulk-result').innerHTML = `<div class="px-4 py-3 rounded-md border text-sm bg-red-50 border-red-200 text-red-800">${escapeHtml(text)}</div>`;
}

// --- paste -----------------------------------------------------------------

function parseGender(value) {
    const v = String(value || '').trim().toLowerCase();
    if (!v) return '';
    if (['m', 'male', 'männlich', 'mann', 'h', 'herr'].includes(v)) return 'male';
    if (['w', 'f', 'female', 'weiblich', 'frau', 'd.', 'dame'].includes(v)) return 'female';
    return 'other';
}

function parseCountry(value) {
    const v = String(value || '').trim().toLowerCase();
    if (!v) return { country: state.defaults.country, countryText: '' };
    const match = COUNTRIES.find(c => c.value.toLowerCase() === v || c.label.toLowerCase() === v);
    return match ? { country: match.value, countryText: '' } : { country: '', countryText: String(value).trim() };
}

function pasteRows(text) {
    const lines = String(text).split(/\r?\n/).filter(line => line.trim());
    const parsed = [];
    lines.forEach((line, i) => {
        const cells = (line.includes('\t') ? line.split('\t') : line.split(';')).map(c => c.trim());
        if (i === 0 && /^name$/i.test(cells[0])) return; // header line
        const row = newRow({
            name: cells[0] || '',
            gender: parseGender(cells[1]),
            year: (cells[2] || '').replace(/\D/g, ''),
            club: cells[3] || state.defaults.club,
            ...parseCountry(cells[4])
        });
        addCodes(row, cells.slice(5).join(' '));
        parsed.push(row);
    });
    state.rows = state.rows.filter(r => !isEmpty(r)).concat(parsed);
    return parsed.length;
}

// --- open and close ------------------------------------------------------------

export async function showBulkForm() {
    showElement('bulk-form');
    $('bulk-result').innerHTML = '';
    try {
        await load();
    } catch (error) {
        showProblem(t('common.loadError', { error: error.message }));
    }
    renderCodeList();
    render();
    focusField(state.rows[0].key, 'name');
}

export function hideBulkForm() {
    hideElement('bulk-form');
}

// --- events ------------------------------------------------------------------

export function setupBulkAddEventListeners() {
    const grid = $('bulk-grid');
    if (!grid) return;

    $('bulk-default-country').innerHTML = `<option value="">${escapeHtml(t('competitors.form.selectCountry'))}</option>`
        + COUNTRIES.map(c => `<option value="${escapeHtml(c.value)}">${escapeHtml(c.label)}</option>`).join('');

    grid.addEventListener('input', (e) => {
        const el = e.target;
        const row = rowByKey(el.dataset.row);
        if (!row || !el.dataset.field || el.dataset.field === 'codes') return;
        row[el.dataset.field] = el.value;
        row.error = '';
        if (el.dataset.field === 'country') row.countryText = '';
        const wasLast = row === state.rows[state.rows.length - 1];
        refreshAllNotes();
        renderTotals();
        // Typing in the empty last row opens the next one, so there is always a row to continue in
        if (wasLast && !isEmpty(row)) {
            state.rows.push(newRow());
            document.querySelector('#bulk-grid tbody').insertAdjacentHTML('beforeend', rowHtml(state.rows[state.rows.length - 1], state.rows.length - 1));
        }
    });

    grid.addEventListener('keydown', (e) => {
        const el = e.target;
        const row = rowByKey(el.dataset.row);
        if (!row || !el.dataset.field) return;
        if (el.dataset.field === 'codes') {
            if (['Enter', ' ', ','].includes(e.key) && el.value.trim()) {
                e.preventDefault();
                addCodes(row, el.value);
                el.value = '';
                refreshRow(row, { focusCodes: true });
                refreshAllNotes();
                renderTotals();
                return;
            }
            if (e.key === 'Backspace' && !el.value && row.picks.length) {
                row.picks.pop();
                refreshRow(row, { focusCodes: true });
                renderTotals();
                return;
            }
        }
        // Enter moves down to the same column, like in a spreadsheet
        if (e.key === 'Enter') {
            e.preventDefault();
            const index = state.rows.indexOf(row);
            if (index === state.rows.length - 1) { state.rows.push(newRow()); render(); }
            focusField(state.rows[index + 1].key, el.dataset.field);
        }
    });

    // A short name typed but not confirmed is taken when leaving the field
    grid.addEventListener('focusout', (e) => {
        const el = e.target;
        if (el.dataset?.field !== 'codes' || !el.value.trim()) return;
        const row = rowByKey(el.dataset.row);
        addCodes(row, el.value);
        el.value = '';
        refreshRow(row);
        refreshAllNotes();
        renderTotals();
    });

    grid.addEventListener('click', (e) => {
        const button = e.target.closest('button');
        if (button?.dataset.removePick) {
            const row = rowByKey(button.dataset.row);
            row.picks = row.picks.filter(p => p !== button.dataset.removePick);
            refreshRow(row, { focusCodes: true });
            refreshAllNotes();
            renderTotals();
        } else if (button?.dataset.copy) {
            // Copies everything but the name, for family members or club mates with the same rankings
            const row = rowByKey(button.dataset.copy);
            const copy = newRow({ club: row.club, country: row.country, picks: [...row.picks] });
            state.rows.splice(state.rows.indexOf(row) + 1, 0, copy);
            render();
            focusField(copy.key, 'name');
        } else if (button?.dataset.delete) {
            state.rows = state.rows.filter(r => r.key !== Number(button.dataset.delete));
            render();
        } else if (e.target.closest('[data-tokens]') && !button) {
            e.target.closest('[data-tokens]').querySelector('input').focus();
        }
    });

    // Several lines or cells pasted into a field (copied from Excel) become rows, as in the paste panel
    grid.addEventListener('paste', (e) => {
        const text = e.clipboardData?.getData('text') || '';
        if (!/[\t\n]/.test(text.trim())) return;
        e.preventDefault();
        pasteRows(text);
        render();
    });

    grid.addEventListener('change', (e) => {
        const el = e.target;
        if (el.dataset.matrixRow) {
            togglePick(rowByKey(el.dataset.matrixRow), el.dataset.pick);
            render();
        }
    });

    $('bulk-view-switch').addEventListener('click', (e) => {
        const button = e.target.closest('[data-view]');
        if (!button) return;
        state.view = button.dataset.view;
        render();
    });

    // The empty last row is the new one; only when it is used does another one come
    $('bulk-add-row').addEventListener('click', () => {
        render();
        focusField(state.rows[state.rows.length - 1].key, 'name');
    });
    $('bulk-add-rows').addEventListener('click', () => {
        for (let i = 0; i < 5; i++) state.rows.push(newRow());
        render();
    });

    $('bulk-default-club').addEventListener('input', (e) => { state.defaults.club = e.target.value; });
    $('bulk-default-country').addEventListener('change', (e) => { state.defaults.country = e.target.value; });
    $('bulk-apply-defaults').addEventListener('click', () => {
        state.rows.forEach(row => {
            if (!row.club) row.club = state.defaults.club;
            if (!row.country && !row.countryText) row.country = state.defaults.country;
        });
        render();
    });

    $('bulk-paste-apply').addEventListener('click', () => {
        const area = $('bulk-paste-text');
        const count = pasteRows(area.value);
        $('bulk-paste-result').textContent = t('bulk.paste.added', { count });
        if (count) area.value = '';
        render();
    });

    $('bulk-save').addEventListener('click', (e) => {
        withButtonFeedback(e.currentTarget, saveAll).catch(error => showProblem(errorText(error)));
    });
    $('bulk-close').addEventListener('click', hideBulkForm);
    $('bulk-cancel').addEventListener('click', hideBulkForm);
}

$('add-several-btn')?.addEventListener('click', showBulkForm);
setupBulkAddEventListeners();
