// Bulk registration demo: many starters and their starts in one go, e.g. a
// whole club. Starters are rows; their rankings are typed as short names
// (WHITO COLT), ticked in a matrix, or pasted from a spreadsheet. It reads
// the rankings, the discipline catalog and the registered competitors and
// never writes anything: "Register all" only shows what would be created.

import { escapeHtml } from '../js/meet.js';
import { categoryLabel } from '../js/teamRanking.js';
import { getCountries } from '../js/countries.js';
import { t } from '../js/i18n.js';

document.title = `${t('bulk.title')} · MLAICC Manager`;

const COUNTRIES = getCountries();
const GENDERS = ['male', 'female', 'other'];

const state = {
    view: 'table',
    rows: [],
    nextKey: 1,
    defaults: { club: '', country: '' },
    tokens: new Map(),   // short name (upper case) -> { code, ranking, entry }
    disciplines: new Map(),
    registered: new Set() // names of the competitors already registered, lower case
};

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

const toCode = (text) => String(text).toUpperCase().replace(/\s+/g, '');

// One short name per entry, the one a starter knows: the discipline's own (MIQO, MIQR) for an entry of one
// discipline, the entry's label for an entry to choose (REMO), else the ranking's short name (REM O -> REMO).
// The ranking's short name also stands in where a name is wanted twice, numbered for an entry to choose (WHIT1).
function buildTokens(rankings) {
    const kindOf = (ranking) => ranking.entries.length > 1 ? 'choice' : (ranking.entries[0].discipline_ids.length > 1 ? 'combo' : 'single');
    const preferred = (entry, ranking) => {
        const own = entry.discipline_ids.length === 1 ? state.disciplines.get(entry.discipline_ids[0])?.short_name
            : kindOf(ranking) === 'choice' ? entry.label : '';
        return own ? toCode(own) : '';
    };
    const wanted = new Map();
    rankings.forEach(r => r.entries.forEach(e => { const c = preferred(e, r); if (c) wanted.set(c, (wanted.get(c) || 0) + 1); }));
    rankings.forEach(ranking => {
        const base = toCode(ranking.short_name || ranking.name);
        const kind = kindOf(ranking);
        const category = rankingCategory(ranking);
        ranking.entries.forEach((entry, i) => {
            const own = preferred(entry, ranking);
            const code = own && wanted.get(own) === 1 ? own : kind === 'choice' ? `${base}${i + 1}` : base;
            const label = kind === 'choice' ? `${ranking.name}: ${entry.label || entry.discipline_ids.map(disciplineName).join(' + ')}` : ranking.name;
            state.tokens.set(code, { code, ranking, entry: i, kind, category, label, disciplineIds: entry.discipline_ids });
        });
    });
}

async function load() {
    const [config, catalog, competitors] = await Promise.all([
        getJson('/rankings'),
        getJson('/available-disciplines'),
        getJson('/competitors').catch(() => [])
    ]);
    (catalog || []).forEach(d => state.disciplines.set(d.id, d));
    buildTokens((config.rankings || [])
        .filter(r => String(r.level || 'individual').toLowerCase() !== 'team')
        .filter(r => (r.entries || []).length));
    (competitors || []).forEach(c => c.name && state.registered.add(c.name.trim().toLowerCase()));
}

// --- rows ----------------------------------------------------------------

function newRow(values = {}) {
    return {
        key: state.nextKey++,
        name: '', gender: '', year: '',
        club: state.defaults.club, country: state.defaults.country,
        countryText: '', // a pasted country that matched none, shown as an error
        codes: [],
        ...values
    };
}

const rowByKey = (key) => state.rows.find(r => r.key === Number(key));
const isEmpty = (row) => !row.name.trim() && !row.codes.length;
const filledRows = () => state.rows.filter(r => !isEmpty(r));

/** The disciplines a row starts in: every discipline of every known short name, combined rankings expanded. */
function rowStarts(row) {
    const ids = new Set();
    row.codes.forEach(code => state.tokens.get(code)?.disciplineIds.forEach(id => ids.add(id)));
    return ids;
}

function rowIssues(row) {
    const errors = [];
    const warnings = [];
    if (isEmpty(row)) return { errors, warnings };
    const name = row.name.trim().toLowerCase();
    if (!name) errors.push(t('bulk.issue.noName'));
    if (row.countryText) errors.push(t('bulk.issue.unknownCountry', { country: row.countryText }));
    else if (!row.country) errors.push(t('bulk.issue.noCountry'));
    const unknown = row.codes.filter(code => !state.tokens.has(code));
    if (unknown.length) errors.push(t('bulk.issue.unknownCodes', { codes: unknown.join(', ') }));
    if (!row.codes.length) warnings.push(t('bulk.issue.noRankings'));
    if (name && state.rows.some(r => r !== row && r.name.trim().toLowerCase() === name)) warnings.push(t('bulk.issue.duplicate'));
    if (name && state.registered.has(name)) warnings.push(t('bulk.issue.registered'));
    return { errors, warnings };
}

function addCodes(row, text) {
    String(text).split(/[\s,;]+/).map(c => c.trim().toUpperCase()).filter(Boolean)
        .forEach(code => { if (!row.codes.includes(code)) row.codes.push(code); });
}

function toggleCode(row, code) {
    row.codes = row.codes.includes(code) ? row.codes.filter(c => c !== code) : [...row.codes, code];
}

/** Keeps an empty row at the end, ready for the next starter. */
function ensureBlankRow() {
    if (!state.rows.length || !isEmpty(state.rows[state.rows.length - 1])) state.rows.push(newRow());
}

// --- table view ------------------------------------------------------------

function chips(row) {
    return row.codes.map(code => {
        const token = state.tokens.get(code);
        const cls = !token ? 'is-unknown' : token.kind === 'combo' ? 'is-combo' : '';
        const title = token ? token.label : t('bulk.issue.unknownCode');
        return `<span class="bulk-chip ${cls}" title="${escapeHtml(title)}">${escapeHtml(code)}<button type="button" data-remove-code="${escapeHtml(code)}" data-row="${row.key}" aria-label="${escapeHtml(t('bulk.removeCode', { code }))}">×</button></span>`;
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
            <td><input type="number" min="1900" step="1" data-row="${row.key}" data-field="year" value="${escapeHtml(row.year)}" aria-label="${escapeHtml(t('competitors.yearOfBirth'))}" class="w-20"></td>
            <td><input type="text" data-row="${row.key}" data-field="club" value="${escapeHtml(row.club)}" aria-label="${escapeHtml(t('competitors.club'))}" class="min-w-[9rem]"></td>
            <td><select data-row="${row.key}" data-field="country" aria-label="${escapeHtml(t('competitors.country'))}" class="min-w-[8rem]"><option value="">–</option>${countryOptions}</select></td>
            <td><div class="bulk-tokens" data-tokens="${row.key}">${chips(row)}<input type="text" list="bulk-codes" data-row="${row.key}" data-field="codes" placeholder="${row.codes.length ? '' : escapeHtml(t('bulk.codesPlaceholder'))}" aria-label="${escapeHtml(t('bulk.col.rankings'))}" autocomplete="off"></div></td>
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
                    <th>${escapeHtml(t('competitors.country'))} *</th>
                    <th>${escapeHtml(t('bulk.col.rankings'))}</th>
                    <th>${escapeHtml(t('bulk.col.check'))}</th>
                    <th></th>
                </tr></thead>
                <tbody>${state.rows.map(rowHtml).join('')}</tbody>
            </table>
        </div>
        <datalist id="bulk-codes">${[...state.tokens.values()].map(tk => `<option value="${escapeHtml(tk.code)}">${escapeHtml(tk.label)}</option>`).join('')}</datalist>`;
}

/** Redraws one row's chips, check column and marker without touching the field being typed in. */
function refreshRow(row, { focusCodes = false } = {}) {
    const tr = document.querySelector(`#grid tr[data-row="${row.key}"]`);
    if (!tr) return;
    tr.className = rowClass(row);
    document.querySelector(`[data-note="${row.key}"]`).innerHTML = noteHtml(row);
    const box = document.querySelector(`[data-tokens="${row.key}"]`);
    const input = box.querySelector('input');
    box.querySelectorAll('.bulk-chip').forEach(chip => chip.remove());
    input.insertAdjacentHTML('beforebegin', chips(row));
    input.placeholder = row.codes.length ? '' : t('bulk.codesPlaceholder');
    if (focusCodes) input.focus();
}

/** Other rows may change too (duplicate names), so their markers are refreshed as well. */
function refreshAllNotes() {
    state.rows.forEach(row => {
        const tr = document.querySelector(`#grid tr[data-row="${row.key}"]`);
        if (!tr) return;
        tr.className = rowClass(row);
        document.querySelector(`[data-note="${row.key}"]`).innerHTML = noteHtml(row);
    });
}

function focusField(key, field) {
    const el = document.querySelector(`#grid [data-row="${key}"][data-field="${field}"]`);
    if (el) { el.focus(); el.select?.(); }
}

// --- matrix view -------------------------------------------------------------

function matrixView() {
    const tokens = [...state.tokens.values()];
    const groups = [];
    tokens.forEach(tk => {
        let group = groups.find(g => g.category === tk.category);
        if (!group) groups.push(group = { category: tk.category, tokens: [] });
        group.tokens.push(tk);
    });
    const ordered = groups.flatMap(g => g.tokens);
    const firstOfGroup = new Set(groups.map(g => g.tokens[0].code));
    const rows = state.rows.filter(r => r.name.trim());

    if (!rows.length) return `<p class="text-sm text-gray-500 py-4">${escapeHtml(t('bulk.matrixEmpty'))}</p>`;

    const viaCombo = (row, tk) => tk.kind !== 'combo' && row.codes.some(code => {
        const other = state.tokens.get(code);
        return other?.kind === 'combo' && tk.disciplineIds.every(id => other.disciplineIds.includes(id));
    });

    return `
        <div class="bulk-scroll" id="matrix-scroll">
            <table class="bulk-table bulk-matrix">
                <thead>
                    <tr>
                        <th class="sticky-col"></th>
                        ${groups.map(g => `<th class="cat" colspan="${g.tokens.length}">${escapeHtml(g.category ? categoryLabel(g.category) : t('common.unknown'))}</th>`).join('')}
                        <th></th>
                    </tr>
                    <tr>
                        <th class="sticky-col">${escapeHtml(t('competitors.name'))}</th>
                        ${ordered.map(tk => `<th class="rot ${firstOfGroup.has(tk.code) ? 'first-of-cat' : ''}" title="${escapeHtml(tk.label)}"><span>${escapeHtml(tk.code)}${tk.kind === 'combo' ? ' Σ' : ''}</span></th>`).join('')}
                        <th>${escapeHtml(t('bulk.col.starts'))}</th>
                    </tr>
                </thead>
                <tbody>${rows.map(row => `
                    <tr class="${rowClass(row)}">
                        <td class="sticky-col font-medium">${escapeHtml(row.name)}<div class="bulk-note text-gray-500">${escapeHtml(row.club || '')}</div></td>
                        ${ordered.map(tk => {
                            const on = row.codes.includes(tk.code);
                            const via = !on && viaCombo(row, tk);
                            return `<td class="cell ${on ? 'is-on' : via ? 'is-via' : ''} ${firstOfGroup.has(tk.code) ? 'first-of-cat' : ''}">
                                <label title="${escapeHtml(`${row.name} · ${tk.label}`)}"><input type="checkbox" data-matrix-row="${row.key}" data-code="${escapeHtml(tk.code)}" ${on ? 'checked' : ''} class="h-4 w-4"></label></td>`;
                        }).join('')}
                        <td class="text-center tabular-nums">${rowStarts(row).size}</td>
                    </tr>`).join('')}
                </tbody>
                <tfoot><tr>
                    <td class="sticky-col text-left">${escapeHtml(t('bulk.perRanking'))}</td>
                    ${ordered.map(tk => `<td class="${firstOfGroup.has(tk.code) ? 'first-of-cat' : ''}">${rows.filter(r => r.codes.includes(tk.code)).length || ''}</td>`).join('')}
                    <td></td>
                </tr></tfoot>
            </table>
        </div>`;
}

// --- totals, result, rendering -----------------------------------------------

function renderTotals() {
    const rows = filledRows();
    const starts = rows.reduce((sum, row) => sum + rowStarts(row).size, 0);
    const errors = rows.filter(r => rowIssues(r).errors.length).length;
    const warnings = rows.filter(r => !rowIssues(r).errors.length && rowIssues(r).warnings.length).length;
    document.getElementById('totals').innerHTML = `
        <span><strong>${rows.length}</strong> ${escapeHtml(t('bulk.totals.starters', { count: rows.length }))}</span>
        <span><strong>${starts}</strong> ${escapeHtml(t('bulk.totals.starts', { count: starts }))}</span>
        ${errors ? `<span class="text-red-700">${escapeHtml(t('bulk.totals.errors', { count: errors }))}</span>` : ''}
        ${warnings ? `<span class="text-amber-700">${escapeHtml(t('bulk.totals.warnings', { count: warnings }))}</span>` : ''}
        ${errors ? `<span class="text-gray-500">${escapeHtml(t('bulk.totals.fixFirst'))}</span>` : ''}`;
    document.getElementById('save-all').disabled = !rows.length || errors > 0;
}

function render() {
    ensureBlankRow();
    document.querySelectorAll('[data-view]').forEach(b => b.setAttribute('aria-pressed', b.dataset.view === state.view));
    document.getElementById('view-hint').textContent = t(state.view === 'table' ? 'bulk.view.tableHint' : 'bulk.view.matrixHint');
    const grid = document.getElementById('grid');
    const scroll = document.getElementById('matrix-scroll')?.scrollLeft ?? 0;
    grid.innerHTML = state.tokens.size
        ? (state.view === 'table' ? tableView() : matrixView())
        : `<p class="px-4 py-3 rounded-md bg-blue-50 text-blue-800 border border-blue-200 text-sm">${t('bulk.noRankings')}</p>`;
    const matrix = document.getElementById('matrix-scroll');
    if (matrix) matrix.scrollLeft = scroll;
    document.getElementById('add-row').hidden = state.view !== 'table';
    document.getElementById('add-rows').hidden = state.view !== 'table';
    renderTotals();
}

function showResult() {
    const rows = filledRows();
    const starts = rows.reduce((sum, row) => sum + rowStarts(row).size, 0);
    document.getElementById('result').innerHTML = `
        <section class="bg-green-50 border border-green-200 text-green-900 rounded-lg p-6 space-y-3">
            <h3 class="text-lg font-semibold">${escapeHtml(t('bulk.result.title'))}</h3>
            <p class="text-sm">${escapeHtml(t('bulk.result.text', { starters: rows.length, starts }))}</p>
            <ul class="text-sm space-y-1">${rows.map(row => `
                <li><strong>${escapeHtml(row.name)}</strong>${row.club ? ` · ${escapeHtml(row.club)}` : ''}:
                    ${[...rowStarts(row)].map(disciplineName).map(escapeHtml).join(', ') || '–'}</li>`).join('')}
            </ul>
            <button type="button" id="result-close" class="bg-white border border-green-300 text-green-800 px-3 py-1 rounded-md hover:bg-green-100 text-sm">${escapeHtml(t('bulk.result.close'))}</button>
        </section>`;
    document.getElementById('result').scrollIntoView({ behavior: 'smooth', block: 'start' });
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

function sampleText() {
    const codes = [...state.tokens.values()].filter(tk => tk.kind !== 'choice').map(tk => tk.code);
    const pick = (...indexes) => indexes.map(i => codes[i % codes.length]).filter(Boolean).join(' ');
    const combo = [...state.tokens.values()].find(tk => tk.kind === 'combo')?.code || '';
    return [
        `Anna Huber\tw\t1981\tSV Beispielstadt\tAustria\t${pick(6, 10)}`,
        `Josef Gruber\tm\t1965\tSV Beispielstadt\tÖsterreich\t${pick(7, 25)} ${combo}`,
        `Karl Mayr\tm\t1990\tSV Beispielstadt\tAustria\t${pick(2, 3, 20)}`,
        `Lena Wagner\tw\t2001\tSG Nachbarort\tGermany\t${pick(13)} XYZ`
    ].join('\n');
}

// --- events ------------------------------------------------------------------

const grid = document.getElementById('grid');

grid.addEventListener('input', (e) => {
    const el = e.target;
    const row = rowByKey(el.dataset.row);
    if (!row || !el.dataset.field || el.dataset.field === 'codes') return;
    row[el.dataset.field] = el.value;
    if (el.dataset.field === 'country') row.countryText = '';
    const wasLast = row === state.rows[state.rows.length - 1];
    refreshAllNotes();
    renderTotals();
    // Typing in the empty last row opens the next one, so there is always a row to continue in
    if (wasLast && !isEmpty(row)) {
        state.rows.push(newRow());
        document.querySelector('#grid tbody').insertAdjacentHTML('beforeend', rowHtml(state.rows[state.rows.length - 1], state.rows.length - 1));
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
        if (e.key === 'Backspace' && !el.value && row.codes.length) {
            row.codes.pop();
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
    if (button?.dataset.removeCode) {
        const row = rowByKey(button.dataset.row);
        row.codes = row.codes.filter(c => c !== button.dataset.removeCode);
        refreshRow(row, { focusCodes: true });
        refreshAllNotes();
        renderTotals();
    } else if (button?.dataset.copy) {
        // Copies everything but the name, for family members or club mates with the same rankings
        const row = rowByKey(button.dataset.copy);
        const copy = newRow({ gender: '', year: '', club: row.club, country: row.country, codes: [...row.codes] });
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
        toggleCode(rowByKey(el.dataset.matrixRow), el.dataset.code);
        render();
    }
});

document.querySelector('.bulk-switch').addEventListener('click', (e) => {
    const button = e.target.closest('[data-view]');
    if (!button) return;
    state.view = button.dataset.view;
    render();
});

// The empty last row is the new one; only when it is used does another one come
document.getElementById('add-row').addEventListener('click', () => {
    render();
    focusField(state.rows[state.rows.length - 1].key, 'name');
});

document.getElementById('add-rows').addEventListener('click', () => {
    for (let i = 0; i < 5; i++) state.rows.push(newRow());
    render();
});

document.getElementById('default-club').addEventListener('input', (e) => { state.defaults.club = e.target.value; });
document.getElementById('default-country').addEventListener('change', (e) => { state.defaults.country = e.target.value; });
document.getElementById('apply-defaults').addEventListener('click', () => {
    state.rows.forEach(row => {
        if (!row.club) row.club = state.defaults.club;
        if (!row.country && !row.countryText) row.country = state.defaults.country;
    });
    render();
});

document.getElementById('paste-sample').addEventListener('click', () => {
    document.getElementById('paste-text').value = sampleText();
});
document.getElementById('paste-apply').addEventListener('click', () => {
    const area = document.getElementById('paste-text');
    const count = pasteRows(area.value);
    document.getElementById('paste-result').textContent = t('bulk.paste.added', { count });
    if (count) area.value = '';
    render();
});

document.getElementById('save-all').addEventListener('click', showResult);
document.getElementById('result').addEventListener('click', (e) => {
    if (e.target.id === 'result-close') document.getElementById('result').innerHTML = '';
});

function renderCodeList() {
    document.getElementById('code-list').innerHTML = [...state.tokens.values()].map(tk => `
        <div class="flex gap-2"><span class="font-mono font-semibold w-20 shrink-0">${escapeHtml(tk.code)}</span><span class="text-gray-600 min-w-0">${escapeHtml(tk.label)}${tk.kind === 'combo' ? ` (${escapeHtml(tk.disciplineIds.map(disciplineName).join(' + '))})` : ''}</span></div>`).join('');
}

function fillDefaultCountry() {
    const select = document.getElementById('default-country');
    select.innerHTML = `<option value="">${escapeHtml(t('competitors.form.selectCountry'))}</option>`
        + COUNTRIES.map(c => `<option value="${escapeHtml(c.value)}">${escapeHtml(c.label)}</option>`).join('');
}

fillDefaultCountry();
load()
    .then(() => { renderCodeList(); render(); })
    .catch((error) => {
        const el = document.getElementById('message');
        el.textContent = t('common.loadError', { error: error.message });
        el.className = 'mx-4 mb-4 px-4 py-3 rounded-md bg-red-100 text-red-800 border border-red-200';
    });
