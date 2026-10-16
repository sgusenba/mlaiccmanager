import { t, tOr } from '../js/i18n.js';

const API = '/api';

// What can be cleared, in the order the server lists it (DangerZoneService.TARGETS).
// The texts are the messages danger.<target>.title (the name), .what (the data,
// in the confirm dialog) and .description; `reset` makes the button reset
// instead of clear; `count` turns the server's counts into the line shown
// next to the name.
const ITEMS = [
    { target: 'results', count: c => t('danger.count.results', { count: c.results }) },
    { target: 'lanes', count: c => t('danger.count.lanes', { count: c.lanes }) },
    { target: 'starts', count: c => t('danger.count.starts', { count: c.starts }) },
    { target: 'competitors', count: c => t('danger.count.competitors', { count: c.competitors }) },
    { target: 'teams', count: c => t('danger.count.teams', { count: c.teams }) },
    {
        target: 'days',
        count: c => `${t('danger.count.days', { count: c.days })}, ${t('danger.count.relays', { count: c.relays })}`
    },
    { target: 'meet', count: c => (c.meet ? t('danger.count.meetSet') : t('danger.count.meetEmpty')) },
    { target: 'rankings', count: c => t('danger.count.rankings', { count: c.rankings }) },
    { target: 'disciplines', reset: true, count: c => t('danger.count.disciplines', { count: c.disciplines }) }
].map(item => ({
    ...item,
    title: t(`danger.${item.target}.title`),
    what: t(`danger.${item.target}.what`),
    description: t(`danger.${item.target}.description`),
    // picks the danger.clear.* or danger.reset.* messages, e.g. "Clear {title}"
    verb: item.reset ? 'reset' : 'clear'
}));

let messageTimer;
function showMessage(text, type = 'error') {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = `mx-4 mb-4 px-4 py-3 rounded-md max-w-5xl ${type === 'error'
        ? 'bg-red-100 text-red-800 border border-red-200'
        : 'bg-green-100 text-green-800 border border-green-200'}`;
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), type === 'error' ? 8000 : 15000);
}

async function errorOf(response) {
    try {
        const json = await response.json();
        if (json?.error) return json.error;
    } catch { /* not JSON */ }
    return t('common.httpError', { status: response.status });
}

const masterToggle = document.getElementById('master-toggle');
const list = document.getElementById('clear-list');

function render() {
    list.innerHTML = ITEMS.map(item => `
        <div class="p-6 flex flex-col sm:flex-row sm:items-center gap-4" data-target="${item.target}">
            <div class="flex-1 min-w-0">
                <h3 class="text-base font-semibold text-gray-900">
                    ${item.title}
                    <span class="ml-2 text-sm font-normal text-gray-500" data-count>–</span>
                </h3>
                <p class="text-sm text-gray-600 mt-1">${item.description}</p>
            </div>
            <div class="flex items-center gap-4 shrink-0">
                <label class="relative inline-flex items-center gap-2 cursor-pointer text-sm text-gray-700">
                    <input type="checkbox" class="danger-toggle" data-arm aria-label="${t(`danger.${item.verb}.allow`, { title: item.title })}">
                    <span class="danger-switch"></span>
                    ${t('danger.allow')}
                </label>
                <button type="button" data-clear
                        class="bg-red-600 text-white px-4 py-2 rounded-md hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-red-600">
                    ${t(`danger.${item.verb}.button`, { title: item.title })}
                </button>
            </div>
        </div>
    `).join('');

    list.querySelectorAll('[data-arm]').forEach(input => input.addEventListener('change', updateState));
    list.querySelectorAll('[data-clear]').forEach(button => button.addEventListener('click',
        () => clear(ITEMS.find(item => item.target === button.closest('[data-target]').dataset.target))));
}

// A row's button works only while both the master switch and the row's own switch are on
function updateState() {
    const enabled = masterToggle.checked;
    list.classList.toggle('opacity-50', !enabled);
    list.querySelectorAll('[data-target]').forEach(row => {
        const arm = row.querySelector('[data-arm]');
        if (!enabled) arm.checked = false;
        arm.disabled = !enabled;
        row.querySelector('[data-clear]').disabled = !(enabled && arm.checked);
    });
}

async function loadCounts() {
    try {
        const response = await fetch(`${API}/danger-zone`, { cache: 'no-store' });
        if (!response.ok) throw new Error(await errorOf(response));
        const counts = await response.json();
        ITEMS.forEach(item => {
            list.querySelector(`[data-target="${item.target}"] [data-count]`).textContent = `(${item.count(counts)})`;
        });
    } catch (error) {
        showMessage(t('danger.error.counts', { error: error.message }));
    }
}

async function clear(item) {
    if (!confirm(t(`danger.${item.verb}.confirm`, { what: item.what, description: item.description }))) {
        return;
    }

    const row = list.querySelector(`[data-target="${item.target}"]`);
    const button = row.querySelector('[data-clear]');
    button.disabled = true;
    try {
        const response = await fetch(`${API}/danger-zone/clear/${item.target}`, { method: 'POST' });
        if (!response.ok) throw new Error(await errorOf(response));
        const result = await response.json();
        const cleared = Object.entries(result.cleared).filter(([, n]) => n > 0)
            .map(([what, n]) => `${tOr(`danger.cleared.${what}`, what)}: ${n}`).join(', ');
        showMessage(`${item.title}: ${cleared ? t('danger.cleared', { cleared }) : t('danger.nothingCleared')}`
            + (result.safety_copy ? ` ${t('backup.safetyCopy', { file: result.safety_copy })}` : ''), 'success');
    } catch (error) {
        showMessage(t(`danger.${item.verb}.error`, { what: item.what, error: error.message }));
    } finally {
        // Each clear needs the row's switch turned on again
        row.querySelector('[data-arm]').checked = false;
        updateState();
        loadCounts();
    }
}

render();
masterToggle.checked = false;
masterToggle.addEventListener('change', updateState);
// Coming back via the browser's back button must not find the page still unlocked
window.addEventListener('pageshow', () => {
    masterToggle.checked = false;
    updateState();
});
updateState();
loadCounts();
