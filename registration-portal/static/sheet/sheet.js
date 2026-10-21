// The club's entry sheet: one row per starter, one checkbox column per
// ranking entry (a ranking with O and R has two). Rows are checked while
// typing (rules.js) and saved in one go; the server checks again and its
// answer is shown on the rows: errors per row (400), rows someone else
// changed meanwhile (409, reloaded and marked), or the registration closed
// (403, the sheet turns read-only). Only changed rows are sent.

import { api, ApiError } from '../js/api.js';
import { escapeHtml, language, t, translatePage } from '../js/i18n.js';
import { isEmpty, rowErrors, rowWarnings } from '../js/rules.js';

translatePage();

// Opened by an organiser as /sheet/?meet=1&club=2: the same sheet for that club,
// through the organiser's API, editable also after the deadline (late entries)
const params = new URLSearchParams(location.search);
const ORGANISER = params.has('meet') && params.has('club');
const BASE = ORGANISER ? `/admin/meets/${Number(params.get('meet'))}/clubs/${Number(params.get('club'))}` : '/club';
const PATHS = { meet: ORGANISER ? `${BASE}/sheet` : '/club/meet', starters: `${BASE}/starters`, contact: `${BASE}/contact` };

const state = {
    meet: null,
    countries: [],          // [{code, name}], name in English as stored
    countryNames: new Set(),
    columns: [],            // [{key, ranking, entry, index}]
    groups: [],             // [{cat, columns}]
    rows: [],
    deleted: [],            // [{id, version}] rows removed since the last save
    readOnly: false,
    nextUid: 1
};

const sheet = document.getElementById('sheet');
const localNames = new Intl.DisplayNames([language], { type: 'region' });

// --- loading -------------------------------------------------------------------

async function load() {
    try {
        const [meet, starters, countries] = await Promise.all([api(PATHS.meet), api(PATHS.starters), api('/countries')]);
        state.meet = meet;
        state.countries = countries;
        state.countryNames = new Set(countries.map(c => c.name));
        buildColumns(meet.offer);
        state.rows = starters.starters.map(fromStarter);
        state.readOnly = !meet.open;
        fillHeader();
        padRows();
        render();
    } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
            location.href = '/login/';
            return;
        }
        const el = document.getElementById('load-error');
        el.textContent = t('sheet.loadFailed', { error: e.message });
        el.hidden = false;
    }
}

function buildColumns(offer) {
    const byCat = new Map();
    for (const ranking of offer) {
        const cat = ['rifle', 'pistol'].includes(ranking.category) ? ranking.category : 'mixed';
        if (!byCat.has(cat)) byCat.set(cat, []);
        ranking.entries.forEach((entry, index) => byCat.get(cat).push({ key: `${ranking.ranking_id}:${entry.index}`, ranking, entry, index }));
    }
    state.groups = ['rifle', 'pistol', 'mixed'].filter(c => byCat.has(c)).map(cat => ({ cat, columns: byCat.get(cat) }));
    state.columns = state.groups.flatMap(g => g.columns);
}

function fromStarter(s) {
    return {
        uid: state.nextUid++, id: s.id, version: s.version,
        name: s.name || '', gender: s.gender || '', year: s.year_of_birth ?? '', club: s.club || '',
        country: s.country || '', email: s.email || '',
        sel: new Set((s.choices || []).map(c => `${c.ranking_id}:${c.entry}`)),
        dirty: false, serverErrors: [], stale: false
    };
}

function newRow() {
    return { uid: state.nextUid++, id: null, version: null, name: '', gender: '', year: '', club: '', country: '', email: '',
        sel: new Set(), dirty: false, serverErrors: [], stale: false };
}

/** Keeps a few empty rows at the end to type into. */
function padRows() {
    if (state.readOnly) return;
    let empty = 0;
    for (let i = state.rows.length - 1; i >= 0 && isEmpty(state.rows[i]) && !state.rows[i].id; i--) empty++;
    while (empty++ < Math.max(3, 8 - state.rows.length)) state.rows.push(newRow());
}

// --- header ----------------------------------------------------------------------

function fillHeader() {
    const m = state.meet;
    document.title = `${m.name} · ${t('sheet.title')}`;
    document.getElementById('meet-name').textContent = m.name;
    document.getElementById('club-name').textContent = ORGANISER ? `${m.club.name} · ${t('sheet.organiserMode')}` : m.club.name;
    document.getElementById('back').hidden = !ORGANISER;
    document.getElementById('late').hidden = !(ORGANISER && m.registration_open === false);
    if (m.deadline) {
        const deadline = new Date(m.deadline);
        const date = new Intl.DateTimeFormat(language, { dateStyle: 'full', timeStyle: 'short', timeZone: m.time_zone }).format(deadline);
        const hours = Math.floor((deadline - Date.now()) / 3600000);
        const left = hours <= 0 ? '' : hours < 48 ? t('sheet.leftHours', { count: hours }) : t('sheet.left', { count: Math.floor(hours / 24) });
        document.getElementById('deadline').textContent = t('sheet.deadline', { date }) + (left && m.open ? ` · ${left}` : '');
    }
    document.getElementById('c-name').value = m.club.contact?.name || '';
    document.getElementById('c-email').value = m.club.contact?.email || '';
    const select = document.getElementById('default-country');
    select.innerHTML = countryOptions('');
    select.value = m.club.default_country || '';
    applyReadOnly();
}

function applyReadOnly() {
    document.getElementById('closed').hidden = !state.readOnly;
    document.body.classList.toggle('readonly', state.readOnly);
    for (const id of ['save', 'add-5', 'contact-save', 'c-name', 'c-email', 'default-country']) {
        document.getElementById(id).disabled = state.readOnly;
    }
    document.getElementById('consent-box').hidden = ORGANISER || state.meet.club.consent_given || state.readOnly;
}

// --- the table ---------------------------------------------------------------------

let countryOptionsCache = null;
function countryOptions(emptyLabel) {
    if (!countryOptionsCache) {
        const sorted = [...state.countries].sort((a, b) => label(a).localeCompare(label(b), language));
        countryOptionsCache = sorted.map(c => `<option value="${escapeHtml(c.name)}">${escapeHtml(label(c))}</option>`).join('');
    }
    return `<option value="">${escapeHtml(emptyLabel)}</option>${countryOptionsCache}`;
}
const label = (c) => localNames.of(c.code) || c.name;
const defaultCountry = () => document.getElementById('default-country').value || null;
const defaultCountryLabel = () => {
    const c = state.countries.find(x => x.name === defaultCountry());
    return c ? `(${label(c)})` : '–';
};

function context() {
    return {
        countries: state.countryNames, defaultCountry: defaultCountry(), collectEmail: state.meet.collect_email,
        offered: new Set(state.columns.map(c => c.key)), thisYear: new Date().getFullYear()
    };
}

function startsOf(row) {
    const ids = new Set();
    for (const col of state.columns) {
        if (row.sel.has(col.key)) col.entry.disciplines.forEach(d => ids.add(d.id));
    }
    return ids;
}

function headerHtml() {
    const email = state.meet.collect_email;
    const fixed = 5 + (email ? 1 : 0);
    const head1 = `<tr><th class="sticky c-num" rowspan="2">#</th><th class="sticky c-name" rowspan="2">${escapeHtml(t('sheet.name'))} *</th>
        <th rowspan="2">${escapeHtml(t('sheet.gender'))}</th><th rowspan="2">${escapeHtml(t('sheet.year'))}</th>
        <th rowspan="2">${escapeHtml(t('sheet.club'))}</th><th rowspan="2">${escapeHtml(t('sheet.country'))} *</th>
        ${email ? `<th rowspan="2">${escapeHtml(t('sheet.email'))}</th>` : ''}
        ${state.groups.map(g => `<th class="grp ${g.cat}" colspan="${g.columns.length}">${escapeHtml(t('sheet.' + g.cat))}</th>`).join('')}
        <th rowspan="2">${escapeHtml(t('sheet.starts'))}</th><th rowspan="2">${escapeHtml(t('sheet.status'))}</th><th rowspan="2"></th></tr>`;
    const head2 = `<tr>${state.columns.map(col => {
        const multi = col.ranking.entries.length > 1;
        const sum = col.entry.disciplines.length > 1;
        const title = col.ranking.name + (multi ? ` · ${col.entry.label}` : '') + (sum ? ` (${col.entry.disciplines.map(d => d.name).join(' + ')})` : '');
        return `<th class="rk" title="${escapeHtml(title)}"><span class="code">${escapeHtml(multi ? col.entry.label : col.ranking.short_name)}</span>`
            + `${multi ? `<span class="sub">${escapeHtml(col.ranking.short_name)}</span>` : ''}${sum ? `<span class="sub">Σ ${escapeHtml(t('sheet.sum'))}</span>` : ''}</th>`;
    }).join('')}</tr>`;
    return { head: `<thead>${head1}${head2}</thead>`, fixed };
}

function rowHtml(row, i) {
    const email = state.meet.collect_email;
    const g = (v, l) => `<option value="${v}"${row.gender === v ? ' selected' : ''}>${escapeHtml(l)}</option>`;
    const cells = state.columns.map(col => {
        const on = row.sel.has(col.key);
        const title = `${row.name || '…'} · ${col.ranking.name}${col.ranking.entries.length > 1 ? ' · ' + col.entry.label : ''}`;
        return `<td class="cell${on ? ' on' : ''}"><input type="checkbox" data-row="${row.uid}" data-col="${col.key}" title="${escapeHtml(title)}" aria-label="${escapeHtml(title)}"${on ? ' checked' : ''}></td>`;
    }).join('');
    return `<tr data-row="${row.uid}">
        <td class="c-num sticky">${i + 1}</td>
        <td class="sticky c-name"><input type="text" data-row="${row.uid}" data-f="name" value="${escapeHtml(row.name)}" placeholder="${escapeHtml(t('sheet.namePlaceholder'))}" aria-label="${escapeHtml(t('sheet.name'))}" style="min-width:190px" maxlength="100"></td>
        <td><select data-row="${row.uid}" data-f="gender" aria-label="${escapeHtml(t('sheet.gender'))}" style="min-width:56px">${g('', '–')}${g('male', t('sheet.m'))}${g('female', t('sheet.f'))}${g('other', t('sheet.o'))}</select></td>
        <td><input type="number" min="1900" max="2100" data-row="${row.uid}" data-f="year" value="${escapeHtml(row.year)}" aria-label="${escapeHtml(t('sheet.year'))}" style="width:76px"></td>
        <td><input type="text" data-row="${row.uid}" data-f="club" value="${escapeHtml(row.club)}" placeholder="${escapeHtml(state.meet.club.name)}" aria-label="${escapeHtml(t('sheet.club'))}" style="min-width:140px" maxlength="200"></td>
        <td><select data-row="${row.uid}" data-f="country" data-value="${escapeHtml(row.country)}" aria-label="${escapeHtml(t('sheet.country'))}" style="min-width:130px">${countryOptions(defaultCountryLabel())}</select></td>
        ${email ? `<td><input type="email" data-row="${row.uid}" data-f="email" value="${escapeHtml(row.email)}" aria-label="${escapeHtml(t('sheet.email'))}" style="min-width:170px"></td>` : ''}
        ${cells}
        <td class="c-starts" data-starts="${row.uid}"></td>
        <td class="c-status" data-status="${row.uid}"></td>
        <td><button type="button" class="del" data-del="${row.uid}" title="${escapeHtml(t('sheet.deleteRow'))}" aria-label="${escapeHtml(t('sheet.deleteRow'))}">×</button></td>
    </tr>`;
}

function render() {
    const { head, fixed } = headerHtml();
    const scroll = document.getElementById('scroll');
    const pos = [scroll.scrollLeft, scroll.scrollTop];
    const foot = `<tfoot><tr><td class="sticky c-num"></td><td class="sticky c-name" style="text-align:left">Σ</td><td colspan="${fixed - 1}"></td>`
        + state.columns.map(col => `<td data-count="${col.key}"></td>`).join('') + '<td colspan="3"></td></tr></tfoot>';
    sheet.innerHTML = `${head}<tbody>${state.rows.map(rowHtml).join('')}</tbody>${foot}`;
    sheet.querySelectorAll('select[data-f="country"]').forEach(s => { s.value = s.dataset.value; });
    [scroll.scrollLeft, scroll.scrollTop] = pos;
    state.rows.forEach(refreshRow);
    document.getElementById('legend').innerHTML = state.meet.offer.map(r => `<div><span class="code">${escapeHtml(r.short_name)}</span> ${escapeHtml(r.name)}: `
        + r.entries.map(e => escapeHtml(e.label) + (e.disciplines.length > 1 ? ` (${e.disciplines.map(d => escapeHtml(d.name)).join(' + ')})` : '')).join(' / ') + '</div>').join('');
    renderTotals();
}

/** Redraws a row's marks (status, starts, ticked cells) without touching what is being typed. */
function refreshRow(row) {
    const tr = sheet.querySelector(`tr[data-row="${row.uid}"]`);
    if (!tr) return;
    const errors = [...rowErrors(row, context()), ...row.serverErrors];
    const warnings = rowWarnings(row, state.rows);
    tr.className = [errors.length ? 'err' : warnings.length ? 'warn' : '', row.dirty ? 'dirty' : ''].join(' ').trim();
    const status = tr.querySelector(`[data-status="${row.uid}"]`);
    if (isEmpty(row)) {
        status.innerHTML = '';
    } else {
        status.innerHTML = (row.stale ? `<span class="st-warn">${escapeHtml(t('sheet.staleRow'))}</span>` : '')
            + errors.map(e => `<span class="st-err">${escapeHtml(e)}</span>`).join('')
            + warnings.map(w => `<span class="st-warn">${escapeHtml(w)}</span>`).join('')
            + (!errors.length && !warnings.length && !row.stale ? `<span class="st-ok">${escapeHtml(row.dirty ? t('sheet.unsaved') : t('sheet.ok'))}</span>` : '');
    }
    tr.querySelector(`[data-starts="${row.uid}"]`).textContent = startsOf(row).size || '';
    tr.querySelectorAll('td.cell').forEach(td => td.classList.toggle('on', row.sel.has(td.querySelector('input').dataset.col)));
}

function renderTotals() {
    const filled = state.rows.filter(r => !isEmpty(r));
    const starts = filled.reduce((n, r) => n + startsOf(r).size, 0);
    const errors = filled.filter(r => rowErrors(r, context()).length || r.serverErrors.length).length;
    const unsaved = state.rows.filter(r => r.dirty && (!isEmpty(r) || r.id)).length + state.deleted.length;
    document.getElementById('totals').innerHTML = [
        `<strong>${escapeHtml(t('sheet.totStarters', { count: filled.length }))}</strong>`,
        escapeHtml(t('sheet.totStarts', { count: starts })),
        errors ? `<span class="st-err" style="display:inline">${escapeHtml(t('sheet.totErrors', { count: errors }))}</span>` : '',
        unsaved ? `<span class="st-warn" style="display:inline">${escapeHtml(t('sheet.unsavedCount', { count: unsaved }))}</span>` : ''
    ].filter(Boolean).map(x => `<span>${x}</span>`).join('');
    for (const col of state.columns) {
        const td = sheet.querySelector(`tfoot [data-count="${col.key}"]`);
        if (td) td.textContent = state.rows.filter(r => r.sel.has(col.key)).length || '';
    }
    document.getElementById('save').disabled = state.readOnly || unsaved === 0;
}

// --- editing -----------------------------------------------------------------------

const rowByUid = (uid) => state.rows.find(r => r.uid === Number(uid));

function touched(row) {
    row.dirty = true;
    row.serverErrors = [];
    // Typing in the last row opens the next one
    if (row === state.rows[state.rows.length - 1] && !isEmpty(row)) {
        const next = newRow();
        state.rows.push(next);
        sheet.querySelector('tbody').insertAdjacentHTML('beforeend', rowHtml(next, state.rows.length - 1));
        refreshRow(next);
    }
    refreshRow(row);
    // A changed name can make or end a duplicate elsewhere
    state.rows.forEach(r => { if (r !== row) refreshRow(r); });
    renderTotals();
    setState('');
}

sheet.addEventListener('input', (e) => {
    const el = e.target;
    const row = rowByUid(el.dataset.row);
    if (!row || !el.dataset.f || state.readOnly) return;
    row[el.dataset.f] = el.value;
    touched(row);
});

sheet.addEventListener('change', (e) => {
    const el = e.target;
    const row = rowByUid(el.dataset.row);
    if (!row || state.readOnly) return;
    if (el.dataset.col) {
        if (el.checked) row.sel.add(el.dataset.col); else row.sel.delete(el.dataset.col);
        touched(row);
    } else if (el.tagName === 'SELECT' && el.dataset.f) {
        row[el.dataset.f] = el.value;
        touched(row);
    }
});

sheet.addEventListener('click', (e) => {
    const button = e.target.closest('[data-del]');
    if (!button || state.readOnly) return;
    const row = rowByUid(button.dataset.del);
    if (row.id) state.deleted.push({ id: row.id, version: row.version });
    state.rows = state.rows.filter(r => r !== row);
    padRows();
    render();
    setState('');
});

// Enter and the arrow keys move to the row below or above, as in a spreadsheet
sheet.addEventListener('keydown', (e) => {
    const el = e.target;
    if (!el.dataset.row) return;
    const textual = el.tagName === 'INPUT' && el.type !== 'number' && el.type !== 'checkbox';
    const down = e.key === 'Enter' || (e.key === 'ArrowDown' && textual);
    const up = e.key === 'ArrowUp' && textual;
    if (!down && !up) return;
    e.preventDefault();
    const i = state.rows.findIndex(r => r.uid === Number(el.dataset.row)) + (down ? 1 : -1);
    if (i < 0) return;
    if (i >= state.rows.length) {
        state.rows.push(newRow());
        render();
    }
    const selector = el.dataset.f ? `[data-f="${el.dataset.f}"]` : `[data-col="${el.dataset.col}"]`;
    sheet.querySelector(`tr[data-row="${state.rows[i].uid}"] ${selector}`)?.focus();
});

// Several rows copied from Excel into the name column: name, gender, year, club, country
sheet.addEventListener('paste', (e) => {
    const el = e.target;
    if (el.dataset.f !== 'name' || state.readOnly) return;
    const text = e.clipboardData.getData('text');
    if (!/[\t\n]/.test(text.trim())) return;
    e.preventDefault();
    let i = state.rows.findIndex(r => r.uid === Number(el.dataset.row));
    for (const line of text.replace(/\r/g, '').split('\n').filter(l => l.trim())) {
        const c = line.split('\t').map(s => s.trim());
        while (i >= state.rows.length) state.rows.push(newRow());
        const row = state.rows[i++];
        row.name = c[0] || row.name;
        const g = (c[1] || '').toLowerCase();
        if (g) row.gender = /^(m|male|männlich|herr)$/.test(g) ? 'male' : /^(w|f|female|weiblich|frau|dame)$/.test(g) ? 'female' : 'other';
        if (c[2]) row.year = c[2].replace(/\D/g, '');
        if (c[3]) row.club = c[3];
        if (c[4]) {
            const hit = state.countries.find(x => x.name.toLowerCase() === c[4].toLowerCase() || label(x).toLowerCase() === c[4].toLowerCase());
            if (hit) row.country = hit.name;
        }
        row.dirty = true;
        row.serverErrors = [];
    }
    padRows();
    render();
});

document.getElementById('add-5').addEventListener('click', () => {
    for (let n = 0; n < 5; n++) state.rows.push(newRow());
    render();
});

document.getElementById('default-country').addEventListener('change', () => {
    // Rows without their own country follow the default; they are sent with it, so they count as changed
    state.rows.filter(r => !r.country && !isEmpty(r)).forEach(r => { r.dirty = true; });
    render();
});

// --- saving ------------------------------------------------------------------------

function setState(text, kind = '') {
    const el = document.getElementById('save-state');
    el.textContent = text;
    el.className = `save-state ${kind}`;
}

function payload(row) {
    const p = {
        name: row.name.trim(),
        gender: row.gender || null,
        year_of_birth: row.year === '' ? null : Number(row.year),
        club: row.club.trim() || null,
        country: row.country || defaultCountry(),
        email: state.meet.collect_email ? (row.email.trim() || null) : null,
        choices: state.columns.filter(c => row.sel.has(c.key)).map(c => ({ ranking_id: c.ranking.ranking_id, entry: c.entry.index }))
    };
    if (row.id) {
        p.id = row.id;
        p.version = row.version;
    }
    return p;
}

async function save() {
    if (state.readOnly) return;
    const ctx = context();
    if (state.rows.some(r => rowErrors(r, ctx).length)) {
        setState(t('sheet.fixFirst'), 'bad');
        return;
    }
    const consent = document.getElementById('consent');
    if (!ORGANISER && !state.meet.club.consent_given && !consent.checked) {
        setState(t('sheet.consentFirst'), 'bad');
        consent.focus();
        return;
    }
    const sent = state.rows.filter(r => r.dirty && !isEmpty(r));
    const emptied = state.rows.filter(r => r.dirty && isEmpty(r) && r.id);
    const deletes = [...state.deleted, ...emptied.map(r => ({ id: r.id, version: r.version }))];
    const button = document.getElementById('save');
    button.disabled = true;
    try {
        const result = await api(PATHS.starters, 'PUT', {
            upsert: sent.map(payload), delete: deletes, consent: consent.checked || undefined
        });
        state.rows = result.starters.map(fromStarter);
        state.deleted = [];
        state.meet.club.consent_given = true;
        padRows();
        render();
        applyReadOnly();
        setState(t('sheet.saved'), 'ok');
    } catch (e) {
        handleSaveError(e, sent);
    } finally {
        renderTotals();
    }
}

function handleSaveError(e, sent) {
    if (e.status === 401) {
        location.href = '/login/';
        return;
    }
    if (e.status === 400 && Array.isArray(e.body.rows)) {
        for (const r of e.body.rows) {
            const row = sent[r.index];
            if (row) row.serverErrors = r.errors;
        }
        state.rows.forEach(refreshRow);
    } else if (e.status === 409 && Array.isArray(e.body.current)) {
        // Rows someone else changed: take theirs and mark them; the own unsaved rows stay
        for (const current of e.body.current) {
            const fresh = { ...fromStarter(current), stale: true };
            const i = state.rows.findIndex(r => r.id === current.id);
            if (i >= 0) {
                state.rows[i] = fresh;
            } else {
                state.deleted = state.deleted.filter(d => d.id !== current.id);
                state.rows.unshift(fresh);
            }
        }
        render();
    } else if (e.status === 403 && e.body.closed) {
        state.readOnly = true;
        applyReadOnly();
        render();
    }
    setState(e.message, 'bad');
}

document.getElementById('save').addEventListener('click', save);

document.getElementById('contact-save').addEventListener('click', async () => {
    const stateEl = document.getElementById('contact-state');
    try {
        const club = await api(PATHS.contact, 'PUT', {
            name: document.getElementById('c-name').value,
            email: document.getElementById('c-email').value,
            version: state.meet.club.version
        });
        state.meet.club = { ...state.meet.club, ...club };
        stateEl.textContent = t('sheet.contactSaved');
        stateEl.className = 'save-state ok';
    } catch (e) {
        if (e.status === 409 && e.body.current) {
            state.meet.club = { ...state.meet.club, ...e.body.current };
            document.getElementById('c-name').value = e.body.current.contact?.name || '';
            document.getElementById('c-email').value = e.body.current.contact?.email || '';
        }
        stateEl.textContent = e.message;
        stateEl.className = 'save-state bad';
    }
});

document.getElementById('logout').addEventListener('click', async () => {
    await api('/auth/logout', 'POST').catch(() => {});
    location.href = '/login/';
});

window.addEventListener('beforeunload', (e) => {
    if (state.deleted.length || state.rows.some(r => r.dirty && (!isEmpty(r) || r.id))) {
        e.preventDefault();
        e.returnValue = t('sheet.leaveWarning');
    }
});

load();
