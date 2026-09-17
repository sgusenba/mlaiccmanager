// Discipline management module

import { getState } from '../config.js';
import { loadAvailableDisciplines as apiLoadAvailableDisciplines, loadActiveDisciplines as apiLoadActiveDisciplines } from '../api.js';
import { showMessage, hideElement, setElementContent, getElementValue, clearForm } from '../utils.js';

// Wrapper functions for API calls
export async function loadAvailableDisciplines() {
    return await apiLoadAvailableDisciplines();
}

export async function loadActiveDisciplines() {
    return await apiLoadActiveDisciplines();
}

// Hide discipline form
export function hideDisciplineForm() {
    hideElement('discipline-form');
    clearForm('discipline-data-form');
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
