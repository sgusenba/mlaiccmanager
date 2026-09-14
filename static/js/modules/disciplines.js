// Discipline management module

import { getState, setState } from '../config.js';
import { loadAvailableDisciplines as apiLoadAvailableDisciplines, loadActiveDisciplines as apiLoadActiveDisciplines, saveActiveDisciplines as apiSaveActiveDisciplines } from '../api.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, clearForm } from '../utils.js';

// Wrapper functions for API calls
export async function loadAvailableDisciplines() {
    return await apiLoadAvailableDisciplines();
}

export async function loadActiveDisciplines() {
    return await apiLoadActiveDisciplines();
}

// Render active disciplines display
export function renderActiveDisciplines() {
    const display = document.getElementById('active-disciplines-display');
    const activeDisciplines = getState('activeDisciplines');
    const availableDisciplines = getState('availableDisciplines');
    
    if (activeDisciplines.length === 0) {
        setElementContent('active-disciplines-display', `
            <div class="text-center py-8 text-gray-500">
                <p>No active disciplines. Click "Manage Active Disciplines" to activate disciplines for this competition.</p>
            </div>
        `);
        return;
    }
    
    display.innerHTML = activeDisciplines.map(disciplineId => {
        const discipline = availableDisciplines.find(d => d.id === disciplineId);
        if (!discipline) return '';
        
        const scoringColor = discipline.scoring_type === 'time' ? 'text-blue-600 bg-blue-100' :
                           discipline.scoring_type === 'points' ? 'text-green-600 bg-green-100' :
                           'text-purple-600 bg-purple-100';
        
        return `
            <div class="bg-white rounded-lg shadow-md p-6 hover:shadow-lg transition-shadow">
                <div class="flex justify-between items-start mb-4">
                    <div class="flex items-center">
                        <svg class="h-6 w-6 text-blue-600 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path>
                        </svg>
                        <h3 class="text-lg font-semibold text-gray-900">${discipline.event}</h3>
                    </div>
                    <div class="flex space-x-2">
                        <button data-action="deactivate-discipline" data-discipline-id="${discipline.id}" class="text-red-600 hover:text-red-900">
                            <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
                            </svg>
                        </button>
                    </div>
                </div>
                
                <div class="mb-4">
                    <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${scoringColor}">
                        ${discipline.scoring_type}
                    </span>
                    <span class="ml-2 text-sm text-gray-500">
                        ${discipline.category} • ${discipline.level} • ${discipline.type}
                    </span>
                </div>
                
                <div class="text-sm text-gray-600">
                    ${discipline.based_on ? `Based on: ${discipline.based_on}` : ''}
                    ${discipline.team_size ? ` • Team size: ${discipline.team_size}` : ''}
                </div>
            </div>
        `;
    }).join('');
    
    // Add event listeners to deactivate buttons
    display.querySelectorAll('button[data-action="deactivate-discipline"]').forEach(button => {
        button.addEventListener('click', handleDisciplineAction);
    });
}

// Handle discipline actions
function handleDisciplineAction(event) {
    const button = event.currentTarget;
    const action = button.dataset.action;
    const disciplineId = parseInt(button.dataset.disciplineId);
    
    switch (action) {
        case 'deactivate-discipline':
            deactivateDiscipline(disciplineId);
            break;
    }
}

// Show discipline activation modal
export function showDisciplineActivation() {
    showElement('discipline-activation-modal');
    renderAvailableDisciplinesForActivation();
}

// Hide discipline activation modal
export function hideDisciplineActivation() {
    hideElement('discipline-activation-modal');
}

// Hide discipline form
export function hideDisciplineForm() {
    hideElement('discipline-form');
    clearForm('discipline-data-form');
}

// Render available disciplines for activation
function renderAvailableDisciplinesForActivation() {
    const list = document.getElementById('available-disciplines-list');
    const availableDisciplines = getState('availableDisciplines');
    const activeDisciplines = getState('activeDisciplines');
    
    // Group disciplines by category
    const groupedDisciplines = {};
    availableDisciplines.forEach(discipline => {
        if (!groupedDisciplines[discipline.category]) {
            groupedDisciplines[discipline.category] = [];
        }
        groupedDisciplines[discipline.category].push(discipline);
    });
    
    list.innerHTML = Object.entries(groupedDisciplines).map(([category, disciplines]) => {
        const isActive = activeDisciplines.some(id => {
            const discipline = availableDisciplines.find(d => d.id === id);
            return discipline && discipline.category === category;
        });
        
        return `
            <div class="mb-6">
                <div class="flex items-center justify-between mb-3">
                    <h4 class="text-lg font-semibold text-gray-900 capitalize">${category} Events</h4>
                    <div class="flex items-center space-x-2">
                        <input type="checkbox" 
                               id="select-all-${category}" 
                               data-action="toggle-category"
                               data-category="${category}"
                               ${isActive ? 'checked' : ''} 
                               class="mr-2">
                        <label for="select-all-${category}" class="text-sm text-gray-600">Select All</label>
                    </div>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
                    ${disciplines.map(discipline => {
                        const isDisciplineActive = activeDisciplines.includes(discipline.id);
                        return `
                            <div class="border rounded-lg p-3 ${isDisciplineActive ? 'border-green-300 bg-green-50' : 'border-gray-200'}">
                                <div class="flex items-start">
                                    <input type="checkbox" 
                                           data-action="discipline-checkbox"
                                           value="${discipline.id}" 
                                           data-category="${category}"
                                           ${isDisciplineActive ? 'checked' : ''} 
                                           class="mr-3 mt-1">
                                    <div>
                                        <div class="font-medium text-gray-900">${discipline.event}</div>
                                        <div class="text-sm text-gray-500">
                                            ${discipline.level} • ${discipline.type}
                                        </div>
                                        ${discipline.based_on ? `
                                            <div class="text-xs text-gray-400">
                                                Based on: ${discipline.based_on}
                                            </div>
                                        ` : ''}
                                        ${discipline.team_size ? `
                                            <div class="text-xs text-gray-400">
                                                Team size: ${discipline.team_size}
                                            </div>
                                        ` : ''}
                                    </div>
                                </div>
                            </div>
                        `;
                    }).join('')}
                </div>
            </div>
        `;
    }).join('');
    
    // Add event listeners to checkboxes
    list.querySelectorAll('input[data-action]').forEach(checkbox => {
        checkbox.addEventListener('change', handleDisciplineCheckbox);
    });
}

// Handle discipline checkbox changes
function handleDisciplineCheckbox(event) {
    const checkbox = event.currentTarget;
    const action = checkbox.dataset.action;
    const category = checkbox.dataset.category;
    
    if (action === 'toggle-category') {
        toggleCategoryDisciplines(category, checkbox.checked);
    } else if (action === 'discipline-checkbox') {
        updateSelectAllCheckbox(category);
    }
}

// Toggle all disciplines in a category
function toggleCategoryDisciplines(category, checked) {
    const checkboxes = document.querySelectorAll(`#available-disciplines-list input[data-action="discipline-checkbox"]`);
    const availableDisciplines = getState('availableDisciplines');
    
    checkboxes.forEach(checkbox => {
        const discipline = availableDisciplines.find(d => d.id === parseInt(checkbox.value));
        if (discipline && discipline.category === category) {
            checkbox.checked = checked;
        }
    });
}

// Update select all checkbox state
function updateSelectAllCheckbox(category) {
    const categoryCheckboxes = document.querySelectorAll(`#available-disciplines-list input[data-action="discipline-checkbox"]`);
    const availableDisciplines = getState('availableDisciplines');
    
    const categoryCheckboxesArray = Array.from(categoryCheckboxes).filter(checkbox => {
        const discipline = availableDisciplines.find(d => d.id === parseInt(checkbox.value));
        return discipline && discipline.category === category;
    });
    
    const allChecked = categoryCheckboxesArray.every(cb => cb.checked);
    const selectAllCheckbox = document.getElementById(`select-all-${category}`);
    if (selectAllCheckbox) {
        selectAllCheckbox.checked = allChecked;
    }
}

// Save active disciplines
export async function saveActiveDisciplines() {
    const checkboxes = document.querySelectorAll('#available-disciplines-list input[type="checkbox"]:checked');
    const disciplineIds = Array.from(checkboxes).map(cb => parseInt(cb.value));
    
    try {
        await apiSaveActiveDisciplines(disciplineIds);
        hideDisciplineActivation();
        await loadActiveDisciplines();
        showMessage('Active disciplines updated successfully!', 'success');
    } catch (error) {
        showMessage('Error updating active disciplines', 'error');
    }
}

// Deactivate discipline
export async function deactivateDiscipline(disciplineId) {
    if (!confirm('Are you sure you want to deactivate this discipline?')) return;
    
    const activeDisciplines = getState('activeDisciplines');
    const newActiveDisciplines = activeDisciplines.filter(id => id !== disciplineId);
    
    try {
        await apiSaveActiveDisciplines(newActiveDisciplines);
        await loadActiveDisciplines();
        showMessage('Discipline deactivated successfully!', 'success');
    } catch (error) {
        showMessage('Error deactivating discipline', 'error');
    }
}

// Populate competitor disciplines checkboxes
export function populateCompetitorDisciplines() {
    const container = document.getElementById('competitor-disciplines-container');
    if (!container) return;
    
    const availableDisciplines = getState('availableDisciplines');
    const activeDisciplines = getState('activeDisciplines');
    
    // Filter to only show active disciplines
    const activeDisciplinesList = availableDisciplines.filter(discipline => 
        activeDisciplines.includes(discipline.id)
    );
    
    // Group active disciplines by category
    const groupedDisciplines = {};
    activeDisciplinesList.forEach(discipline => {
        if (!groupedDisciplines[discipline.category]) {
            groupedDisciplines[discipline.category] = [];
        }
        groupedDisciplines[discipline.category].push(discipline);
    });
    
    let html = '';
    Object.keys(groupedDisciplines).sort().forEach(category => {
        html += `<div class="border border-gray-200 rounded-lg p-3">
            <h4 class="text-sm font-medium text-gray-700 mb-2">${category}</h4>
            <div class="space-y-2">`;
        
        groupedDisciplines[category].forEach(discipline => {
            html += `
                <div class="flex items-center space-x-2 p-2 border border-gray-100 rounded">
                    <input type="checkbox" value="${discipline.id}" id="discipline-${discipline.id}" 
                           class="form-checkbox h-4 w-4 text-blue-600">
                    <label for="discipline-${discipline.id}" class="text-sm text-gray-700">
                        ${discipline.event}
                    </label>
                </div>
            `;
        });
        
        html += `</div></div>`;
    });
    
    setElementContent('competitor-disciplines-container', html);
}

// Setup event listeners for discipline section
export function setupDisciplineEventListeners() {
    // Manage active disciplines button
    const manageBtn = document.getElementById('manage-disciplines-btn');
    if (manageBtn) {
        manageBtn.addEventListener('click', showDisciplineActivation);
    }
    
    // Discipline activation modal
    const modal = document.getElementById('discipline-activation-modal');
    if (modal) {
        // Cancel button
        const cancelBtn = document.getElementById('cancel-discipline-modal-btn');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', hideDisciplineActivation);
        }
        
        // Save button
        const saveBtn = document.getElementById('save-disciplines-btn');
        if (saveBtn) {
            saveBtn.addEventListener('click', saveActiveDisciplines);
        }
        
        // Close button
        const closeBtn = document.getElementById('close-discipline-modal-btn');
        if (closeBtn) {
            closeBtn.addEventListener('click', hideDisciplineActivation);
        }
    }
    
    // Discipline form
    const disciplineForm = document.getElementById('discipline-data-form');
    if (disciplineForm) {
        disciplineForm.addEventListener('submit', saveDiscipline);
    }
    
    // Discipline form close button
    const closeFormBtn = document.getElementById('close-discipline-form-btn');
    if (closeFormBtn) {
        closeFormBtn.addEventListener('click', hideDisciplineForm);
    }
    
    // Discipline form cancel button
    const cancelFormBtn = document.getElementById('cancel-discipline-form-btn');
    if (cancelFormBtn) {
        cancelFormBtn.addEventListener('click', hideDisciplineForm);
    }
}

// Save discipline function
export async function saveDiscipline(event) {
    event.preventDefault();
    
    const data = {
        name: getElementValue('discipline-name'),
        description: getElementValue('discipline-description'),
        scoring_type: getElementValue('discipline-scoring-type'),
        unit: getElementValue('discipline-unit')
    };
    
    try {
        const response = await fetch('/api/disciplines', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(data)
        });
        
        if (response.ok) {
            hideDisciplineForm();
            await loadAvailableDisciplines();
            showMessage('Discipline added successfully!', 'success');
        } else {
            const error = await response.json();
            showMessage(error.error || 'Error saving discipline', 'error');
        }
    } catch (error) {
        showMessage('Error saving discipline', 'error');
    }
}