const API = '/api';

// What can be cleared, in the order the server lists it (DangerZoneService.TARGETS).
// `what` names the data in the confirm dialog, `action` the button (default
// Clear); `count` turns the server's
// counts into the line shown next to the name.
const ITEMS = [
    {
        target: 'results',
        title: 'Results',
        what: 'all results',
        description: 'Deletes every entered result, tie-break values included. Competitors, starts, lanes and teams stay.',
        count: c => plural(c.results, 'result')
    },
    {
        target: 'lanes',
        title: 'Lane Assignments',
        what: 'all lane assignments',
        description: 'Clears every lane of every relay, locked days included. Meet days and relays stay.',
        count: c => plural(c.lanes, 'lane assignment')
    },
    {
        target: 'starts',
        title: 'Starts',
        what: 'all starts',
        description: 'Deletes every start of every competitor, and with them all results, lane assignments and team members. Competitors and teams (then empty) stay.',
        count: c => plural(c.starts, 'start')
    },
    {
        target: 'competitors',
        title: 'Competitors',
        what: 'all competitors',
        description: 'Deletes every competitor with their starts, and with them all results, lane assignments and team members. Teams (then empty) stay.',
        count: c => plural(c.competitors, 'competitor')
    },
    {
        target: 'teams',
        title: 'Teams',
        what: 'all teams',
        description: 'Deletes every team. Starts and results stay.',
        count: c => plural(c.teams, 'team')
    },
    {
        target: 'days',
        title: 'Meet Days & Relays',
        what: 'all meet days and relays',
        description: 'Deletes every meet day with its relays and lane assignments, locked days included. Ranges, relay duration and break stay.',
        count: c => `${plural(c.days, 'day')}, ${plural(c.relays, 'relay')}`
    },
    {
        target: 'meet',
        title: 'Meet Details',
        what: 'the meet details',
        description: "Clears the meet's name, venue, host and dates.",
        count: c => (c.meet ? 'set' : 'empty')
    },
    {
        target: 'disciplines',
        title: 'Discipline Settings',
        action: 'Reset',
        what: 'the discipline settings',
        description: 'Resets the disciplines to the shipped catalog, like a fresh install: every catalog discipline active, edits (e.g. the range) undone, removed ones back. Added disciplines are deleted, with their starts, results, lane assignments and teams.',
        count: c => `${c.active_disciplines} active, ${c.added_disciplines} added`
    }
];

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

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
    return `HTTP error! status: ${response.status}`;
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
                    <input type="checkbox" class="danger-toggle" data-arm aria-label="Allow ${(item.action || 'Clear').toLowerCase()} ${item.title}">
                    <span class="danger-switch"></span>
                    Allow
                </label>
                <button type="button" data-clear
                        class="bg-red-600 text-white px-4 py-2 rounded-md hover:bg-red-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-red-600">
                    ${item.action || 'Clear'} ${item.title}
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
        showMessage(`Could not load the data counts: ${error.message}`);
    }
}

async function clear(item) {
    if (!confirm(`Really ${(item.action || 'Clear').toLowerCase()} ${item.what}?\n\n${item.description}\n\n`
        + 'The current data is saved on the server first, so this can be undone on Backup & Restore.')) {
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
            .map(([what, n]) => `${what}: ${n}`).join(', ');
        showMessage(`${item.title}: ${cleared ? `cleared ${cleared}.` : 'there was nothing to clear.'}`
            + (result.safety_copy ? ` The previous data was saved to ${result.safety_copy}.` : ''), 'success');
    } catch (error) {
        showMessage(`Could not ${(item.action || 'Clear').toLowerCase()} ${item.what}: ${error.message}`);
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
