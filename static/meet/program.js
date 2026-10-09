// Program card of the Meet page: which disciplines of each event this meet
// shoots (original and/or reproduction), whether an event's original and
// reproduction are ranked combined, and which team rankings there are. Saved
// as the active disciplines and the combined events of the competition
// (/api/meet/program).

import { escapeHtml } from '../js/meet.js';

const CATEGORY_LABELS = { rifle: 'Rifle', pistol: 'Pistol' };
const TYPE_TAGS = { original: 'O', reproduction: 'R' };

let program = null;
let api = null;
let showMessage = null;

const plural = (n, word) => `${n} ${word}${n === 1 ? '' : 's'}`;

// The event's original and reproduction discipline (one each); any other discipline of the
// event, e.g. one added with type "combined", is listed under the event name with its own checkbox
const typed = (event, type) => event.disciplines.find(d => d.type === type);
const others = (event) => event.disciplines.filter(d => d !== typed(event, 'original') && d !== typed(event, 'reproduction'));

function disciplineCheckbox(event, d, label = '') {
    return `
        <input type="checkbox" class="program-discipline h-4 w-4" value="${d.id}" data-key="${escapeHtml(event.key)}"
            data-type="${escapeHtml(d.type || '')}" ${d.active ? 'checked' : ''} aria-label="${escapeHtml(d.name)}">${label}
        <span class="text-xs ${d.starts ? 'text-gray-500' : 'text-gray-300'}">${plural(d.starts, 'start')}</span>`;
}

/** The checkbox of the event's discipline of this type, or "–" if the event has none. */
function typeCell(event, type) {
    const d = typed(event, type);
    if (!d) return '<td class="px-3 py-2 text-center text-gray-300">–</td>';
    return `
        <td class="px-3 py-2 text-center">
            <label class="inline-flex flex-col items-center cursor-pointer">${disciplineCheckbox(event, d)}</label>
        </td>`;
}

function combinedCell(event) {
    if (!event.combinable) return '<td class="px-3 py-2 text-center text-gray-300">–</td>';
    const bothShot = typed(event, 'original').active && typed(event, 'reproduction').active;
    return `
        <td class="px-3 py-2 text-center">
            <input type="checkbox" class="program-combined h-4 w-4" data-key="${escapeHtml(event.key)}"
                ${event.combined && bothShot ? 'checked' : ''} ${bothShot ? '' : 'disabled'}
                title="Ranks original and reproduction together; needs both to be shot" aria-label="${escapeHtml(event.name)} combined">
        </td>`;
}

function eventRow(event) {
    const note = event.aggregate ? '<div class="text-xs text-gray-500">Results of other disciplines added up</div>' : '';
    return `
        <tr class="hover:bg-gray-50">
            <td class="px-3 py-2 text-sm"><span class="font-medium">${escapeHtml(event.name)}</span>${note}
                ${others(event).map(d => `
                <label class="flex items-center gap-2 mt-1 cursor-pointer">
                    ${disciplineCheckbox(event, d, ` <span>${escapeHtml(d.name)}${d.type ? ` <span class="text-gray-400">(${escapeHtml(d.type)})</span>` : ''}</span>`)}
                </label>`).join('')}
            </td>
            ${typeCell(event, 'original')}
            ${typeCell(event, 'reproduction')}
            ${combinedCell(event)}
        </tr>`;
}

function render() {
    const th = (label, align = 'center', width = 'w-24') =>
        `<th class="${width} px-3 py-2 text-${align} text-xs font-medium text-gray-500 uppercase tracking-wider">${label}</th>`;
    const categories = [...new Set(program.events.map(e => e.category))];
    document.getElementById('program-events').innerHTML = categories.map(category => `
        <h4 class="text-sm font-semibold text-gray-700 mt-4 mb-2">${escapeHtml(CATEGORY_LABELS[category] || category)}</h4>
        <div class="overflow-x-auto">
            <table class="min-w-full table-fixed divide-y divide-gray-200">
                <thead class="bg-gray-50">
                    <tr>${th('Event', 'left', 'w-auto')}${th('Original')}${th('Reproduction', 'center', 'w-28')}${th('Combined')}</tr>
                </thead>
                <tbody class="divide-y divide-gray-200">
                    ${program.events.filter(e => e.category === category).map(eventRow).join('')}
                </tbody>
            </table>
        </div>`).join('');

    document.getElementById('program-teams').innerHTML = program.teams.map(team => `
        <label class="flex items-start gap-2 px-3 py-2 border border-gray-200 rounded-md text-sm cursor-pointer hover:bg-gray-50">
            <input type="checkbox" class="program-team h-4 w-4 mt-0.5" value="${team.id}" ${team.active ? 'checked' : ''}>
            <span class="min-w-0">
                <span class="font-medium">${escapeHtml(team.name)}</span>
                <span class="text-xs text-gray-400">${escapeHtml(CATEGORY_LABELS[team.category] || team.category)}</span>
                <span class="block text-xs text-gray-500">${team.composition.length ? escapeHtml(team.composition.join(' + ')) : '<span class="text-red-700">no results that count set</span>'}${team.teams
                    ? ` · ${plural(team.teams, 'team')}` : ''}</span>
            </span>
        </label>`).join('') || '<p class="text-sm text-gray-500">No team disciplines.</p>';
}

/** Combined is only possible while both original and reproduction of the event are ticked. */
function updateCombined(key) {
    const combined = [...document.querySelectorAll('.program-combined')].find(input => input.dataset.key === key);
    if (!combined) return;
    const bothShot = ['original', 'reproduction'].every(type => [...document.querySelectorAll('.program-discipline')]
        .some(input => input.dataset.key === key && input.dataset.type === type && input.checked));
    combined.disabled = !bothShot;
    if (!bothShot) combined.checked = false;
}

/** What the page would save: per event the ticked disciplines and whether combined, and the ticked teams. */
function chosen() {
    const events = {};
    for (const event of program.events) {
        events[event.key] = { active: [], combined: false };
    }
    document.querySelectorAll('.program-discipline:checked').forEach(input => {
        events[input.dataset.key].active.push(Number(input.value));
    });
    document.querySelectorAll('.program-combined:checked').forEach(input => {
        events[input.dataset.key].combined = true;
    });
    const teams = [...document.querySelectorAll('.program-team:checked')].map(input => Number(input.value));
    return { events, teams };
}

/** Disciplines and teams the change stops while they have starts or teams, for the confirmation. */
function losses({ events, teams }) {
    const lost = [];
    for (const event of program.events) {
        for (const d of event.disciplines) {
            if (d.active && d.starts > 0 && !events[event.key].active.includes(d.id)) {
                lost.push(`${d.name} (${plural(d.starts, 'start')})`);
            }
        }
    }
    for (const team of program.teams) {
        if (team.active && team.teams > 0 && !teams.includes(team.id)) {
            lost.push(`${team.name} (${plural(team.teams, 'team')})`);
        }
    }
    return lost;
}

async function load() {
    program = await api('/meet/program');
    render();
}

async function save() {
    const button = document.getElementById('program-save-btn');
    const request = chosen();
    const lost = losses(request);
    if (lost.length && !confirm(`These are no longer shot, but have starts or teams:\n\n${lost.join('\n')}\n\n`
            + 'Their starts and teams are kept, but they are no longer ranked. Save anyway?')) {
        return;
    }
    button.disabled = true;
    try {
        program = await api('/meet/program', 'PUT', { ...request, base: program.base });
        render();
        showMessage('Program saved.', 'success');
    } catch (error) {
        if (error.status === 409 && !error.message.startsWith('Cannot combine')) {
            await load();
            showMessage(`${error.message}. The latest program is shown now; please make your change again.`);
        } else {
            showMessage(`Could not save the program: ${error.message}`);
        }
    } finally {
        button.disabled = false;
    }
}

export async function initProgram(options) {
    ({ api, showMessage } = options);
    document.getElementById('program-save-btn').addEventListener('click', save);
    document.getElementById('program-events').addEventListener('change', event => {
        if (event.target.matches('.program-discipline')) updateCombined(event.target.dataset.key);
    });
    await load();
}
