// Results management module

import { getState, setState } from '../config.js';
import { saveResult as apiSaveResult, deleteResult as apiDeleteResult, loadResults } from '../api.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue, scrollToElement } from '../utils.js';

// Render results table
export function renderResults() {
    const tbody = document.getElementById('results-table-body');
    const noResults = document.getElementById('no-results');
    const results = getState('results');
    const activeDisciplines = getState('activeDisciplines');
    const availableDisciplines = getState('availableDisciplines');
    
    // If the results table elements don't exist, skip rendering
    if (!tbody) {
        console.log('Results table body not found, skipping renderResults');
        return;
    }
    
    if (results.length === 0) {
        setElementContent('results-table-body', '');
        if (noResults) {
            showElement('no-results');
        }
        return;
    }
    
    if (noResults) {
        hideElement('no-results');
    }
    
    tbody.innerHTML = results.map(result => {
        const discipline = activeDisciplines.length > 0 ? 
            availableDisciplines.find(d => d.id === result.discipline_id) :
            null;
        
        return `
            <tr class="hover:bg-gray-50">
                <td class="px-6 py-4 whitespace-nowrap">
                    <div class="text-sm font-mono text-gray-900">${result.start_id || 'N/A'}</div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap">
                    <div class="flex items-center">
                        <svg class="h-5 w-5 text-gray-400 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0z"></path>
                        </svg>
                        <div>
                            <div class="text-sm font-medium text-gray-900">${result.competitor_name}</div>
                        </div>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap">
                    <div class="flex items-center">
                        <svg class="h-5 w-5 text-gray-400 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path>
                        </svg>
                        <div class="text-sm text-gray-900">${result.event_name || discipline?.event || 'Unknown'}</div>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap">
                    <div class="text-sm font-medium text-gray-900">
                        ${result.override_value !== null ? `
                            <span class="line-through text-gray-400">${result.value}</span>
                            <span class="text-green-600 font-bold">${result.override_value}*</span>
                        ` : `
                            <span>${result.value}</span>
                        `}
                        <span class="text-gray-500 ml-1">points</span>
                    </div>
                </td>
                <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    ${result.notes || '-'}
                </td>
                <td class="px-6 py-4 whitespace-nowrap text-sm font-medium">
                    <div class="flex space-x-2">
                        <button data-action="edit-result" data-result-id="${result.id}" class="text-blue-600 hover:text-blue-900">
                            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
                            </svg>
                        </button>
                        <button data-action="delete-result" data-result-id="${result.id}" class="text-red-600 hover:text-red-900">
                            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                            </svg>
                        </button>
                    </div>
                </td>
            </tr>
        `;
    }).join('');
    
    // Add event listeners to action buttons
    tbody.querySelectorAll('button[data-action]').forEach(button => {
        button.addEventListener('click', handleResultAction);
    });
}

// Handle result actions
function handleResultAction(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    const resultId = parseInt(button.dataset.resultId);
    
    switch (action) {
        case 'edit-result':
            editResult(resultId);
            break;
        case 'delete-result':
            deleteResult(resultId);
            break;
    }
}

// Search start by ID
export function searchStartById() {
    const startId = getElementValue('result-start-search').trim();
    
    if (!startId) {
        showMessage('Please enter a start ID', 'error');
        return;
    }
    
    const foundStart = findStartById(startId);
    
    if (!foundStart) {
        showMessage('Start not found', 'error');
        return;
    }
    
    setState('selectedStart', foundStart);
    displaySelectedStart(foundStart);
}

// Find start by ID helper function
function findStartById(startId) {
    const competitors = getState('competitors');
    
    for (const competitor of competitors) {
        if (competitor.starts) {
            for (const disciplineId in competitor.starts) {
                const starts = competitor.starts[disciplineId];
                if (Array.isArray(starts)) {
                    const start = starts.find(s => s.generated_id === startId);
                    if (start) {
                        return {
                            ...start,
                            competitor: competitor,
                            discipline_id: parseInt(disciplineId)
                        };
                    }
                }
            }
        }
    }
    return null;
}

// Display selected start for result entry
function displaySelectedStart(start) {
    const infoDiv = document.getElementById('selected-start-info');
    const availableDisciplines = getState('availableDisciplines');
    const results = getState('results');
    
    if (!infoDiv) {
        console.error('selected-start-info element not found');
        return;
    }
    
    const discipline = availableDisciplines.find(d => d.id === start.discipline_id);
    
    // Check if this start already has a result
    const existingResult = results.find(result => result.start_id === start.generated_id);
    
    // Create start details HTML
    let startDetailsHtml = `
        <div class="bg-blue-50 rounded-lg p-4 mb-6">
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                    <h4 class="font-semibold text-gray-900 mb-2">Start Information</h4>
                    <div class="space-y-1">
                        <p><span class="text-sm text-gray-600">Start ID:</span> <span class="font-mono text-blue-600">${start.generated_id}</span></p>
                        <p><span class="text-sm text-gray-600">Status:</span> <span class="px-2 py-1 text-xs rounded-full ${start.status === 'registered' ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'}">${start.status || 'registered'}</span></p>
                    </div>
                </div>
                <div>
                    <h4 class="font-semibold text-gray-900 mb-2">Competitor Information</h4>
                    <div class="space-y-1">
                        <p><span class="text-sm text-gray-600">Name:</span> ${start.competitor.name}</p>
                        <p><span class="text-sm text-gray-600">ID:</span> ${start.competitor.id}</p>
                        <p><span class="text-sm text-gray-600">Club:</span> ${start.competitor.club || '-'}</p>
                    </div>
                </div>
            </div>
            <div class="mt-4">
                <h4 class="font-semibold text-gray-900 mb-2">Discipline Information</h4>
                <div class="space-y-1">
                    <p><span class="text-sm text-gray-600">Event:</span> ${discipline ? discipline.event : 'Unknown'}</p>
                    <p><span class="text-sm text-gray-600">Category:</span> ${discipline ? discipline.category : '-'}</p>
                    <p><span class="text-sm text-gray-600">Type:</span> ${discipline ? discipline.type : '-'}</p>
                </div>
            </div>
        </div>
    `;
    
    // Create result form HTML
    let resultFormHtml = `
        <div class="bg-white rounded-lg shadow-md p-6">
            <div class="flex justify-between items-center mb-4">
                <h3 class="text-lg font-semibold">Result Management</h3>
                <div class="flex space-x-2">
                    <button id="delete-result-btn" data-action="delete-result-for-start" class="bg-red-600 text-white px-3 py-1 rounded hover:bg-red-700 transition-colors ${existingResult ? '' : 'hidden'}">
                        Delete Result
                    </button>
                    <button data-action="clear-start-search" class="text-gray-500 hover:text-gray-700">
                        <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
                        </svg>
                    </button>
                </div>
            </div>
    `;
    
    // Add existing result info if found
    if (existingResult) {
        resultFormHtml += `
            <div class="mb-4 p-4 bg-yellow-50 border-l-4 border-yellow-200 rounded-md">
                <h4 class="font-semibold text-yellow-800">Existing Result Found</h4>
                <div class="grid grid-cols-2 gap-4 text-sm mt-2">
                    <div><strong>Value:</strong> ${existingResult.value}</div>
                    <div><strong>Override:</strong> ${existingResult.override_value || 'None'}</div>
                    <div><strong>Notes:</strong> ${existingResult.notes || 'None'}</div>
                </div>
            </div>
        `;
    }
    
    resultFormHtml += `
            <form data-action="save-result-form" class="space-y-4">
                <!-- 10 Entry Fields -->
                <div class="space-y-2">
                    <label class="block text-sm font-medium text-gray-700 mb-2">10 Entries (0-10 each)</label>
                    <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
    `;
    
    // Add 10 entry fields with existing values if available
    for (let i = 1; i <= 10; i++) {
        const value = existingResult && existingResult.entries && existingResult.entries[i-1] ? existingResult.entries[i-1] : '';
        resultFormHtml += `
                        <div><label class="text-xs text-gray-600">Entry ${i}</label><input type="number" data-entry="${i}" min="0" max="10" value="${value}" class="w-full px-2 py-1 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"></div>
        `;
    }
    
    resultFormHtml += `
                    </div>
                </div>
                
                <!-- Sum Display -->
                <div class="bg-gray-50 p-3 rounded-md">
                    <div class="flex items-center justify-between">
                        <span class="text-sm font-medium text-gray-700">Total Sum:</span>
                        <span id="result-sum" class="text-lg font-bold text-blue-600">${existingResult ? existingResult.value : '0'}</span>
                    </div>
                </div>
                
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-1">Override Value (for tie-breaking)</label>
                        <input type="number" id="result-override" step="any" value="${existingResult && existingResult.override_value ? existingResult.override_value : ''}" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500" placeholder="Use only for tie-breaking">
                    </div>
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-1">Notes</label>
                        <input type="text" id="result-notes" value="${existingResult && existingResult.notes ? existingResult.notes : ''}" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500">
                    </div>
                </div>
                
                <div class="flex justify-end space-x-3">
                    <button type="button" data-action="clear-start-search" class="px-4 py-2 border border-gray-300 rounded-md hover:bg-gray-50 transition-colors">Cancel</button>
                    <button type="submit" class="bg-blue-600 text-white px-4 py-2 rounded-md hover:bg-blue-700 transition-colors flex items-center">
                        <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path>
                        </svg>
                        Save Result
                    </button>
                </div>
            </form>
        </div>
    `;
    
    // Combine all HTML and set to infoDiv
    infoDiv.innerHTML = startDetailsHtml + resultFormHtml;
    
    // Show the result section
    showElement('selected-start-info');
    scrollToElement('selected-start-info');
    
    // Store result ID for editing
    if (existingResult) {
        setState('currentEditingResultId', existingResult.id);
    } else {
        setState('currentEditingResultId', null);
    }
    
    // Add event listeners to entry fields for sum calculation
    infoDiv.querySelectorAll('input[data-entry]').forEach(field => {
        field.addEventListener('input', updateResultSum);
    });
    
    // Add event listeners to form actions
    infoDiv.querySelectorAll('button[data-action]').forEach(button => {
        button.addEventListener('click', handleResultFormAction);
    });
    
    // Add form submit listener
    const form = infoDiv.querySelector('form[data-action="save-result-form"]');
    if (form) {
        form.addEventListener('submit', saveResult);
    }
    
    // Update sum display
    updateResultSum();
}

// Handle result form actions
function handleResultFormAction(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    
    switch (action) {
        case 'delete-result-for-start':
            deleteResultForStart();
            break;
        case 'clear-start-search':
            clearStartSearch();
            break;
    }
}

// Update result sum calculation
function updateResultSum() {
    let sum = 0;
    for (let i = 1; i <= 10; i++) {
        const entryField = document.querySelector(`input[data-entry="${i}"]`);
        if (entryField) {
            const value = parseFloat(entryField.value) || 0;
            sum += value;
        }
    }
    
    const sumElement = document.getElementById('result-sum');
    if (sumElement) {
        sumElement.textContent = sum;
    }
}

// Save result
async function saveResult(event) {
    event.preventDefault();
    
    const selectedStart = getState('selectedStart');
    if (!selectedStart) {
        showMessage('No start selected', 'error');
        return;
    }
    
    // Calculate sum of all 10 entries
    let totalSum = 0;
    const entries = [];
    for (let i = 1; i <= 10; i++) {
        const entryField = document.querySelector(`input[data-entry="${i}"]`);
        const value = entryField ? parseFloat(entryField.value) || 0 : 0;
        entries.push(value);
        totalSum += value;
    }
    
    const overrideValue = parseFloat(getElementValue('result-override')) || null;
    const notes = getElementValue('result-notes');
    
    // Smart logic: if override value is provided, use it; otherwise use sum
    const finalValue = overrideValue !== null && overrideValue !== '' ? overrideValue : totalSum;
    
    const currentEditingResultId = getState('currentEditingResultId');
    const data = {
        competitor_id: selectedStart.competitor.id,
        discipline_id: selectedStart.discipline_id,
        start_id: selectedStart.generated_id,
        value: finalValue,
        entries: entries,
        override_value: overrideValue,
        notes: notes
    };
    
    // Add ID if editing existing result
    if (currentEditingResultId) {
        data.id = currentEditingResultId;
    }
    
    try {
        await apiSaveResult(data);
        clearStartSearch();
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage(currentEditingResultId ? 'Result updated successfully!' : 'Result saved successfully!', 'success');
    } catch (error) {
        showMessage('Error saving result', 'error');
    }
}

// Delete result for current start
async function deleteResultForStart() {
    const selectedStart = getState('selectedStart');
    const currentEditingResultId = getState('currentEditingResultId');
    
    if (!selectedStart || !currentEditingResultId) {
        showMessage('No result to delete', 'error');
        return;
    }
    
    if (!confirm('Are you sure you want to delete this result? This action cannot be undone.')) {
        return;
    }
    
    try {
        await apiDeleteResult(currentEditingResultId);
        clearStartSearch();
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage('Result deleted successfully!', 'success');
    } catch (error) {
        showMessage('Error deleting result', 'error');
    }
}

// Delete result from table
export async function deleteResult(id) {
    if (!confirm('Are you sure you want to delete this result?')) return;
    
    try {
        await apiDeleteResult(id);
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage('Result deleted successfully!', 'success');
    } catch (error) {
        showMessage('Error deleting result', 'error');
    }
}

// Edit result (placeholder for future implementation)
function editResult(id) {
    // For now, we can redirect to start search with the start ID
    const results = getState('results');
    const result = results.find(r => r.id === id);
    if (result && result.start_id) {
        setElementValue('result-start-search', result.start_id);
        searchStartById();
    }
}

// Clear start search
export function clearStartSearch() {
    setElementValue('result-start-search', '');
    hideElement('selected-start-info');
    setElementContent('selected-start-info', '');
    setState('selectedStart', null);
    setState('currentEditingResultId', null);
}

// Setup event listeners for results section
export function setupResultsEventListeners() {
    // Search start button
    const searchBtn = document.getElementById('search-start-btn');
    if (searchBtn) {
        searchBtn.addEventListener('click', searchStartById);
    }
}