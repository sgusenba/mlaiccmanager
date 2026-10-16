// Starts management module

import { getState, setState } from '../config.js';
import { addStart, deleteStart as apiDeleteStart, loadCompetitors } from '../api.js';
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
export function displayStartsManagement(competitor) {
    showElement('starts-management');
    populateActiveDisciplinesDropdown();
    displayStartsTable(competitor);
}

// Populate active disciplines dropdown
function populateActiveDisciplinesDropdown() {
    const dropdown = document.getElementById('start-discipline');
    const availableDisciplines = getState('availableDisciplines');
    const activeDisciplines = getState('activeDisciplines');
    
    // The meet's program (the disciplines its rankings cover); competitors start in individual disciplines only
    const activeDisciplinesList = availableDisciplines.filter(discipline =>
        activeDisciplines.includes(discipline.id) && discipline.level !== 'team'
    );
    
    dropdown.innerHTML = `<option value="">${t('starts.selectDiscipline')}</option>` +
        activeDisciplinesList.map(discipline => 
            `<option value="${discipline.id}">${discipline.name} (${tOr(`category.${discipline.category}`, discipline.category)})</option>`
        ).join('');
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
    
    if (allStarts.length === 0) {
        setElementContent('starts-table-body', '');
        showElement('no-starts');
    } else {
        hideElement('no-starts');
        tbody.innerHTML = allStarts.map(start => {
            const discipline = availableDisciplines.find(d => d.id === start.discipline_id);
            return `
                <tr>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">
                        ${discipline ? discipline.name : t('common.unknown')}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        ${start.start_number}
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
    showElement('add-start-form');
}

// Hide add start form
export function hideAddStartForm() {
    hideElement('add-start-form');
    setElementValue('start-discipline', '');
    setElementValue('start-caliber', '');
}

// Save start
export async function saveStart() {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor) {
        showMessage(t('starts.noCompetitorSelected'), 'error');
        return;
    }
    
    const disciplineId = parseInt(getElementValue('start-discipline'));
    
    if (!disciplineId) {
        showMessage(t('starts.selectDisciplinePrompt'), 'error');
        return;
    }
    
    try {
        const caliber = getElementValue('start-caliber').trim();
        await withButtonFeedback(document.getElementById('save-start-btn'),
            () => addStart(selectedCompetitor.id, disciplineId, caliber));
        hideAddStartForm();
        await refreshSelectedCompetitor();
    } catch (error) {
        console.error('Error adding start:', error);
        // Show the starts as stored on the server, including ones other users added
        if (!await refreshSelectedCompetitor()) return;
        showMessage(error.body?.error ? t('starts.error.addWith', { error: error.body.error }) : t('starts.error.add'), 'error');
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