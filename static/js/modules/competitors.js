// Competitor management module

import { getState, setState, updateState } from '../config.js';
import { loadCompetitors, saveCompetitor as apiSaveCompetitor, deleteCompetitor as apiDeleteCompetitor } from '../api.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue, clearForm } from '../utils.js';

// Render competitors table
export function renderCompetitors() {
    const tbody = document.getElementById('competitors-table-body');
    const noCompetitors = document.getElementById('no-competitors');
    const competitors = getState('competitors');
    
    if (competitors.length === 0) {
        setElementContent('competitors-table-body', '');
        showElement('no-competitors');
        return;
    }
    
    hideElement('no-competitors');
    tbody.innerHTML = competitors.map(competitor => `
        <tr class="hover:bg-gray-50">
            <td class="px-4 py-4 whitespace-nowrap">
                <div class="flex items-center">
                    <svg class="h-5 w-5 text-gray-400 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path>
                    </svg>
                    <div class="text-sm font-medium text-gray-900">${competitor.name}</div>
                </div>
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                #${competitor.id}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.gender || 'Not specified'}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.year_of_birth || 'Not specified'}
            </td>
            <td class="px-4 py-4 text-sm text-gray-500">
                ${competitor.club || 'Not specified'}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.email || 'Not provided'}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.phone || 'Not provided'}
            </td>
            <td class="px-4 py-4 text-sm text-gray-500">
                ${competitor.address || 'Not provided'}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm font-medium">
                <div class="flex space-x-2">
                    <button data-action="manage-starts" data-competitor-id="${competitor.id}" class="text-green-600 hover:text-green-900" title="Manage Starts">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path>
                        </svg>
                    </button>
                    <button data-action="edit-competitor" data-competitor-id="${competitor.id}" class="text-blue-600 hover:text-blue-900" title="Edit">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
                        </svg>
                    </button>
                    <button data-action="delete-competitor" data-competitor-id="${competitor.id}" class="text-red-600 hover:text-red-900" title="Delete">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                        </svg>
                    </button>
                </div>
            </td>
        </tr>
    `).join('');
    
    // Add event listeners to the new buttons
    tbody.querySelectorAll('button[data-action]').forEach(button => {
        button.addEventListener('click', handleCompetitorAction);
    });
}

// Handle competitor table actions
function handleCompetitorAction(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    const competitorId = parseInt(button.dataset.competitorId);
    
    switch (action) {
        case 'manage-starts':
            manageCompetitorStarts(competitorId);
            break;
        case 'edit-competitor':
            editCompetitor(competitorId);
            break;
        case 'delete-competitor':
            deleteCompetitor(competitorId);
            break;
    }
}

// Show competitor form
export async function showCompetitorForm() {
    showElement('competitor-form');
    
    // Load disciplines for the form
    const disciplinesModule = await import('./disciplines.js');
    await disciplinesModule.loadAvailableDisciplines();
    await disciplinesModule.loadActiveDisciplines();
}

// Hide competitor form
export function hideCompetitorForm() {
    hideElement('competitor-form');
    clearForm('competitor-data-form');
    setState('currentEditingId', null);
    setState('currentEditingVersion', null);

    // Reset form title and submit button back to "Add" state
    document.querySelector('#competitor-form h3').textContent = 'Add New Competitor';
    const submitButton = document.querySelector('#competitor-form button[type="submit"]');
    submitButton.innerHTML = `
        <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
        </svg>
        Save
    `;
}

// Save competitor
export async function saveCompetitor(event) {
    event.preventDefault();
    
    const data = {
        name: getElementValue('competitor-name'),
        gender: getElementValue('competitor-gender'),
        year_of_birth: getElementValue('competitor-year-of-birth'),
        club: getElementValue('competitor-club'),
        email: getElementValue('competitor-email'),
        phone: getElementValue('competitor-phone'),
        address: getElementValue('competitor-address')
        // starts are managed in the Starts section and never sent from this form
    };

    const currentEditingId = getState('currentEditingId');
    if (currentEditingId) {
        data.id = currentEditingId;
        data.version = getState('currentEditingVersion');
    }

    try {
        await apiSaveCompetitor(data);
        hideCompetitorForm();
        await loadCompetitors();
        renderCompetitors();
        showMessage(currentEditingId ? 'Competitor updated successfully!' : 'Competitor created successfully!', 'success');
    } catch (error) {
        if (currentEditingId && (error.isConflict || error.isNotFound)) {
            await handleCompetitorSaveConflict(error);
            return;
        }
        showMessage('Error saving competitor', 'error');
    }
}

// Someone else changed or deleted the competitor while this form was open
async function handleCompetitorSaveConflict(error) {
    await loadCompetitors();
    renderCompetitors();

    const current = error.body?.current;
    if (error.isConflict && current) {
        fillCompetitorForm(current);
        showMessage('Someone else changed this competitor in the meantime. The latest data is now loaded - please make your change again.', 'error');
    } else {
        hideCompetitorForm();
        showMessage('This competitor was deleted by someone else.', 'error');
    }
}

// Populate form fields and remember which version the user is editing
function fillCompetitorForm(competitor) {
    setState('currentEditingId', competitor.id);
    setState('currentEditingVersion', competitor.version ?? null);

    setElementValue('competitor-name', competitor.name);
    setElementValue('competitor-gender', competitor.gender || '');
    setElementValue('competitor-year-of-birth', competitor.year_of_birth || '');
    setElementValue('competitor-club', competitor.club || '');
    setElementValue('competitor-email', competitor.email || '');
    setElementValue('competitor-phone', competitor.phone || '');
    setElementValue('competitor-address', competitor.address || '');
}

// Edit competitor
export function editCompetitor(id) {
    const competitors = getState('competitors');
    const competitor = competitors.find(c => c.id === id);
    if (!competitor) return;

    fillCompetitorForm(competitor);

    showCompetitorForm();
    
    // Update form title
    document.querySelector('#competitor-form h3').textContent = 'Edit Competitor';
    const submitButton = document.querySelector('#competitor-form button[type="submit"]');
    submitButton.innerHTML = `
        <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path>
        </svg>
        Update
    `;
}

// Delete competitor
export async function deleteCompetitor(id) {
    if (!confirm('Are you sure you want to delete this competitor?')) return;

    const competitor = getState('competitors').find(c => c.id === id);

    try {
        await apiDeleteCompetitor(id, competitor?.version);
        await loadCompetitors();
        renderCompetitors();
        showMessage('Competitor deleted successfully!', 'success');
    } catch (error) {
        if (error.isConflict || error.isNotFound) {
            await loadCompetitors();
            renderCompetitors();
            showMessage(error.isConflict
                ? 'Someone else changed this competitor in the meantime. The list is refreshed - please check it and delete again if needed.'
                : 'This competitor was already deleted by someone else.', 'error');
            return;
        }
        showMessage('Error deleting competitor', 'error');
    }
}

// Manage competitor starts (navigate to starts section)
export async function manageCompetitorStarts(competitorId) {
    const competitors = getState('competitors');
    const competitor = competitors.find(c => c.id === competitorId);
    if (!competitor) {
        showMessage('Competitor not found', 'error');
        return;
    }
    
    setState('selectedCompetitor', competitor);
    
    // Navigate to starts section
    const navigationModule = await import('../navigation.js');
    navigationModule.showSection('starts');
    
    // Display competitor info and starts management
    const startsModule = await import('./starts.js');
    startsModule.displayCompetitorInfo(competitor);
    startsModule.displayStartsManagement(competitor);
    setElementValue('competitor-search', competitor.name);
}

// Setup event listeners for competitor section
export function setupCompetitorEventListeners() {
    // Add competitor button
    const addCompetitorBtn = document.getElementById('add-competitor-btn');
    if (addCompetitorBtn) {
        addCompetitorBtn.addEventListener('click', showCompetitorForm);
    }
    
    // Year of birth can't be in the future
    const yearOfBirthInput = document.getElementById('competitor-year-of-birth');
    if (yearOfBirthInput) {
        yearOfBirthInput.max = new Date().getFullYear();
    }

    // Competitor form
    const competitorForm = document.getElementById('competitor-data-form');
    if (competitorForm) {
        competitorForm.addEventListener('submit', saveCompetitor);
    }
    
    // Cancel button
    const cancelBtn = document.getElementById('cancel-competitor-form-btn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', hideCompetitorForm);
    }
    
    // Close button (X button)
    const closeBtn = document.getElementById('close-competitor-form-btn');
    if (closeBtn) {
        closeBtn.addEventListener('click', hideCompetitorForm);
    }
}