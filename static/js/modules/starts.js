// Starts management module

import { getState, setState } from '../config.js';
import { deleteStart as apiDeleteStart, loadCompetitors, loadRankings, registerForRanking, withdrawFromRanking } from '../api.js';
import { escapeHtml } from '../teamRanking.js';
import { t, tOr } from '../i18n.js';
import { genderLabel } from './competitors.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue } from '../utils.js';
import { withButtonFeedback } from '../buttonFeedback.js';

// Display competitor information
export function displayCompetitorInfo(competitor) {
    const detailsDiv = document.getElementById('competitor-details');
    
    detailsDiv.innerHTML = `
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.name')}</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.name}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.id')}</span>
                <span class="text-sm text-gray-900 ml-2">#${competitor.id}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.yearOfBirth')}</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.year_of_birth || t('common.notSpecified')}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.gender')}</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.gender ? genderLabel(competitor.gender) : t('common.notSpecified')}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.club')}</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.club || t('common.notSpecified')}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">${t('starts.info.email')}</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.email || t('common.notProvided')}</span>
            </div>
        </div>
    `;
    
    showElement('selected-competitor-info');
}

// Display starts management
export async function displayStartsManagement(competitor) {
    showElement('starts-management');
    // The table names each start's rankings, so they are loaded first
    await populateRankingsDropdown();
    displayStartsTable(getState('selectedCompetitor') || competitor);
}

// The ranking entries offered in the dropdown: option value "rankingId:entry" -> { rankingId, entry }
const rankingEntries = new Map();
// The meet's individual rankings by id, for the names shown with each start
const rankingsById = new Map();

const disciplineName = (id) =>
    getState('availableDisciplines').find(d => d.id === id)?.name ?? `#${id}`;

const rankingName = (id) => rankingsById.get(id)?.name ?? `#${id}`;

// Populate the dropdown with the meet's individual rankings, as the bulk entry
// offers them: one option per ranking entry. A ranking that ranks several
// entries together (e.g. Whitworth O/R) gets a group with one option each.
// A competitor is registered for each ranking on its own; an entry that sums
// disciplines (Remington_O = Colt + Donald Malson_O) uses a start in each,
// shared with the other rankings the competitor is registered for.
async function populateRankingsDropdown() {
    const dropdown = document.getElementById('start-ranking');
    let rankings = [];
    try {
        rankings = (await loadRankings())
            .filter(r => String(r.level || 'individual').toLowerCase() !== 'team')
            .filter(r => (r.entries || []).length);
    } catch (error) {
        console.error('Error loading rankings:', error);
        showMessage(t('starts.error.loadRankings'), 'error');
        return;
    }
    rankingEntries.clear();
    rankingsById.clear();
    rankings.forEach(r => rankingsById.set(r.id, r));

    const option = (ranking, i, text) => {
        const value = `${ranking.id}:${i}`;
        const ids = ranking.entries[i].discipline_ids;
        rankingEntries.set(value, { rankingId: ranking.id, entry: i });
        const parts = ids.length > 1 ? ` (${ids.map(disciplineName).join(' + ')})` : '';
        return `<option value="${value}">${escapeHtml(text)}${escapeHtml(parts)}</option>`;
    };

    dropdown.innerHTML = `<option value="">${t('starts.selectRanking')}</option>` +
        rankings.map(ranking => {
            const entries = ranking.entries;
            if (entries.length === 1) {
                return option(ranking, 0, ranking.name);
            }
            return `<optgroup label="${escapeHtml(ranking.name)}">` +
                entries.map((entry, i) => option(ranking, i,
                    entry.label || (entry.discipline_ids.length === 1 ? disciplineName(entry.discipline_ids[0]) : ranking.name))).join('') +
                `</optgroup>`;
        }).join('');

    hideElement('no-rankings-hint');
    if (!rankingEntries.size) showElement('no-rankings-hint');
}

// The rankings the competitor is registered for, each with a button to withdraw
function displayRegisteredRankings(allStarts) {
    const container = document.getElementById('registered-rankings');
    const ids = [...new Set(allStarts.flatMap(start => start.ranking_ids || []))]
        .sort((a, b) => rankingName(a).localeCompare(rankingName(b)));
    if (!ids.length) {
        container.innerHTML = '';
        hideElement('registered-rankings');
        return;
    }
    container.innerHTML = `<span class="text-sm font-medium text-gray-500 mr-1">${t('starts.registeredFor')}</span>` +
        ids.map(id => `
            <span class="inline-flex items-center gap-1 px-2 py-1 rounded-full bg-blue-50 text-blue-800 text-sm">
                ${escapeHtml(rankingName(id))}
                <button type="button" data-action="withdraw-ranking" data-ranking-id="${id}"
                        class="text-blue-500 hover:text-red-600 leading-none" title="${escapeHtml(t('starts.withdraw', { ranking: rankingName(id) }))}"
                        aria-label="${escapeHtml(t('starts.withdraw', { ranking: rankingName(id) }))}">×</button>
            </span>`).join('');
    container.querySelectorAll('button[data-action="withdraw-ranking"]').forEach(button => {
        button.addEventListener('click', () => withdraw(parseInt(button.dataset.rankingId), button));
    });
    showElement('registered-rankings');
}

// Display starts table
function displayStartsTable(competitor) {
    const tbody = document.getElementById('starts-table-body');
    const noStartsDiv = document.getElementById('no-starts');
    const availableDisciplines = getState('availableDisciplines');
    
    const allStarts = [];
    
    // Collect all starts from all disciplines
    if (competitor.starts) {
        Object.keys(competitor.starts).forEach(disciplineId => {
            competitor.starts[disciplineId].forEach(start => {
                allStarts.push({
                    ...start,
                    discipline_id: parseInt(disciplineId)
                });
            });
        });
    }
    
    displayRegisteredRankings(allStarts);

    if (allStarts.length === 0) {
        setElementContent('starts-table-body', '');
        showElement('no-starts');
    } else {
        hideElement('no-starts');
        tbody.innerHTML = allStarts.map(start => {
            const discipline = availableDisciplines.find(d => d.id === start.discipline_id);
            // Starts added by discipline alone (older data) count in every ranking of their discipline
            const rankings = start.ranking_ids
                ? start.ranking_ids.map(rankingName).map(escapeHtml).join('<br>')
                : `<span class="text-gray-400">${t('starts.anyRanking')}</span>`;
            return `
                <tr>
                    <td class="px-6 py-4 text-sm text-gray-900">
                        ${rankings}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        ${discipline ? escapeHtml(discipline.name) : t('common.unknown')}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        ${start.caliber ? escapeHtml(start.caliber) : '<span class="text-gray-400">–</span>'}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900 font-mono">
                        ${start.generated_id}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        <span class="px-2 py-1 text-xs rounded-full ${
                            start.status === 'registered' ? 'bg-green-100 text-green-800' :
                            start.status === 'completed' ? 'bg-blue-100 text-blue-800' :
                            'bg-gray-100 text-gray-800'
                        }">
                            ${tOr(`starts.statusValue.${start.status || 'registered'}`, start.status)}
                        </span>
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                        <button data-action="delete-start" data-discipline-id="${start.discipline_id}" data-generated-id="${start.generated_id}" 
                                class="text-red-600 hover:text-red-900" title="${t('common.delete')}">
                            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                            </svg>
                        </button>
                    </td>
                </tr>
            `;
        }).join('');
        
        // Add event listeners to delete buttons
        tbody.querySelectorAll('button[data-action="delete-start"]').forEach(button => {
            button.addEventListener('click', handleStartAction);
        });
    }
}

// Handle start actions
function handleStartAction(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    const disciplineId = parseInt(button.dataset.disciplineId);
    const generatedId = button.dataset.generatedId;
    
    switch (action) {
        case 'delete-start':
            deleteStart(disciplineId, generatedId, button);
            break;
    }
}

// Show add start form
export function showAddStartForm() {
    // Rankings may have changed since the competitor was opened
    populateRankingsDropdown();
    showElement('add-start-form');
}

// Hide add start form
export function hideAddStartForm() {
    hideElement('add-start-form');
    setElementValue('start-ranking', '');
    setElementValue('start-caliber', '');
}

// Save start
export async function saveStart() {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor) {
        showMessage(t('starts.noCompetitorSelected'), 'error');
        return;
    }
    
    const picked = rankingEntries.get(getElementValue('start-ranking'));
    
    if (!picked) {
        showMessage(t('starts.selectRankingPrompt'), 'error');
        return;
    }
    
    try {
        const caliber = getElementValue('start-caliber').trim();
        await withButtonFeedback(document.getElementById('save-start-btn'),
            () => registerForRanking(selectedCompetitor.id, picked.rankingId, picked.entry, caliber));
        hideAddStartForm();
        await refreshSelectedCompetitor();
    } catch (error) {
        console.error('Error registering for ranking:', error);
        // Show the starts as stored on the server, including ones other users added
        if (!await refreshSelectedCompetitor()) return;
        showMessage(error.body?.error ? t('starts.error.addWith', { error: error.body.error }) : t('starts.error.add'), 'error');
    }
}

// Withdraw the competitor from a ranking: starts used by no other ranking are deleted with their results
async function withdraw(rankingId, button) {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor || !confirm(t('starts.confirmWithdraw', { ranking: rankingName(rankingId) }))) {
        return;
    }
    try {
        await withButtonFeedback(button, () => withdrawFromRanking(selectedCompetitor.id, rankingId));
        await refreshSelectedCompetitor();
    } catch (error) {
        console.error('Error withdrawing from ranking:', error);
        if (!await refreshSelectedCompetitor()) return;
        showMessage(error.body?.error ? t('starts.error.withdrawWith', { error: error.body.error }) : t('starts.error.withdraw'), 'error');
    }
}

// Reload competitors from the server and re-render the selected competitor's starts,
// so starts added or deleted by other users show up too.
// Returns false if the competitor no longer exists (the user has already been told).
async function refreshSelectedCompetitor() {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor) return true;
    
    const competitors = await loadCompetitors();
    const fresh = competitors.find(c => c.id === selectedCompetitor.id);
    if (!fresh) {
        // An empty list may just mean loading failed; keep what is shown then
        if (competitors.length === 0) return true;
        clearCompetitorSearch();
        showMessage(t('competitors.conflict.deleted'), 'error');
        return false;
    }
    setState('selectedCompetitor', fresh);
    displayCompetitorInfo(fresh);
    displayStartsTable(fresh);
    return true;
}

// Delete start
export async function deleteStart(disciplineId, generatedId, button) {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor || !confirm(t('starts.confirmDelete'))) {
        return;
    }
    
    try {
        await withButtonFeedback(button, () => apiDeleteStart(selectedCompetitor.id, generatedId));
        await refreshSelectedCompetitor();
    } catch (error) {
        console.error('Error deleting start:', error);
        if (!await refreshSelectedCompetitor()) return;
        showMessage(error.body?.error ? t('starts.error.deleteWith', { error: error.body.error }) : t('starts.error.delete'), 'error');
    }
}

// Search competitor
export function searchCompetitor() {
    const searchTerm = getElementValue('competitor-search').trim();
    if (!searchTerm) {
        showMessage(t('starts.enterNameOrId'), 'error');
        return;
    }
    
    const competitors = getState('competitors');
    const foundCompetitor = competitors.find(competitor => 
        competitor.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
        competitor.id.toString() === searchTerm
    );
    
    if (foundCompetitor) {
        setState('selectedCompetitor', foundCompetitor);
        displayCompetitorInfo(foundCompetitor);
        displayStartsManagement(foundCompetitor);
    } else {
        showMessage(t('competitors.notFound'), 'error');
        clearCompetitorSearch();
    }
}

// Clear competitor search
export function clearCompetitorSearch() {
    setElementValue('competitor-search', '');
    hideElement('selected-competitor-info');
    hideElement('starts-management');
    setState('selectedCompetitor', null);
}

// Setup event listeners for starts section
export function setupStartsEventListeners() {
    // Search button
    const searchBtn = document.getElementById('search-competitor-btn');
    if (searchBtn) {
        searchBtn.addEventListener('click', searchCompetitor);
    }
    
    // Clear button
    const clearBtn = document.getElementById('clear-competitor-search-btn');
    if (clearBtn) {
        clearBtn.addEventListener('click', clearCompetitorSearch);
    }
    
    // Clear competitor info button
    const clearInfoBtn = document.getElementById('clear-competitor-info-btn');
    if (clearInfoBtn) {
        clearInfoBtn.addEventListener('click', clearCompetitorSearch);
    }
    
    // Add start button
    const addStartBtn = document.getElementById('add-start-btn');
    if (addStartBtn) {
        addStartBtn.addEventListener('click', showAddStartForm);
    }
    
    // Add start form
    const addStartForm = document.getElementById('add-start-form');
    if (addStartForm) {
        // Save button click handler
        const saveBtn = document.getElementById('save-start-btn');
        if (saveBtn) {
            saveBtn.addEventListener('click', (event) => {
                event.preventDefault();
                saveStart();
            });
        }
        
        // Cancel button
        const cancelBtn = document.getElementById('cancel-start-btn');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', hideAddStartForm);
        }
    }
}