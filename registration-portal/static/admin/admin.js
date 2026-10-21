// The organiser's page. One page with tabs (#meets, #clubs, #starters,
// #download, #data, #account) for the meet chosen at the top. Everything is
// saved through the organiser's API; destructive actions are confirmed on the
// page itself (a second click, or typing the meet's key), never in a popup.

import { api, ApiError } from '../js/api.js';
import { escapeHtml, language, t, translatePage } from '../js/i18n.js';
import { qrSvg } from '../js/qrCode.js';

translatePage();
document.title = `${t('admin.title')} · ${t('common.portal')}`;

const TABS = ['meets', 'clubs', 'starters', 'download', 'data', 'account'];
const MEET_KEY = 'mlaicportal.meet';
const localNames = new Intl.DisplayNames([language], { type: 'region' });

const state = {
    session: null,
    meets: [],
    meetId: null,
    countries: [],
    editing: null      // the meet in the form; null for a new one
};

const $ = (id) => document.getElementById(id);
const meet = () => state.meets.find(m => m.id === state.meetId) || null;
const countryLabel = (name) => {
    const c = state.countries.find(x => x.name === name);
    return c ? (localNames.of(c.code) || c.name) : (name || '');
};
const formatDateTime = (iso, zone) => iso
    ? new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short', timeZone: zone || undefined }).format(new Date(iso)) : '–';

function message(id, text, kind = '') {
    const el = $(id);
    if (!el) return;
    el.textContent = text;
    el.className = `section-msg ${kind}`;
}

/** A button that needs a second click within a few seconds; the first click only arms it. */
function confirmClick(button, action) {
    button.addEventListener('click', async () => {
        if (!button.classList.contains('armed')) {
            const label = button.textContent;
            button.classList.add('armed');
            button.textContent = t('admin.confirmAgain');
            button._timer = setTimeout(() => { button.classList.remove('armed'); button.textContent = label; }, 4000);
            return;
        }
        clearTimeout(button._timer);
        button.classList.remove('armed');
        await action();
    });
}

async function guarded(msgId, action) {
    try {
        return await action();
    } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
            location.href = '/login/';
            return undefined;
        }
        message(msgId, e.message, 'bad');
        return undefined;
    }
}

// --- frame: session, meets, tabs --------------------------------------------------

async function start() {
    try {
        state.session = await api('/session');
        if (state.session.role !== 'organiser') {
            location.href = '/sheet/';
            return;
        }
    } catch {
        location.href = '/login/';
        return;
    }
    $('who').textContent = state.session.username;
    state.countries = await api('/countries').catch(() => []);
    await loadMeets();
    window.addEventListener('hashchange', showTab);
    showTab();
}

async function loadMeets(selectId) {
    state.meets = await api('/admin/meets');
    let wanted = selectId ?? state.meetId ?? Number(localStorage.getItem(MEET_KEY));
    if (!state.meets.some(m => m.id === wanted)) {
        wanted = (state.meets.find(m => m.status === 'open') || state.meets[state.meets.length - 1] || {}).id ?? null;
    }
    state.meetId = wanted;
    try { localStorage.setItem(MEET_KEY, String(wanted ?? '')); } catch { /* fine */ }
    $('meet-select').innerHTML = state.meets.map(m => `<option value="${m.id}">${escapeHtml(m.name)} (${escapeHtml(t('admin.status.' + m.status))})</option>`).join('')
        || `<option value="">${escapeHtml(t('admin.noMeets'))}</option>`;
    $('meet-select').value = String(wanted ?? '');
}

$('meet-select').addEventListener('change', async (e) => {
    state.meetId = Number(e.target.value) || null;
    try { localStorage.setItem(MEET_KEY, String(state.meetId ?? '')); } catch { /* fine */ }
    state.editing = null;
    showTab();
});

function currentTab() {
    const hash = location.hash.slice(1);
    return TABS.includes(hash) ? hash : 'meets';
}

document.querySelectorAll('[data-tab]').forEach(b => b.addEventListener('click', () => { location.hash = b.dataset.tab; }));

function showTab() {
    const tab = currentTab();
    document.querySelectorAll('[data-tab]').forEach(b => b.setAttribute('aria-selected', String(b.dataset.tab === tab)));
    TABS.forEach(name => { $(`tab-${name}`).hidden = name !== tab; });
    const needsMeet = !['meets', 'account'].includes(tab);
    $('no-meet').hidden = !(needsMeet && !state.meetId);
    if (needsMeet && !state.meetId) {
        $(`tab-${tab}`).innerHTML = '';
        return;
    }
    ({ meets: renderMeets, clubs: renderClubs, starters: renderStarters, download: renderDownload, data: renderData, account: renderAccount })[tab]();
}

$('logout').addEventListener('click', async () => {
    await api('/auth/logout', 'POST').catch(() => {});
    location.href = '/login/';
});

// --- meets ----------------------------------------------------------------------------

function renderMeets() {
    const el = $('tab-meets');
    const m = state.editing === 'new' ? null : (state.editing ? state.meets.find(x => x.id === state.editing) : meet());
    const v = (field) => escapeHtml(m?.[field] ?? '');
    const deadlineLocal = m?.deadline ? m.deadline.slice(0, 16) : '';
    el.innerHTML = `
        <div class="card">
            <div class="topbar mb-8">
                <h2>${escapeHtml(t('admin.meets'))}</h2>
                <button type="button" class="btn" id="new-meet">${escapeHtml(t('admin.newMeet'))}</button>
            </div>
            <div class="table-scroll"><table class="list">
                <thead><tr><th>${escapeHtml(t('admin.name'))}</th><th>${escapeHtml(t('admin.key'))}</th><th>${escapeHtml(t('admin.status'))}</th>
                    <th>${escapeHtml(t('admin.deadline'))}</th><th>${escapeHtml(t('admin.clubs'))}</th><th>${escapeHtml(t('admin.starters'))}</th></tr></thead>
                <tbody>${state.meets.map(x => `<tr class="${x.id === state.meetId ? 'selected' : ''}">
                    <td><a href="#meets" data-pick="${x.id}">${escapeHtml(x.name)}</a></td><td><span class="chip">${escapeHtml(x.key)}</span></td>
                    <td><span class="pill ${x.status}">${escapeHtml(t('admin.status.' + x.status))}</span></td>
                    <td>${escapeHtml(formatDateTime(x.deadline, x.time_zone))}</td><td>${x.clubs}</td><td>${x.starters}</td></tr>`).join('')
                    || `<tr><td colspan="6" class="muted">${escapeHtml(t('admin.noMeets'))}</td></tr>`}</tbody>
            </table></div>
        </div>

        <form class="card" id="meet-form" novalidate>
            <h2>${escapeHtml(m ? t('admin.editMeet', { name: m.name }) : t('admin.newMeet'))}</h2>
            <div class="fields">
                <div class="field"><label for="f-name">${escapeHtml(t('admin.name'))} *</label><input id="f-name" value="${v('name')}" required maxlength="200"></div>
                <div class="field"><label for="f-key">${escapeHtml(t('admin.key'))} *</label><input id="f-key" value="${v('key')}" required maxlength="40" placeholder="ooe-lm-2027" pattern="[a-z0-9][a-z0-9-]*"></div>
                <div class="field"><label for="f-date">${escapeHtml(t('admin.date'))}</label><input id="f-date" type="date" value="${v('date')}"></div>
                <div class="field"><label for="f-deadline">${escapeHtml(t('admin.deadline'))}</label><input id="f-deadline" type="datetime-local" value="${escapeHtml(deadlineLocal)}"></div>
                <div class="field"><label for="f-zone">${escapeHtml(t('admin.timeZone'))}</label><input id="f-zone" value="${escapeHtml(m?.time_zone || 'Europe/Vienna')}"></div>
                <div class="field"><label for="f-delete">${escapeHtml(t('admin.deleteAfter'))}</label><input id="f-delete" type="date" value="${v('delete_after')}"></div>
                <div class="field"><label for="f-cname">${escapeHtml(t('admin.contactName'))}</label><input id="f-cname" value="${escapeHtml(m?.contact?.name || '')}"></div>
                <div class="field"><label for="f-cemail">${escapeHtml(t('admin.contactEmail'))}</label><input id="f-cemail" type="email" value="${escapeHtml(m?.contact?.email || '')}"></div>
                <div class="field"><label for="f-cclub">${escapeHtml(t('admin.contactClub'))}</label><input id="f-cclub" value="${escapeHtml(m?.contact?.club || '')}"></div>
                <div class="field check"><input id="f-email" type="checkbox" ${m?.collect_email ? 'checked' : ''}><label for="f-email">${escapeHtml(t('admin.collectEmail'))}</label></div>
            </div>
            <p class="muted fs-85">${escapeHtml(t('admin.deleteAfterHint'))}</p>
            <div class="btn-row">
                <button type="submit" class="btn primary">${escapeHtml(m ? t('admin.saveMeet') : t('admin.createMeet'))}</button>
                ${m ? `
                    ${m.status !== 'open' ? `<button type="button" class="btn" data-status="open">${escapeHtml(t('admin.open'))}</button>` : ''}
                    ${m.status === 'open' ? `<button type="button" class="btn" data-status="closed">${escapeHtml(t('admin.close'))}</button>` : ''}
                    ${m.status === 'closed' ? `<button type="button" class="btn" data-status="draft">${escapeHtml(t('admin.backToDraft'))}</button>` : ''}` : ''}
            </div>
            <p id="msg-meet" class="section-msg"></p>
        </form>

        ${m ? `<div class="card">
            <h2>${escapeHtml(t('admin.rankingsTitle'))}</h2>
            <p class="muted fs-88">${escapeHtml(t('admin.rankingsHint'))}</p>
            <p>${m.snapshot ? escapeHtml(t('admin.rankingsUploaded', { date: formatDateTime(m.snapshot.uploaded_at), offered: m.snapshot.offered, total: m.snapshot.rankings }))
                : `<span class="pill bad">${escapeHtml(t('admin.rankingsMissing'))}</span>`}</p>
            <div class="fields">
                <div class="field"><label for="u-rankings">${escapeHtml(t('admin.rankingsFile'))}</label><input id="u-rankings" type="file" accept=".json,application/json"></div>
                <div class="field"><label for="u-catalog">${escapeHtml(t('admin.catalogFile'))}</label><input id="u-catalog" type="file" accept=".json,application/json"></div>
            </div>
            <div class="btn-row mt-10"><button type="button" class="btn primary" id="upload">${escapeHtml(t('admin.upload'))}</button></div>
            <p id="msg-upload" class="section-msg"></p>
            <div id="offer"></div>
        </div>` : ''}`;

    el.querySelectorAll('[data-pick]').forEach(a => a.addEventListener('click', (e) => {
        e.preventDefault();
        state.meetId = Number(a.dataset.pick);
        state.editing = null;
        $('meet-select').value = String(state.meetId);
        try { localStorage.setItem(MEET_KEY, String(state.meetId)); } catch { /* fine */ }
        renderMeets();
    }));
    $('new-meet').addEventListener('click', () => { state.editing = 'new'; renderMeets(); $('f-name').focus(); });
    $('meet-form').addEventListener('submit', (e) => { e.preventDefault(); saveMeet(m, null); });
    el.querySelectorAll('[data-status]').forEach(b => b.addEventListener('click', () => saveMeet(m, b.dataset.status)));
    if (m) {
        $('upload').addEventListener('click', () => upload(m));
        if (m.snapshot) showOffer(m.id);
    }
}

function meetBody(m, status) {
    const body = {
        name: $('f-name').value, key: $('f-key').value.trim(), date: $('f-date').value || null,
        deadline: $('f-deadline').value || null, time_zone: $('f-zone').value || null, delete_after: $('f-delete').value || null,
        collect_email: $('f-email').checked,
        contact: { name: $('f-cname').value, email: $('f-cemail').value, club: $('f-cclub').value }
    };
    if (m) {
        body.version = m.version;
        if (status) body.status = status;
    }
    return body;
}

async function saveMeet(m, status) {
    await guarded('msg-meet', async () => {
        const saved = m ? await api(`/admin/meets/${m.id}`, 'PUT', meetBody(m, status)) : await api('/admin/meets', 'POST', meetBody(null));
        state.editing = null;
        await loadMeets(saved.id);
        renderMeets();
        message('msg-meet', t('admin.saved'), 'ok');
    });
}

async function readJson(input) {
    const file = input.files[0];
    if (!file) return null;
    try {
        return JSON.parse(await file.text());
    } catch {
        throw new Error(t('admin.notJson', { file: file.name }));
    }
}

async function upload(m) {
    await guarded('msg-upload', async () => {
        const rankings = await readJson($('u-rankings'));
        const catalog = await readJson($('u-catalog'));
        if (!rankings || !catalog) {
            message('msg-upload', t('admin.chooseBoth'), 'bad');
            return;
        }
        const result = await api(`/admin/meets/${m.id}/snapshot`, 'PUT', { rankings, catalog });
        await loadMeets(m.id);
        renderMeets();
        message('msg-upload', [t('admin.uploaded', { count: result.offer.length }), ...result.warnings].join(' '), result.warnings.length ? '' : 'ok');
    });
}

async function showOffer(meetId) {
    const offer = await api(`/admin/meets/${meetId}/offer`).catch(() => []);
    const el = $('offer');
    if (!el) return;
    el.innerHTML = `<details class="mt-8"><summary>${escapeHtml(t('admin.offer', { count: offer.length }))}</summary>
        <div class="legend-grid">${offer.map(r => `<div><span class="code">${escapeHtml(r.short_name)}</span> ${escapeHtml(r.name)}: ${r.entries.map(e => escapeHtml(e.label)).join(' / ')}</div>`).join('')}</div></details>`;
}

// --- clubs ----------------------------------------------------------------------------

async function renderClubs() {
    const el = $('tab-clubs');
    const clubs = await guardedLoad(() => api(`/admin/meets/${state.meetId}/clubs`));
    if (!clubs) return;
    const countryOptions = state.countries.map(c => ({ name: c.name, label: countryLabel(c.name) }))
        .sort((a, b) => a.label.localeCompare(b.label, language))
        .map(c => `<option value="${escapeHtml(c.name)}">${escapeHtml(c.label)}</option>`).join('');
    el.innerHTML = `
        <form class="card" id="club-form" novalidate>
            <h2>${escapeHtml(t('admin.newClub'))}</h2>
            <div class="fields">
                <div class="field"><label for="club-name">${escapeHtml(t('admin.clubName'))} *</label><input id="club-name" maxlength="200" required></div>
                <div class="field"><label for="club-country">${escapeHtml(t('admin.defaultCountry'))}</label><select id="club-country"><option value="">–</option>${countryOptions}</select></div>
            </div>
            <div class="btn-row mt-10"><button type="submit" class="btn primary">${escapeHtml(t('admin.createClub'))}</button></div>
            <p id="msg-club" class="section-msg"></p>
            <div id="code-box"></div>
        </form>
        <div class="card">
            <h2>${escapeHtml(t('admin.clubsOf', { meet: meet()?.name || '' }))}</h2>
            <div class="table-scroll"><table class="list">
                <thead><tr><th>${escapeHtml(t('admin.clubName'))}</th><th>${escapeHtml(t('admin.defaultCountry'))}</th><th>${escapeHtml(t('admin.code'))}</th>
                    <th>${escapeHtml(t('admin.lastLogin'))}</th><th>${escapeHtml(t('admin.starters'))}</th><th></th></tr></thead>
                <tbody>${clubs.map(c => `<tr>
                    <td>${escapeHtml(c.name)}${c.revoked ? ` <span class="pill bad">${escapeHtml(t('admin.blocked'))}</span>` : ''}</td>
                    <td>${escapeHtml(countryLabel(c.default_country))}</td>
                    <td><span class="chip">${escapeHtml(c.code_hint || '')}</span></td>
                    <td>${escapeHtml(c.last_login_at ? formatDateTime(c.last_login_at) : t('admin.never'))}</td>
                    <td>${c.starters}</td>
                    <td><div class="btn-row">
                        <button type="button" class="btn" data-rotate="${c.id}">${escapeHtml(t('admin.newCode'))}</button>
                        <button type="button" class="btn" data-revoke="${c.id}" data-revoked="${c.revoked}">${escapeHtml(c.revoked ? t('admin.unblock') : t('admin.block'))}</button>
                        <button type="button" class="btn danger" data-delete="${c.id}" data-starters="${c.starters}">${escapeHtml(c.starters ? t('admin.deleteWithStarters', { count: c.starters }) : t('admin.delete'))}</button>
                    </div></td></tr>`).join('') || `<tr><td colspan="6" class="muted">${escapeHtml(t('admin.noClubs'))}</td></tr>`}</tbody>
            </table></div>
            <p id="msg-clubs" class="section-msg"></p>
        </div>`;
    $('club-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        await guarded('msg-club', async () => {
            const created = await api(`/admin/meets/${state.meetId}/clubs`, 'POST', { name: $('club-name').value, default_country: $('club-country').value || null });
            await renderClubs();
            showCode(created);
        });
    });
    el.querySelectorAll('[data-rotate]').forEach(b => confirmClick(b, () => guarded('msg-clubs', async () => {
        const rotated = await api(`/admin/meets/${state.meetId}/clubs/${b.dataset.rotate}/code`, 'POST');
        await renderClubs();
        showCode(rotated);
    })));
    el.querySelectorAll('[data-revoke]').forEach(b => b.addEventListener('click', () => guarded('msg-clubs', async () => {
        await api(`/admin/meets/${state.meetId}/clubs/${b.dataset.revoke}/revoke`, 'POST', { revoked: b.dataset.revoked !== 'true' });
        await renderClubs();
    })));
    el.querySelectorAll('[data-delete]').forEach(b => confirmClick(b, () => guarded('msg-clubs', async () => {
        const withStarters = Number(b.dataset.starters) > 0 ? '?with_starters=true' : '';
        await api(`/admin/meets/${state.meetId}/clubs/${b.dataset.delete}${withStarters}`, 'DELETE');
        await renderClubs();
        await loadMeets();
    })));
}

/** Shows a new code with its invite link once, with copy buttons. */
function showCode(withCode) {
    const box = $('code-box');
    box.innerHTML = `<div class="codebox">
        <strong>${escapeHtml(t('admin.codeFor', { club: withCode.club.name }))}</strong>
        <span class="code">${escapeHtml(withCode.code)}</span>
        <span class="url">${escapeHtml(withCode.invite_url)}</span>
        <div class="btn-row">
            <button type="button" class="btn" data-copy="${escapeHtml(withCode.invite_url)}">${escapeHtml(t('admin.copyLink'))}</button>
            <button type="button" class="btn" data-copy="${escapeHtml(withCode.code)}">${escapeHtml(t('admin.copyCode'))}</button>
            <span class="section-msg" id="copy-state"></span>
        </div>
        <span class="muted fs-85">${escapeHtml(t('admin.codeOnce'))}</span>
    </div>`;
    box.querySelectorAll('[data-copy]').forEach(b => b.addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(b.dataset.copy);
            message('copy-state', t('admin.copied'), 'ok');
        } catch {
            message('copy-state', t('admin.copyFailed'), 'bad');
        }
    }));
    box.scrollIntoView({ block: 'nearest' });
}

async function guardedLoad(load) {
    try {
        return await load();
    } catch (e) {
        if (e instanceof ApiError && e.status === 401) location.href = '/login/';
        $(`tab-${currentTab()}`).innerHTML = `<p class="notice bad">${escapeHtml(e.message)}</p>`;
        return null;
    }
}

// --- starters -------------------------------------------------------------------------

async function renderStarters() {
    const el = $('tab-starters');
    const data = await guardedLoad(() => Promise.all([
        api(`/admin/meets/${state.meetId}/starters`), api(`/admin/meets/${state.meetId}/clubs`), api(`/admin/meets/${state.meetId}/offer`)]));
    if (!data) return;
    const [result, clubs, offer] = data;
    const entries = offer.flatMap(r => r.entries.map(e => ({ key: `${r.ranking_id}:${e.index}`, label: r.entries.length > 1 ? e.label : r.short_name, disciplines: e.disciplines })));
    const byKey = new Map(entries.map(e => [e.key, e]));
    const starts = (s) => new Set(s.choices.flatMap(c => byKey.get(`${c.ranking_id}:${c.entry}`)?.disciplines.map(d => d.id) || [])).size;
    const totalStarts = result.starters.reduce((n, s) => n + starts(s), 0);
    const counts = entries.map(e => ({ ...e, count: result.starters.filter(s => s.choices.some(c => `${c.ranking_id}:${c.entry}` === e.key)).length })).filter(e => e.count);
    el.innerHTML = `
        <div class="card">
            <h2>${escapeHtml(t('admin.startersOf', { meet: meet()?.name || '' }))}</h2>
            <p>${escapeHtml(t('admin.startersSummary', { starters: result.starters.length, starts: totalStarts, clubs: clubs.length }))}</p>
            <div class="chips">${counts.map(e => `<span class="chip">${escapeHtml(e.label)} ${e.count}</span>`).join('')}</div>
        </div>
        ${clubs.map(c => {
            const own = result.starters.filter(s => s.club_id === c.id);
            return `<div class="card">
                <div class="topbar mb-6">
                    <h2>${escapeHtml(c.name)} <span class="muted fw-normal">· ${escapeHtml(t('admin.startersCount', { count: own.length }))}</span></h2>
                    <a class="btn" href="/sheet/?meet=${state.meetId}&club=${c.id}">${escapeHtml(t('admin.editList'))}</a>
                </div>
                ${c.contact?.name || c.contact?.email ? `<p class="muted m-0 mb-8 fs-88">${escapeHtml(t('admin.contact'))}: ${escapeHtml([c.contact.name, c.contact.email].filter(Boolean).join(', '))}</p>` : ''}
                ${own.length ? `<div class="table-scroll"><table class="list">
                    <thead><tr><th>${escapeHtml(t('admin.name'))}</th><th>${escapeHtml(t('admin.year'))}</th><th>${escapeHtml(t('admin.country'))}</th>
                        <th>${escapeHtml(t('admin.rankings'))}</th><th>${escapeHtml(t('admin.notes'))}</th></tr></thead>
                    <tbody>${own.map(s => `<tr><td>${escapeHtml(s.name)}</td><td>${escapeHtml(s.year_of_birth ?? '')}</td><td>${escapeHtml(countryLabel(s.country))}</td>
                        <td><div class="chips">${s.choices.map(ch => `<span class="chip">${escapeHtml(byKey.get(`${ch.ranking_id}:${ch.entry}`)?.label || '?')}</span>`).join('')}</div></td>
                        <td>${(result.warnings[String(s.id)] || []).map(w => `<span class="st-warn">${escapeHtml(w)}</span>`).join('')}</td></tr>`).join('')}</tbody>
                </table></div>` : `<p class="muted">${escapeHtml(t('admin.noStarters'))}</p>`}
            </div>`;
        }).join('')}`;
}

// --- download ---------------------------------------------------------------------------

function renderDownload() {
    const m = meet();
    $('tab-download').innerHTML = `<div class="card">
        <h2>${escapeHtml(t('admin.downloadTitle'))}</h2>
        <p>${escapeHtml(t('admin.downloadSummary', { starters: m.starters, clubs: m.clubs }))}
            ${m.status === 'open' ? `<span class="pill open">${escapeHtml(t('admin.stillOpen'))}</span>` : ''}</p>
        <div class="btn-row"><a class="btn primary" href="/api/admin/meets/${m.id}/export" download>${escapeHtml(t('admin.downloadButton'))}</a></div>
        <ol class="muted steps">
            <li>${escapeHtml(t('admin.downloadStep1'))}</li><li>${escapeHtml(t('admin.downloadStep2'))}</li><li>${escapeHtml(t('admin.downloadStep3'))}</li>
        </ol>
    </div>`;
}

// --- data -----------------------------------------------------------------------------

async function renderData() {
    const el = $('tab-data');
    const m = meet();
    const data = await guardedLoad(() => Promise.all([api(`/admin/meets/${m.id}/audit?limit=200`), api(`/admin/meets/${m.id}/clubs`), api('/admin/organisers')]));
    if (!data) return;
    const [audit, clubs, organisers] = data;
    const actor = (a) => {
        const [kind, id] = String(a).split(':');
        if (kind === 'club') return clubs.find(c => c.id === Number(id))?.name || t('admin.deletedClub');
        return organisers.find(o => o.id === Number(id))?.username || a;
    };
    el.innerHTML = `
        <div class="card">
            <h2>${escapeHtml(t('admin.audit'))}</h2>
            <div class="table-scroll"><table class="list">
                <thead><tr><th>${escapeHtml(t('admin.when'))}</th><th>${escapeHtml(t('admin.who'))}</th><th>${escapeHtml(t('admin.what'))}</th><th></th></tr></thead>
                <tbody>${audit.map(a => `<tr><td>${escapeHtml(formatDateTime(a.at, m.time_zone))}</td><td>${escapeHtml(actor(a.actor))}</td>
                    <td>${escapeHtml(t('audit.' + a.action))}</td><td>${escapeHtml(a.summary || '')}</td></tr>`).join('')
                    || `<tr><td colspan="4" class="muted">${escapeHtml(t('admin.noAudit'))}</td></tr>`}</tbody>
            </table></div>
        </div>
        <div class="grid2">
            <div class="card danger">
                <h2>${escapeHtml(t('admin.purge'))}</h2>
                <p class="muted fs-88">${escapeHtml(t('admin.purgeHint', { date: m.delete_after || '–' }))}</p>
                <div class="field"><label for="purge-key">${escapeHtml(t('admin.typeKey', { key: m.key }))}</label><input id="purge-key" autocomplete="off"></div>
                <div class="btn-row mt-10"><button type="button" class="btn danger" id="purge">${escapeHtml(t('admin.purgeButton'))}</button></div>
                <p id="msg-purge" class="section-msg"></p>
            </div>
            <div class="card danger">
                <h2>${escapeHtml(t('admin.deleteMeet'))}</h2>
                <p class="muted fs-88">${escapeHtml(t('admin.deleteMeetHint'))}</p>
                <div class="field"><label for="delete-key">${escapeHtml(t('admin.typeKey', { key: m.key }))}</label><input id="delete-key" autocomplete="off"></div>
                <div class="btn-row mt-10"><button type="button" class="btn danger" id="delete-meet">${escapeHtml(t('admin.deleteMeetButton'))}</button></div>
                <p id="msg-delete" class="section-msg"></p>
            </div>
        </div>`;
    $('purge').addEventListener('click', () => guarded('msg-purge', async () => {
        const result = await api(`/admin/meets/${m.id}/starters?confirm=${encodeURIComponent($('purge-key').value.trim())}`, 'DELETE');
        await loadMeets(m.id);
        await renderData();
        message('msg-purge', t('admin.purged', { count: result.deleted }), 'ok');
    }));
    $('delete-meet').addEventListener('click', () => guarded('msg-delete', async () => {
        if ($('delete-key').value.trim() !== m.key) {
            message('msg-delete', t('admin.keyMismatch', { key: m.key }), 'bad');
            return;
        }
        await api(`/admin/meets/${m.id}`, 'DELETE');
        state.meetId = null;
        await loadMeets();
        location.hash = 'meets';
        showTab();
    }));
}

// --- account --------------------------------------------------------------------------

async function renderAccount() {
    const el = $('tab-account');
    const data = await guardedLoad(() => Promise.all([api('/session'), api('/admin/organisers'), api('/admin/settings')]));
    if (!data) return;
    const [session, organisers, settings] = data;
    const c = settings.controller || {};
    el.innerHTML = `
        <div class="grid2">
            <form class="card" id="pw-form" novalidate>
                <h2>${escapeHtml(t('admin.password'))}</h2>
                <div class="fields">
                    <div class="field"><label for="pw-current">${escapeHtml(t('admin.currentPassword'))}</label><input id="pw-current" type="password" autocomplete="current-password"></div>
                    <div class="field"><label for="pw-new">${escapeHtml(t('admin.newPassword'))}</label><input id="pw-new" type="password" autocomplete="new-password" minlength="12"></div>
                </div>
                <div class="btn-row mt-10"><button type="submit" class="btn primary">${escapeHtml(t('admin.changePassword'))}</button></div>
                <p id="msg-pw" class="section-msg"></p>
            </form>
            <div class="card">
                <h2>${escapeHtml(t('admin.totp'))}</h2>
                <p>${session.totp_enabled ? `<span class="pill open">${escapeHtml(t('admin.totpOn'))}</span>` : `<span class="pill">${escapeHtml(t('admin.totpOff'))}</span>`}</p>
                <p class="muted fs-88">${escapeHtml(t('admin.totpHint'))}</p>
                ${session.totp_enabled ? `
                    <div class="field"><label for="totp-pw">${escapeHtml(t('admin.currentPassword'))}</label><input id="totp-pw" type="password" autocomplete="current-password"></div>
                    <div class="btn-row mt-10"><button type="button" class="btn danger" id="totp-off">${escapeHtml(t('admin.totpDisable'))}</button></div>`
                    : `<div class="btn-row"><button type="button" class="btn" id="totp-setup">${escapeHtml(t('admin.totpSetup'))}</button></div><div id="totp-box"></div>`}
                <p id="msg-totp" class="section-msg"></p>
            </div>
        </div>
        <div class="grid2">
            <div class="card">
                <h2>${escapeHtml(t('admin.organisers'))}</h2>
                <table class="list"><tbody>${organisers.map(o => `<tr><td>${escapeHtml(o.username)}${o.id === session.id ? ` <span class="muted">(${escapeHtml(t('admin.you'))})</span>` : ''}</td>
                    <td>${o.totp_enabled ? `<span class="pill open">${escapeHtml(t('admin.totpShort'))}</span>` : ''}</td>
                    <td>${o.id !== session.id ? `<button type="button" class="btn danger" data-del-org="${o.id}">${escapeHtml(t('admin.delete'))}</button>` : ''}</td></tr>`).join('')}</tbody></table>
                <form id="org-form" novalidate class="mt-12">
                    <div class="fields">
                        <div class="field"><label for="org-name">${escapeHtml(t('login.username'))}</label><input id="org-name" autocomplete="off" autocapitalize="none"></div>
                        <div class="field"><label for="org-pw">${escapeHtml(t('admin.initialPassword'))}</label><input id="org-pw" type="password" autocomplete="new-password"></div>
                    </div>
                    <div class="btn-row mt-10"><button type="submit" class="btn">${escapeHtml(t('admin.addOrganiser'))}</button></div>
                    <p id="msg-org" class="section-msg"></p>
                </form>
            </div>
            <form class="card" id="settings-form" novalidate>
                <h2>${escapeHtml(t('admin.controller'))}</h2>
                <p class="muted fs-88">${escapeHtml(t('admin.controllerHint'))}</p>
                <div class="fields">
                    <div class="field"><label for="s-name">${escapeHtml(t('admin.name'))}</label><input id="s-name" value="${escapeHtml(c.name || '')}"></div>
                    <div class="field"><label for="s-address">${escapeHtml(t('admin.address'))}</label><input id="s-address" value="${escapeHtml(c.address || '')}"></div>
                    <div class="field"><label for="s-email">${escapeHtml(t('admin.contactEmail'))}</label><input id="s-email" type="email" value="${escapeHtml(c.email || '')}"></div>
                </div>
                <div class="btn-row mt-10"><button type="submit" class="btn primary">${escapeHtml(t('admin.save'))}</button></div>
                <p id="msg-settings" class="section-msg"></p>
            </form>
        </div>`;

    $('pw-form').addEventListener('submit', (e) => {
        e.preventDefault();
        guarded('msg-pw', async () => {
            await api('/admin/password', 'PUT', { current: $('pw-current').value, password: $('pw-new').value });
            $('pw-current').value = '';
            $('pw-new').value = '';
            message('msg-pw', t('admin.passwordChanged'), 'ok');
        });
    });
    $('totp-setup')?.addEventListener('click', () => guarded('msg-totp', async () => {
        const setup = await api('/admin/totp/setup', 'POST');
        $('totp-box').innerHTML = `<p class="muted fs-88">${escapeHtml(t('admin.totpScan'))}</p>
            <div class="qr">${qrSvg(setup.otpauth_uri, 45)}</div>
            <p class="fs-80 break-all"><span class="muted">${escapeHtml(t('admin.totpSecret'))}:</span> <span class="chip">${escapeHtml(setup.secret)}</span></p>
            <div class="field"><label for="totp-code">${escapeHtml(t('login.totp'))}</label><input id="totp-code" inputmode="numeric" autocomplete="one-time-code" maxlength="7"></div>
            <div class="btn-row mt-10"><button type="button" class="btn primary" id="totp-confirm">${escapeHtml(t('admin.totpConfirm'))}</button></div>`;
        $('totp-confirm').addEventListener('click', () => guarded('msg-totp', async () => {
            await api('/admin/totp/confirm', 'POST', { code: $('totp-code').value });
            await renderAccount();
            message('msg-totp', t('admin.totpNowOn'), 'ok');
        }));
    }));
    $('totp-off')?.addEventListener('click', () => guarded('msg-totp', async () => {
        await api('/admin/totp/disable', 'POST', { password: $('totp-pw').value });
        await renderAccount();
    }));
    el.querySelectorAll('[data-del-org]').forEach(b => confirmClick(b, () => guarded('msg-org', async () => {
        await api(`/admin/organisers/${b.dataset.delOrg}`, 'DELETE');
        await renderAccount();
    })));
    $('org-form').addEventListener('submit', (e) => {
        e.preventDefault();
        guarded('msg-org', async () => {
            await api('/admin/organisers', 'POST', { username: $('org-name').value, password: $('org-pw').value });
            await renderAccount();
            message('msg-org', t('admin.organiserAdded'), 'ok');
        });
    });
    $('settings-form').addEventListener('submit', (e) => {
        e.preventDefault();
        guarded('msg-settings', async () => {
            await api('/admin/settings', 'PUT', { controller: { name: $('s-name').value, address: $('s-address').value, email: $('s-email').value } });
            message('msg-settings', t('admin.saved'), 'ok');
        });
    });
}

start();
