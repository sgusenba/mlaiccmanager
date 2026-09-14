// Starts management module

import { getState, setState } from '../config.js';
import { addStart, deleteStart as apiDeleteStart } from '../api.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue } from '../utils.js';

// Display competitor information
export function displayCompetitorInfo(competitor) {
    const detailsDiv = document.getElementById('competitor-details');
    
    detailsDiv.innerHTML = `
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
                <span class="text-sm font-medium text-gray-500">Name:</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.name}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">ID:</span>
                <span class="text-sm text-gray-900 ml-2">#${competitor.id}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">Year of Birth:</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.year_of_birth || 'Not specified'}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">Gender:</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.gender || 'Not specified'}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">Club:</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.club || 'Not specified'}</span>
            </div>
            <div>
                <span class="text-sm font-medium text-gray-500">Email:</span>
                <span class="text-sm text-gray-900 ml-2">${competitor.email || 'Not provided'}</span>
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
    
    const activeDisciplinesList = availableDisciplines.filter(discipline => 
        activeDisciplines.includes(discipline.id)
    );
    
    dropdown.innerHTML = '<option value="">Select Discipline</option>' +
        activeDisciplinesList.map(discipline => 
            `<option value="${discipline.id}">${discipline.event} (${discipline.category})</option>`
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
                        ${discipline ? discipline.event : 'Unknown'}
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                        ${start.start_number}
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
                            ${start.status || 'registered'}
                        </span>
                    </td>
                    <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                        <button data-action="delete-start" data-discipline-id="${start.discipline_id}" data-generated-id="${start.generated_id}" 
                                class="text-red-600 hover:text-red-900">
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
            deleteStart(disciplineId, generatedId);
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
}

// Save start
export async function saveStart() {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor) {
        showMessage('No competitor selected', 'error');
        return;
    }
    
    const disciplineId = parseInt(getElementValue('start-discipline'));
    
    if (!disciplineId) {
        showMessage('Please select a discipline', 'error');
        return;
    }
    
    try {
        const newStart = await addStart(selectedCompetitor.id, disciplineId);
        
        // Initialize starts object if it doesn't exist
        if (!selectedCompetitor.starts) {
            selectedCompetitor.starts = {};
        }
        
        // Initialize starts for this discipline if it doesn't exist
        if (!selectedCompetitor.starts[disciplineId]) {
            selectedCompetitor.starts[disciplineId] = [];
        }
        
        // Add the new start from backend response
        selectedCompetitor.starts[disciplineId].push(newStart);
        
        // Sort starts by start number
        selectedCompetitor.starts[disciplineId].sort((a, b) => a.start_number - b.start_number);
        
        // Update local competitors array
        const competitors = getState('competitors');
        const competitorIndex = competitors.findIndex(c => c.id === selectedCompetitor.id);
        if (competitorIndex !== -1) {
            competitors[competitorIndex] = selectedCompetitor;
            setState('competitors', competitors);
        }
        
        // Update display
        displayStartsTable(selectedCompetitor);
        hideAddStartForm();
        
        showMessage('Start added successfully', 'success');
    } catch (error) {
        console.error('Error adding start:', error);
        showMessage('Error adding start', 'error');
    }
}

// Delete start
export async function deleteStart(disciplineId, generatedId) {
    const selectedCompetitor = getState('selectedCompetitor');
    if (!selectedCompetitor || !confirm('Are you sure you want to delete this start?')) {
        return;
    }
    
    try {
        await apiDeleteStart(selectedCompetitor.id, generatedId);
        
        // Remove start from local state
        if (selectedCompetitor.starts && selectedCompetitor.starts[disciplineId]) {
            selectedCompetitor.starts[disciplineId] = selectedCompetitor.starts[disciplineId].filter(
                start => start.generated_id !== generatedId
            );
            
            // Remove discipline entry if no starts left
            if (selectedCompetitor.starts[disciplineId].length === 0) {
                delete selectedCompetitor.starts[disciplineId];
            }
            
            // Update local competitors array
            const competitors = getState('competitors');
            const competitorIndex = competitors.findIndex(c => c.id === selectedCompetitor.id);
            if (competitorIndex !== -1) {
                competitors[competitorIndex] = selectedCompetitor;
                setState('competitors', competitors);
            }
            
            // Update display
            displayStartsTable(selectedCompetitor);
            
            showMessage('Start deleted successfully', 'success');
        }
    } catch (error) {
        console.error('Error deleting start:', error);
        showMessage('Error deleting start', 'error');
    }
}

// Search competitor
export function searchCompetitor() {
    const searchTerm = getElementValue('competitor-search').trim();
    if (!searchTerm) {
        showMessage('Please enter a competitor name or ID', 'error');
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
        showMessage('Competitor not found', 'error');
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