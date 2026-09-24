// Results management module

import { getState, setState } from '../config.js';
import { saveResult as apiSaveResult, deleteResult as apiDeleteResult, loadResults, loadCompetitors } from '../api.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue, scrollToElement } from '../utils.js';

// A result's score is the sum of its shots; older results stored the override as value
function resultScore(result) {
    if (Array.isArray(result.entries) && result.entries.length > 0) {
        return result.entries.reduce((sum, entry) => sum + (Number(entry) || 0), 0);
    }
    return result.value;
}

const SHOT_COUNT = 10;
const MAX_RING = 10;

// How many shots hit each ring (0 = miss); shots that are no whole ring 0-10 are left out
function ringCountsFromEntries(entries) {
    const counts = {};
    if (Array.isArray(entries)) {
        entries.forEach(entry => {
            const ring = Number(entry);
            if (Number.isInteger(ring) && ring >= 0 && ring <= MAX_RING) {
                counts[ring] = (counts[ring] || 0) + 1;
            }
        });
    }
    return counts;
}

// The shots are stored as a list, best ring first; the ranking only uses their sum and ring counts
function entriesFromRingCounts(counts) {
    const entries = [];
    for (let ring = MAX_RING; ring >= 0; ring--) {
        for (let i = 0; i < (counts[ring] || 0); i++) {
            entries.push(ring);
        }
    }
    return entries;
}

function hasTieBreak(result) {
    return result.override_value !== null && result.override_value !== undefined;
}

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
                        <span>${resultScore(result)}</span>
                        <span class="text-gray-500 ml-1">points</span>
                        ${hasTieBreak(result) ? `
                            <div class="text-xs text-gray-500" title="Tie-break: distance of the furthest shot, lower wins">Tie-break: ${result.override_value}</div>
                        ` : ''}
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
                    <div><strong>Value:</strong> ${resultScore(existingResult)}</div>
                    <div><strong>Tie-break:</strong> ${hasTieBreak(existingResult) ? existingResult.override_value : 'None'}</div>
                    <div><strong>Notes:</strong> ${existingResult.notes || 'None'}</div>
                </div>
            </div>
        `;
    }
    
    resultFormHtml += `
            <form data-action="save-result-form" class="space-y-4">
                <!-- Ring count fields: how many of the ${SHOT_COUNT} shots hit each ring -->
                <div class="space-y-2">
                    <label class="block text-sm font-medium text-gray-700 mb-2">Number of shots per ring (${SHOT_COUNT} shots in total)</label>
                    <div class="grid grid-cols-4 md:grid-cols-11 gap-3">
    `;

    // Add one field per ring, prefilled from the existing result's shots
    const ringCounts = existingResult ? ringCountsFromEntries(existingResult.entries) : {};
    for (let ring = MAX_RING; ring >= 0; ring--) {
        const count = ringCounts[ring] || '';
        resultFormHtml += `
                        <div><label class="text-xs text-gray-600">${ring === 0 ? '0 (miss)' : `${ring}s`}</label><input type="number" data-ring="${ring}" min="0" max="${SHOT_COUNT}" step="1" value="${count}" class="w-full px-2 py-1 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"></div>
        `;
    }

    resultFormHtml += `
                    </div>
                </div>

                <!-- Shot count and sum display -->
                <div class="bg-gray-50 p-3 rounded-md space-y-1">
                    <div class="flex items-center justify-between">
                        <span class="text-sm font-medium text-gray-700">Shots:</span>
                        <span id="result-shot-count" class="text-lg font-bold">0 / ${SHOT_COUNT}</span>
                    </div>
                    <div class="flex items-center justify-between">
                        <span class="text-sm font-medium text-gray-700">Total Sum:</span>
                        <span id="result-sum" class="text-lg font-bold text-blue-600">${existingResult ? resultScore(existingResult) : '0'}</span>
                    </div>
                </div>
                
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-1">Tie-break (lower wins)</label>
                        <input type="number" id="result-override" step="any" min="0" value="${existingResult && hasTieBreak(existingResult) ? existingResult.override_value : ''}" class="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500" placeholder="Distance of the furthest shot, only for ties">
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
        setState('currentEditingResultVersion', existingResult.version ?? null);
    } else {
        setState('currentEditingResultId', null);
        setState('currentEditingResultVersion', null);
    }
    
    // Add event listeners to ring count fields for sum calculation
    infoDiv.querySelectorAll('input[data-ring]').forEach(field => {
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

// Read the ring count fields; invalid is set when a field holds no whole, non-negative number
function readRingCounts() {
    const counts = {};
    let shots = 0;
    let sum = 0;
    let invalid = false;
    for (let ring = MAX_RING; ring >= 0; ring--) {
        const field = document.querySelector(`input[data-ring="${ring}"]`);
        const text = field ? String(field.value).trim() : '';
        const count = text === '' ? 0 : Number(text);
        if (!Number.isInteger(count) || count < 0) {
            invalid = true;
            continue;
        }
        counts[ring] = count;
        shots += count;
        sum += ring * count;
    }
    return { counts, shots, sum, invalid };
}

// Update shot count and sum display
function updateResultSum() {
    const { shots, sum, invalid } = readRingCounts();

    const shotCountElement = document.getElementById('result-shot-count');
    if (shotCountElement) {
        const complete = !invalid && shots === SHOT_COUNT;
        shotCountElement.textContent = `${shots} / ${SHOT_COUNT}`;
        shotCountElement.classList.toggle('text-green-600', complete);
        shotCountElement.classList.toggle('text-red-600', !complete);
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
    
    // The ring counts must add up to exactly the number of shots
    const { counts, shots, sum: totalSum, invalid } = readRingCounts();
    if (invalid) {
        showMessage('Shot counts must be whole numbers of 0 or more', 'error');
        return;
    }
    if (shots !== SHOT_COUNT) {
        showMessage(`The shot counts add up to ${shots}, but there must be exactly ${SHOT_COUNT} shots`, 'error');
        return;
    }
    const entries = entriesFromRingCounts(counts);

    // Tie-break only decides between equal scores (lower wins); it never replaces the sum
    const overrideText = String(getElementValue('result-override') ?? '').trim();
    const overrideValue = overrideText === '' || isNaN(parseFloat(overrideText)) ? null : parseFloat(overrideText);
    const notes = getElementValue('result-notes');
    
    const currentEditingResultId = getState('currentEditingResultId');
    const data = {
        competitor_id: selectedStart.competitor.id,
        discipline_id: selectedStart.discipline_id,
        start_id: selectedStart.generated_id,
        value: totalSum,
        entries: entries,
        override_value: overrideValue,
        notes: notes
    };
    
    // Add ID and the version the user saw if editing existing result
    if (currentEditingResultId) {
        data.id = currentEditingResultId;
        data.version = getState('currentEditingResultVersion');
    }
    
    try {
        await apiSaveResult(data);
        clearStartSearch();
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage(currentEditingResultId ? 'Result updated successfully!' : 'Result saved successfully!', 'success');
    } catch (error) {
        if (error.isConflict || error.isNotFound) {
            await reloadAfterResultConflict(selectedStart.generated_id);
            showMessage(error.isNotFound
                ? 'This result was deleted by someone else. Please enter it again if needed.'
                : currentEditingResultId
                    ? 'Someone else changed this result in the meantime. The latest values are now loaded - please make your change again.'
                    : 'Someone else already entered a result for this start. Their result is now loaded - please check it.', 'error');
            return;
        }
        showMessage('Error saving result', 'error');
    }
}

// Reload data after another user changed a result and show the start again with the latest values
async function reloadAfterResultConflict(startId) {
    await Promise.all([loadResults(), loadCompetitors()]);
    renderResults();
    
    const start = startId ? findStartById(startId) : null;
    if (start) {
        setState('selectedStart', start);
        displaySelectedStart(start);
    } else {
        clearStartSearch();
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
        await apiDeleteResult(currentEditingResultId, getState('currentEditingResultVersion'));
        clearStartSearch();
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage('Result deleted successfully!', 'success');
    } catch (error) {
        if (error.isConflict || error.isNotFound) {
            await reloadAfterResultConflict(selectedStart.generated_id);
            showMessage(resultDeleteConflictMessage(error), 'error');
            return;
        }
        showMessage('Error deleting result', 'error');
    }
}

function resultDeleteConflictMessage(error) {
    return error.isConflict
        ? 'Someone else changed this result in the meantime. The latest values are now loaded - please check them and delete again if needed.'
        : 'This result was already deleted by someone else.';
}

// Delete result from table
export async function deleteResult(id) {
    if (!confirm('Are you sure you want to delete this result?')) return;
    
    const result = getState('results').find(r => r.id === id);
    
    try {
        await apiDeleteResult(id, result?.version);
        
        // Reload results to update the table
        await loadResults();
        renderResults();
        
        showMessage('Result deleted successfully!', 'success');
    } catch (error) {
        if (error.isConflict || error.isNotFound) {
            await loadResults();
            renderResults();
            showMessage(resultDeleteConflictMessage(error), 'error');
            return;
        }
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
    setState('currentEditingResultVersion', null);
}

// Setup event listeners for results section
export function setupResultsEventListeners() {
    // Search start button
    const searchBtn = document.getElementById('search-start-btn');
    if (searchBtn) {
        searchBtn.addEventListener('click', searchStartById);
    }
}