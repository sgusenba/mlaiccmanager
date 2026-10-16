// Registration demo: how an online registration form for starters could
// look. It reads the meet (/api/meet), its rankings (/api/rankings) and the
// discipline catalog (/api/available-disciplines), offers every individual
// ranking, and never writes anything: "Register" only shows what would be sent.
//
// A ranking is one of three kinds:
//   single  one entry with one discipline, e.g. 4_Whitworth_O
//   choice  several entries ranked together, the starter picks one of them
//   combo   one entry that sums several disciplines, e.g. Remington_O = Colt + Donald Malson_O;
//           choosing it registers for all of them

import { loadMeet, escapeHtml } from '../js/meet.js';
import { categoryLabel } from '../js/teamRanking.js';
import { getCountries } from '../js/countries.js';
import { t, tOr } from '../js/i18n.js';

document.title = `${t('register.title')} · MLAICC Manager`;

const state = {
    variant: 'list',
    rankings: [],     // individual rankings, with kind, category, discipline ids per entry
    disciplines: new Map(),
    selected: new Map(), // ranking id -> chosen entry index
    filter: { category: 'all', type: 'all', query: '' },
    step: 1,
    consent: false,
    sent: false,
    person: { name: '', gender: '', year: '', club: '', country: '', email: '', phone: '', address: '' }
};

// --- data ----------------------------------------------------------------

async function getJson(path) {
    const response = await fetch(`/api${path}`);
    if (!response.ok) throw new Error(t('common.loadFailed', { path, status: response.status }));
    return response.json();
}

const disciplineName = (id) => state.disciplines.get(id)?.name ?? `#${id}`;
const entryLabel = (entry) => entry.label || entry.discipline_ids.map(disciplineName).join(' + ');

// "m50" (the catalog's range id) reads as "50 m"; other range names stay as they are
const distanceLabel = (range) => {
    const match = String(range ?? '').match(/^m(\d+)$/i);
    return match ? `${match[1]} m` : (range || '');
};

// Splits "4_Whitworth_O" into number 4, model "Whitworth" and variant "O"
function nameParts(name) {
    const match = String(name).match(/^(?:(\d+)_)?(.+?)(?:[ _](O|R|RS|RU))?$/);
    return { num: match?.[1] ?? '', model: match?.[2] ?? name, variant: match?.[3] ?? '' };
}

function prepare(ranking) {
    const entries = ranking.entries || [];
    const ids = [...new Set(entries.flatMap(e => e.discipline_ids))];
    const discs = ids.map(id => state.disciplines.get(id)).filter(Boolean);
    const kind = entries.length > 1 ? 'choice' : (entries[0]?.discipline_ids.length > 1 ? 'combo' : 'single');
    const categories = [...new Set(discs.map(d => d.category).filter(Boolean))].sort();
    const distances = [...new Set(discs.map(d => distanceLabel(d.shooting_distance)).filter(Boolean))];
    const types = [...new Set(discs.map(d => d.type).filter(Boolean))];
    return { ...ranking, kind, category: categories.join(' / '), distances, types, ...nameParts(ranking.name) };
}

async function load() {
    const [meet, config, catalog] = await Promise.all([
        loadMeet().catch(() => ({})),
        getJson('/rankings'),
        getJson('/available-disciplines')
    ]);
    (catalog || []).forEach(d => state.disciplines.set(d.id, d));
    state.rankings = (config.rankings || [])
        .filter(r => String(r.level || 'individual').toLowerCase() !== 'team')
        .filter(r => (r.entries || []).length)
        .map(prepare);
    renderMeet(meet);
}

function renderMeet(meet) {
    document.getElementById('meet-name').textContent = meet.name || t('register.noMeetName');
    document.getElementById('meet-facts').innerHTML = [meet.location, meet.dateText, meet.host && t('register.host', { host: meet.host })]
        .filter(Boolean).map(text => `<span>${escapeHtml(text)}</span>`).join('');
}

// --- selection -----------------------------------------------------------

const rankingById = (id) => state.rankings.find(r => r.id === id);
const combos = () => state.rankings.filter(r => r.kind === 'combo');

function toggle(id, entry = 0) {
    if (state.selected.get(id) === entry) state.selected.delete(id);
    else state.selected.set(id, entry);
    state.sent = false;
    render();
}

/** The disciplines the starter would start in: every discipline of every chosen entry. */
function startIds() {
    const ids = new Set();
    state.selected.forEach((entry, id) => rankingById(id)?.entries[entry]?.discipline_ids.forEach(d => ids.add(d)));
    return [...ids];
}

/** The chosen combined rankings that bring a discipline in. */
const viaCombos = (disciplineId) => combos()
    .filter(c => state.selected.has(c.id) && c.entries[0].discipline_ids.includes(disciplineId))
    .map(c => c.name);

/** Whether a single ranking's discipline is in the starts, chosen directly or through a combined ranking. */
const isCovered = (ranking) => state.selected.has(ranking.id)
    || (ranking.kind === 'single' && viaCombos(ranking.entries[0].discipline_ids[0]).length > 0);

function matches(ranking) {
    const { category, type, query } = state.filter;
    if (category !== 'all' && !ranking.category.split(' / ').includes(category)) return false;
    if (type !== 'all' && !ranking.types.includes(type)) return false;
    if (query && !`${ranking.name} ${ranking.short_name || ''}`.toLowerCase().includes(query.toLowerCase())) return false;
    return true;
}

function missing() {
    const list = [];
    if (!state.person.name.trim()) list.push(t('competitors.name'));
    if (!state.person.country) list.push(t('competitors.country'));
    if (!state.person.email.trim()) list.push(t('competitors.email'));
    if (!startIds().length) list.push(t('register.missing.atLeastOne'));
    return list;
}

// --- shared parts --------------------------------------------------------

const categoriesInUse = () => [...new Set(state.rankings.flatMap(r => r.category.split(' / ')).filter(Boolean))].sort();
const typesInUse = () => [...new Set(state.rankings.flatMap(r => r.types))].sort();
const distanceTags = (r) => r.distances.map(d => `<span class="reg-tag text-gray-500">${escapeHtml(d)}</span>`).join(' ');

function personPanel() {
    const p = state.person;
    const input = (key, label, type = 'text', required = false) => `
        <div>
            <label for="reg-${key}" class="block text-sm font-medium text-gray-700 mb-1">${escapeHtml(label)}${required ? ' *' : ''}</label>
            <input id="reg-${key}" data-person="${key}" type="${type}" value="${escapeHtml(p[key])}" ${type === 'number' ? 'min="1900" step="1"' : ''}
                   class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">
        </div>`;
    const genders = ['male', 'female', 'other'].map(g => `<option value="${g}" ${p.gender === g ? 'selected' : ''}>${escapeHtml(t(`gender.${g}`))}</option>`).join('');
    const countries = getCountries().map(c => `<option value="${escapeHtml(c.value)}" ${p.country === c.value ? 'selected' : ''}>${escapeHtml(c.label)}</option>`).join('');
    return `
        <section class="bg-white rounded-lg shadow-md p-6">
            <h3 class="text-lg font-semibold mb-4">${escapeHtml(t('register.personal'))}</h3>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                ${input('name', t('register.fullName'), 'text', true)}
                <div>
                    <label for="reg-gender" class="block text-sm font-medium text-gray-700 mb-1">${escapeHtml(t('competitors.gender'))}</label>
                    <select id="reg-gender" data-person="gender" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">
                        <option value="">${escapeHtml(t('competitors.form.selectGender'))}</option>${genders}
                    </select>
                </div>
                ${input('year', t('competitors.yearOfBirth'), 'number')}
                ${input('club', t('competitors.club'))}
                <div>
                    <label for="reg-country" class="block text-sm font-medium text-gray-700 mb-1">${escapeHtml(t('competitors.country'))} *</label>
                    <select id="reg-country" data-person="country" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">
                        <option value="">${escapeHtml(t('competitors.form.selectCountry'))}</option>${countries}
                    </select>
                </div>
                ${input('email', t('competitors.email'), 'email', true)}
                ${input('phone', t('competitors.phone'), 'tel')}
                <div class="md:col-span-2">
                    <label for="reg-address" class="block text-sm font-medium text-gray-700 mb-1">${escapeHtml(t('competitors.address'))}</label>
                    <textarea id="reg-address" data-person="address" rows="2" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">${escapeHtml(p.address)}</textarea>
                </div>
            </div>
        </section>`;
}

function filterBar() {
    const f = state.filter;
    const chip = (group, value, label) =>
        `<button type="button" class="reg-chip" data-filter="${group}" data-value="${escapeHtml(value)}" aria-pressed="${f[group] === value}">${escapeHtml(label)}</button>`;
    const categories = categoriesInUse();
    const types = typesInUse();
    return `
        <div class="flex flex-wrap items-center gap-2 my-4">
            ${categories.length > 1 ? `<div class="flex flex-wrap gap-2" role="group">${chip('category', 'all', t('register.filter.allCategories'))}${categories.map(c => chip('category', c, categoryLabel(c))).join('')}</div>` : ''}
            ${types.length > 1 ? `<div class="flex flex-wrap gap-2" role="group">${chip('type', 'all', t('register.filter.allTypes'))}${types.map(ty => chip('type', ty, tOr(`type.${ty}`, ty))).join('')}</div>` : ''}
            <input id="reg-search" type="search" value="${escapeHtml(f.query)}" placeholder="${escapeHtml(t('register.searchPlaceholder'))}" aria-label="${escapeHtml(t('common.search'))}"
                   class="flex-1 min-w-[12rem] px-3 py-1 border border-gray-300 rounded-full text-sm">
        </div>`;
}

function combosPanel(framed = true) {
    const list = combos().filter(matches);
    if (!list.length) return '';
    return `
        <section class="${framed ? 'bg-white rounded-lg shadow-md p-6' : ''}">
            <div class="flex flex-wrap items-baseline justify-between gap-2 mb-3">
                <h3 class="text-lg font-semibold">${escapeHtml(t('register.combos'))}</h3>
                <span class="reg-eyebrow">${escapeHtml(t('register.combosHint'))}</span>
            </div>
            <div class="reg-combos">${list.map(c => `
                <label class="reg-combo ${state.selected.has(c.id) ? 'is-selected' : ''}">
                    <input type="checkbox" class="mt-1 h-4 w-4" data-ranking="${c.id}" ${state.selected.has(c.id) ? 'checked' : ''}>
                    <span class="font-medium">${escapeHtml(c.name)} ${c.short_name ? `<span class="text-xs text-gray-500">${escapeHtml(c.short_name)}</span>` : ''}</span>
                    <small class="col-start-2 text-gray-500">${escapeHtml(t('register.comboEnters', { list: c.entries[0].discipline_ids.map(disciplineName).join(' + ') }))}</small>
                </label>`).join('')}
            </div>
        </section>`;
}

function summaryPanel(withSubmit) {
    const ids = startIds();
    const chosenCombos = combos().filter(c => state.selected.has(c.id));
    // Combined rankings whose disciplines are all chosen already, but not the combined ranking itself
    const directIds = new Set();
    state.selected.forEach((entry, id) => {
        const r = rankingById(id);
        if (r && r.kind !== 'combo') r.entries[entry].discipline_ids.forEach(d => directIds.add(d));
    });
    const suggestions = combos().filter(c => !state.selected.has(c.id) && c.entries[0].discipline_ids.every(d => directIds.has(d)));

    const startRows = ids.map(id => {
        const via = viaCombos(id);
        const own = state.rankings.find(r => r.kind === 'single' && state.selected.has(r.id) && r.entries[0].discipline_ids[0] === id);
        return `<li class="flex justify-between gap-2 items-start text-sm">
            <span>${escapeHtml(disciplineName(id))}${via.length ? `<br><small class="text-blue-700">${escapeHtml(t('register.via', { list: via.join(', ') }))}</small>` : ''}</span>
            ${own ? `<button type="button" class="text-gray-400 hover:text-red-600 px-1" data-ranking="${own.id}" aria-label="${escapeHtml(t('register.removeStart', { name: own.name }))}" title="${escapeHtml(t('common.remove'))}">×</button>` : ''}
        </li>`;
    }).join('');

    return `
        <aside class="reg-summary bg-white rounded-lg shadow-md p-6 space-y-4" aria-live="polite">
            <div>
                <p class="reg-eyebrow">${escapeHtml(t('register.yourStarts'))}</p>
                <p class="text-4xl font-bold text-gray-900">${ids.length}</p>
            </div>
            ${ids.length ? `<ul class="space-y-1">${startRows}</ul>` : `<p class="text-sm text-gray-500">${escapeHtml(t('register.noStarts'))}</p>`}
            ${chosenCombos.length ? `<div><p class="reg-eyebrow mb-1">${escapeHtml(t('register.combos'))}</p><ul class="space-y-1">${chosenCombos.map(c => `
                <li class="flex justify-between gap-2 text-sm"><span>${escapeHtml(c.name)}</span>
                <button type="button" class="text-gray-400 hover:text-red-600 px-1" data-ranking="${c.id}" aria-label="${escapeHtml(t('register.removeStart', { name: c.name }))}" title="${escapeHtml(t('common.remove'))}">×</button></li>`).join('')}</ul></div>` : ''}
            ${suggestions.map(c => `
                <div class="text-sm bg-blue-50 border border-blue-200 rounded-md px-3 py-2">
                    ${escapeHtml(t('register.suggest', { list: c.entries[0].discipline_ids.map(disciplineName).join(' + ') }))}
                    <button type="button" class="font-semibold text-blue-700 underline" data-ranking="${c.id}">${escapeHtml(t('register.suggestAdd', { name: c.name }))}</button>
                </div>`).join('')}
            ${withSubmit ? submitBlock() : ''}
        </aside>`;
}

function submitBlock() {
    if (state.sent) {
        return `
            <div class="bg-green-50 border border-green-200 text-green-800 rounded-md px-3 py-3 text-sm space-y-2">
                <p class="font-semibold">${escapeHtml(t('register.sentTitle'))}</p>
                <p>${escapeHtml(t('register.sentText', { count: startIds().length, email: state.person.email }))}</p>
                <button type="button" id="reg-reset" class="bg-white border border-green-300 text-green-800 px-3 py-1 rounded-md hover:bg-green-100">${escapeHtml(t('register.reset'))}</button>
            </div>`;
    }
    const open = missing();
    return `
        <label class="flex gap-2 items-start text-sm text-gray-600">
            <input type="checkbox" id="reg-consent" class="mt-1 h-4 w-4" ${state.consent ? 'checked' : ''}>
            <span>${escapeHtml(t('register.consent'))}</span>
        </label>
        ${open.length ? `<p class="text-sm text-red-700">${escapeHtml(t('register.missing', { list: open.join(', ') }))}</p>` : ''}
        <button type="button" id="reg-submit" class="w-full bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                ${open.length || !state.consent ? 'disabled' : ''}>${escapeHtml(t('register.submit'))}</button>`;
}

// Buttons for one card or row: one per entry of a choice ranking, one per variant of a firearm
function entryButtons(ranking) {
    return ranking.entries.map((entry, i) => `
        <button type="button" data-ranking="${ranking.id}" data-entry="${i}" aria-pressed="${state.selected.get(ranking.id) === i}">
            ${escapeHtml(entryLabel(entry))}
        </button>`).join('');
}

const noMatch = () => `<p class="text-sm text-gray-500 py-4">${escapeHtml(t('register.noMatch'))}</p>`;

// Single and choice rankings grouped by category, in the meet's ranking order
function byCategory(list) {
    const groups = new Map();
    list.forEach(r => {
        const key = r.category || '';
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(r);
    });
    return [...groups.entries()];
}

// --- variant: list ---------------------------------------------------------

function listVariant() {
    const list = state.rankings.filter(r => r.kind !== 'combo' && matches(r));
    const rows = byCategory(list).map(([category, rankings]) => `
        <div class="flex items-baseline gap-2 pt-4 pb-1 px-2">
            <h4 class="font-semibold">${escapeHtml(category ? categoryLabel(category) : t('common.unknown'))}</h4>
            <span class="reg-eyebrow">${escapeHtml(t('register.count', { count: rankings.length }))}</span>
        </div>
        ${rankings.map(r => r.kind === 'choice' ? `
            <div class="reg-row ${state.selected.has(r.id) ? 'is-selected' : ''}" style="cursor:default">
                <span></span>
                <span class="min-w-0"><span class="font-medium">${escapeHtml(r.name)}</span>
                    <small class="block text-gray-500">${escapeHtml(t('register.chooseOne'))}</small>
                    <span class="reg-seg mt-1">${entryButtons(r)}</span></span>
                <span>${distanceTags(r)}</span>
            </div>` : `
            <label class="reg-row ${isCovered(r) ? 'is-selected' : ''}">
                <input type="checkbox" class="h-4 w-4" data-ranking="${r.id}" ${state.selected.has(r.id) ? 'checked' : ''}>
                <span class="min-w-0"><span class="font-medium">${escapeHtml(r.name)}</span>
                    <small class="block text-gray-500">${escapeHtml([r.short_name, ...viaCombos(r.entries[0].discipline_ids[0]).map(n => t('register.via', { list: n }))].filter(Boolean).join(' · '))}</small></span>
                <span>${distanceTags(r)}</span>
            </label>`).join('')}`).join('');

    return `
        <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_20rem] gap-6 items-start">
            <div class="space-y-6 min-w-0">
                ${personPanel()}
                <section class="bg-white rounded-lg shadow-md p-6">
                    <div class="flex flex-wrap items-baseline justify-between gap-2">
                        <h3 class="text-lg font-semibold">${escapeHtml(t('register.rankings'))}</h3>
                        <span class="reg-eyebrow">${escapeHtml(t('register.multiSelect'))}</span>
                    </div>
                    ${filterBar()}
                    <div class="border-t border-gray-200">${rows || noMatch()}</div>
                </section>
                ${combosPanel()}
            </div>
            ${summaryPanel(true)}
        </div>`;
}

// --- variant: cards per firearm ---------------------------------------------

// Single rankings of the same firearm (same number and model, e.g. 4_Whitworth_O and _R) share a card
function firearmCards(list) {
    const cards = [];
    list.forEach(r => {
        const key = r.kind === 'single' ? `${r.num}|${r.model}` : `ranking-${r.id}`;
        let card = cards.find(c => c.key === key);
        if (!card) cards.push(card = { key, num: r.num, model: r.kind === 'single' ? r.model : r.name, category: r.category, rankings: [] });
        card.rankings.push(r);
    });
    return cards;
}

function cardButtons(card) {
    if (card.rankings.length === 1 && card.rankings[0].kind === 'choice') return entryButtons(card.rankings[0]);
    return card.rankings.map(r => {
        const disc = state.disciplines.get(r.entries[0].discipline_ids[0]);
        return `<button type="button" data-ranking="${r.id}" aria-pressed="${isCovered(r)}" title="${escapeHtml(r.name)}">
            ${escapeHtml(r.variant || t('register.take'))}<small>${escapeHtml(disc?.type ? tOr(`type.${disc.type}`, disc.type) : (r.short_name || ''))}</small>
        </button>`;
    }).join('');
}

function cardsVariant() {
    const list = state.rankings.filter(r => r.kind !== 'combo' && matches(r));
    const sections = byCategory(list).map(([category, rankings]) => `
        <div class="space-y-3">
            <div class="flex items-baseline justify-between gap-2">
                <h4 class="font-semibold">${escapeHtml(category ? categoryLabel(category) : t('common.unknown'))}</h4>
            </div>
            <div class="reg-cards">${firearmCards(rankings).map(card => {
                const on = card.rankings.some(isCovered);
                const distances = [...new Set(card.rankings.flatMap(r => r.distances))];
                return `
                <div class="reg-card ${on ? 'is-selected' : ''}">
                    <div class="flex gap-3 items-baseline">
                        <span class="reg-card-num">${escapeHtml(card.num)}</span>
                        <div class="min-w-0">
                            <p class="font-semibold">${escapeHtml(card.model)}</p>
                            <div class="flex flex-wrap gap-1 mt-1">${distances.map(d => `<span class="reg-tag text-gray-500">${escapeHtml(d)}</span>`).join('')}
                                ${card.rankings[0].kind === 'choice' ? `<span class="text-xs text-gray-500">${escapeHtml(t('register.chooseOne'))}</span>` : ''}</div>
                        </div>
                    </div>
                    <div class="reg-seg">${cardButtons(card)}</div>
                </div>`;
            }).join('')}</div>
        </div>`).join('');

    return `
        <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_20rem] gap-6 items-start">
            <div class="space-y-6 min-w-0">
                ${personPanel()}
                <section class="bg-white rounded-lg shadow-md p-6">
                    <h3 class="text-lg font-semibold">${escapeHtml(t('register.rankings'))}</h3>
                    <p class="text-sm text-gray-600 mt-1">${escapeHtml(t('register.cardsHint'))}</p>
                    ${filterBar()}
                    <div class="space-y-6">${sections || noMatch()}</div>
                </section>
                ${combosPanel()}
            </div>
            ${summaryPanel(true)}
        </div>`;
}

// --- variant: step by step ---------------------------------------------------

function wizardVariant() {
    const steps = ['register.step.personal', 'register.step.rankings', 'register.step.review'];
    const head = `<nav class="reg-steps mb-4" aria-label="${escapeHtml(t('register.variant.wizard'))}">${steps.map((key, i) => {
        const n = i + 1;
        const cls = n < state.step ? 'is-done' : n === state.step ? 'is-current' : '';
        return `<button type="button" class="reg-step ${cls}" data-step="${n}" ${n === state.step ? 'aria-current="step"' : ''}>
            <span class="n">${n < state.step ? '✓' : n}</span>${escapeHtml(t(key))}</button>`;
    }).join('')}</nav>`;

    let body;
    if (state.step === 1) {
        body = personPanel();
    } else if (state.step === 2) {
        const list = state.rankings.filter(r => r.kind !== 'combo' && matches(r));
        const chosenCombos = combos().filter(c => state.selected.has(c.id)).length;
        body = `
            <section class="bg-white rounded-lg shadow-md p-6">
                <h3 class="text-lg font-semibold">${escapeHtml(t('register.whichRankings'))}</h3>
                ${filterBar()}
                <div class="space-y-3">
                ${byCategory(list).map(([category, rankings]) => {
                    const chosen = rankings.filter(isCovered).length;
                    return `
                    <details class="border border-gray-200 rounded-md" open>
                        <summary class="cursor-pointer px-4 py-3 flex justify-between gap-2 font-semibold">
                            <span>${escapeHtml(category ? categoryLabel(category) : t('common.unknown'))}</span>
                            <span class="reg-eyebrow">${escapeHtml(t('register.chosen', { count: chosen }))}</span>
                        </summary>
                        <div class="px-4 pb-3">${firearmCards(rankings).map(card => `
                            <div class="grid grid-cols-[minmax(0,1fr)_auto] gap-3 items-center py-2 border-t border-gray-100">
                                <span class="min-w-0"><span class="font-medium">${escapeHtml(`${card.num} ${card.model}`.trim())}</span>
                                    ${[...new Set(card.rankings.flatMap(r => r.distances))].map(d => `<span class="reg-tag text-gray-500 ml-1">${escapeHtml(d)}</span>`).join('')}</span>
                                <div class="reg-seg">${cardButtons(card)}</div>
                            </div>`).join('')}
                        </div>
                    </details>`;
                }).join('') || noMatch()}
                ${combos().length ? `
                    <details class="border border-gray-200 rounded-md">
                        <summary class="cursor-pointer px-4 py-3 flex justify-between gap-2 font-semibold">
                            <span>${escapeHtml(t('register.combos'))}</span>
                            <span class="reg-eyebrow">${escapeHtml(t('register.chosen', { count: chosenCombos }))}</span>
                        </summary>
                        <div class="px-4 pb-4">${combosPanel(false)}</div>
                    </details>` : ''}
                </div>
            </section>`;
    } else {
        const p = state.person;
        const countryName = getCountries().find(c => c.value === p.country)?.label || p.country;
        const row = (label, value) => `<dt class="text-gray-500">${escapeHtml(label)}</dt><dd>${escapeHtml(value || '–')}</dd>`;
        body = `
            <section class="bg-white rounded-lg shadow-md p-6 space-y-4">
                <h3 class="text-lg font-semibold">${escapeHtml(t('register.review'))}</h3>
                <dl class="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm">
                    ${row(t('competitors.name'), p.name)}
                    ${row(t('competitors.club'), p.club)}
                    ${row(t('competitors.country'), countryName)}
                    ${row(t('competitors.email'), p.email)}
                    ${row(t('register.starts'), startIds().map(disciplineName).join(', '))}
                    ${row(t('register.combos'), combos().filter(c => state.selected.has(c.id)).map(c => c.name).join(', '))}
                </dl>
                <div class="space-y-3 max-w-md">${submitBlock()}</div>
            </section>`;
    }

    const nav = `
        <div class="flex justify-between gap-3">
            ${state.step > 1 ? `<button type="button" class="bg-white border border-gray-300 text-gray-700 px-4 py-2 rounded-md hover:bg-gray-50" data-step="${state.step - 1}">${escapeHtml(t('register.back'))}</button>` : '<span></span>'}
            ${state.step < 3 ? `<button type="button" class="bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700" data-step="${state.step + 1}">${escapeHtml(t('register.next'))}</button>` : ''}
        </div>`;

    return `${head}
        <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_20rem] gap-6 items-start">
            <div class="space-y-4 min-w-0">${body}${nav}</div>
            ${summaryPanel(false)}
        </div>`;
}

// --- rendering and events ------------------------------------------------------

const VARIANTS = { list: listVariant, cards: cardsVariant, wizard: wizardVariant };
const app = document.getElementById('app');

function render() {
    document.querySelectorAll('[data-variant]').forEach(b => b.setAttribute('aria-pressed', b.dataset.variant === state.variant));
    document.getElementById('variant-intro').textContent = t(`register.variant.${state.variant}Intro`);
    if (!state.rankings.length) {
        app.innerHTML = `<p class="px-4 py-3 rounded-md bg-blue-50 text-blue-800 border border-blue-200 text-sm">${t('register.noRankings')}</p>`;
        return;
    }
    // Keep focus and caret in the field being typed in, since the page is redrawn on every change
    const active = document.activeElement;
    const focusId = active && app.contains(active) ? active.id : null;
    const caret = focusId && typeof active.selectionStart === 'number' ? active.selectionStart : null;
    app.innerHTML = VARIANTS[state.variant]();
    const again = focusId && document.getElementById(focusId);
    if (again) {
        again.focus();
        if (caret !== null) { try { again.setSelectionRange(caret, caret); } catch { /* not a text field */ } }
    }
}

document.querySelector('.reg-switch').addEventListener('click', (e) => {
    const button = e.target.closest('[data-variant]');
    if (!button) return;
    state.variant = button.dataset.variant;
    render();
});

app.addEventListener('click', (e) => {
    const target = e.target.closest('button');
    if (!target) return;
    if (target.dataset.ranking) toggle(Number(target.dataset.ranking), Number(target.dataset.entry || 0));
    else if (target.dataset.filter) { state.filter[target.dataset.filter] = target.dataset.value; render(); }
    else if (target.dataset.step) { state.step = Number(target.dataset.step); render(); }
    else if (target.id === 'reg-submit') { state.sent = true; render(); }
    else if (target.id === 'reg-reset') {
        state.selected.clear();
        state.consent = false;
        state.sent = false;
        state.step = 1;
        Object.keys(state.person).forEach(key => { state.person[key] = ''; });
        render();
    }
});

// Typing in the personal details only redraws the summary (what is still missing), so the field keeps focus and caret
function updatePerson(field) {
    state.person[field.dataset.person] = field.value;
    state.sent = false;
    const summary = app.querySelector('.reg-summary');
    if (summary) summary.outerHTML = summaryPanel(state.variant !== 'wizard');
}

app.addEventListener('change', (e) => {
    const target = e.target;
    if (target.matches('input[type="checkbox"][data-ranking]')) toggle(Number(target.dataset.ranking));
    else if (target.id === 'reg-consent') { state.consent = target.checked; render(); }
    else if (target.dataset.person) updatePerson(target);
});

app.addEventListener('input', (e) => {
    const target = e.target;
    if (target.id === 'reg-search') { state.filter.query = target.value; render(); }
    else if (target.dataset.person) updatePerson(target);
});

load()
    .then(render)
    .catch((error) => {
        const el = document.getElementById('message');
        el.textContent = t('common.loadError', { error: error.message });
        el.className = 'mx-4 mb-4 px-4 py-3 rounded-md bg-red-100 text-red-800 border border-red-200';
    });
