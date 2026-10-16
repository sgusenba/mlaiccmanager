// Competitor management module

import { getState, setState, updateState } from '../config.js';
import { loadCompetitors, saveCompetitor as apiSaveCompetitor, deleteCompetitor as apiDeleteCompetitor } from '../api.js';
import { countryLabel, getCountries } from '../countries.js';
import { t } from '../i18n.js';
import { showMessage, hideElement, showElement, setElementContent, getElementValue, setElementValue, clearForm } from '../utils.js';
import { withButtonFeedback } from '../buttonFeedback.js';

const NOT_SPECIFIED = '__none__';
const FILTER_IDS = ['competitors-search', 'competitors-filter-gender', 'competitors-filter-club', 'competitors-filter-country'];

function filterValue(elementId) {
    return (getElementValue(elementId) ?? '').trim();
}

// Rebuild a filter dropdown from the values present, keeping the current selection
// (label: how a value is shown)
function refreshFilterOptions(selectId, values, label = value => value) {
    const select = document.getElementById(selectId);
    if (!select) return;
    const selected = select.value;
    const distinct = [...new Set(values.filter(Boolean))].sort((a, b) => label(a).localeCompare(label(b)));
    select.innerHTML = '';
    select.add(new Option(t('common.all'), ''));
    distinct.forEach(value => select.add(new Option(label(value), value)));
    if (values.some(value => !value)) {
        select.add(new Option(t('common.notSpecified'), NOT_SPECIFIED));
    }
    select.value = [...select.options].some(o => o.value === selected) ? selected : '';
}

function matchesSelect(value, filter) {
    if (!filter) return true;
    if (filter === NOT_SPECIFIED) return !value;
    return value === filter;
}

// Apply the search box and dropdown filters to the competitor list
function filterCompetitors(competitors) {
    const terms = filterValue('competitors-search').toLowerCase().split(/\s+/).filter(Boolean);
    const gender = filterValue('competitors-filter-gender');
    const club = filterValue('competitors-filter-club');
    const country = filterValue('competitors-filter-country');

    return competitors.filter(competitor => {
        if (!matchesSelect(competitor.gender, gender)) return false;
        if (!matchesSelect(competitor.club, club)) return false;
        if (!matchesSelect(competitor.country, country)) return false;
        if (terms.length === 0) return true;

        const haystack = [
            competitor.name,
            competitor.year_of_birth,
            competitor.club,
            competitor.email,
            competitor.phone,
            competitor.country,
            competitor.country && countryLabel(competitor.country),
            competitor.gender && genderLabel(competitor.gender),
            competitor.address
        ].filter(Boolean).join(' ').toLowerCase();
        // Every word must match somewhere; "7" or "#7" matches ID 7 exactly (not 17)
        const id = String(competitor.id);
        return terms.every(term => term === id || term === `#${id}` || haystack.includes(term));
    });
}

// The gender as shown; stored values are male, female and other
export function genderLabel(gender) {
    return ['male', 'female', 'other'].includes(gender) ? t(`gender.${gender}`) : gender;
}

function hasActiveFilters() {
    return FILTER_IDS.some(id => filterValue(id) !== '');
}

function clearCompetitorFilters() {
    FILTER_IDS.forEach(id => setElementValue(id, ''));
    renderCompetitors();
}

// Render competitors table
export function renderCompetitors() {
    const tbody = document.getElementById('competitors-table-body');
    const allCompetitors = getState('competitors');

    refreshFilterOptions('competitors-filter-club', allCompetitors.map(c => c.club));
    refreshFilterOptions('competitors-filter-country', allCompetitors.map(c => c.country), countryLabel);

    const competitors = filterCompetitors(allCompetitors);
    const filtered = hasActiveFilters();

    setElementContent('competitors-count', filtered
        ? t('competitors.countFiltered', { shown: competitors.length, count: allCompetitors.length })
        : t('competitors.count', { count: allCompetitors.length }));
    if (filtered) {
        showElement('competitors-clear-filters-btn');
    } else {
        hideElement('competitors-clear-filters-btn');
    }

    hideElement('no-competitors');
    hideElement('no-matching-competitors');
    if (competitors.length === 0) {
        setElementContent('competitors-table-body', '');
        showElement(allCompetitors.length === 0 ? 'no-competitors' : 'no-matching-competitors');
        return;
    }

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
                ${competitor.gender ? genderLabel(competitor.gender) : t('common.notSpecified')}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.year_of_birth || t('common.notSpecified')}
            </td>
            <td class="px-4 py-4 text-sm text-gray-500">
                ${competitor.club || t('common.notSpecified')}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.email || t('common.notProvided')}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.phone || t('common.notProvided')}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                ${competitor.country ? countryLabel(competitor.country) : t('common.notSpecified')}
            </td>
            <td class="px-4 py-4 text-sm text-gray-500">
                ${competitor.address || t('common.notProvided')}
            </td>
            <td class="px-4 py-4 whitespace-nowrap text-sm font-medium">
                <div class="flex space-x-2">
                    <button data-action="manage-starts" data-competitor-id="${competitor.id}" class="text-green-600 hover:text-green-900" title="${t('competitors.manageStarts')}">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path>
                        </svg>
                    </button>
                    <button data-action="edit-competitor" data-competitor-id="${competitor.id}" class="text-blue-600 hover:text-blue-900" title="${t('common.edit')}">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
                        </svg>
                    </button>
                    <button data-action="delete-competitor" data-competitor-id="${competitor.id}" class="text-red-600 hover:text-red-900" title="${t('common.delete')}">
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
            deleteCompetitor(competitorId, button);
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
    document.querySelector('#competitor-form h3').textContent = t('competitors.form.addTitle');
    const submitButton = document.querySelector('#competitor-form button[type="submit"]');
    submitButton.innerHTML = `
        <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
        </svg>
        ${t('common.save')}
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
        country: getElementValue('competitor-country'),
        address: getElementValue('competitor-address')
        // starts are managed in the Starts section and never sent from this form
    };

    const currentEditingId = getState('currentEditingId');
    if (currentEditingId) {
        data.id = currentEditingId;
        data.version = getState('currentEditingVersion');
    }

    const submitButton = event.submitter ?? document.querySelector('#competitor-form button[type="submit"]');
    try {
        await withButtonFeedback(submitButton, () => apiSaveCompetitor(data));
        hideCompetitorForm();
        await loadCompetitors();
        renderCompetitors();
    } catch (error) {
        if (currentEditingId && (error.isConflict || error.isNotFound)) {
            await handleCompetitorSaveConflict(error);
            return;
        }
        showMessage(t('competitors.error.save'), 'error');
    }
}

// Someone else changed or deleted the competitor while this form was open
async function handleCompetitorSaveConflict(error) {
    await loadCompetitors();
    renderCompetitors();

    const current = error.body?.current;
    if (error.isConflict && current) {
        fillCompetitorForm(current);
        showMessage(t('competitors.conflict.changed'), 'error');
    } else {
        hideCompetitorForm();
        showMessage(t('competitors.conflict.deleted'), 'error');
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
    // Keep countries saved before the dropdown existed selectable
    const countrySelect = document.getElementById('competitor-country');
    if (competitor.country && ![...countrySelect.options].some(o => o.value === competitor.country)) {
        countrySelect.add(new Option(countryLabel(competitor.country), competitor.country));
    }
    setElementValue('competitor-country', competitor.country || '');
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
    document.querySelector('#competitor-form h3').textContent = t('competitors.form.editTitle');
    const submitButton = document.querySelector('#competitor-form button[type="submit"]');
    submitButton.innerHTML = `
        <svg class="h-4 w-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"></path>
        </svg>
        ${t('common.update')}
    `;
}

// Delete competitor
export async function deleteCompetitor(id, button) {
    if (!confirm(t('competitors.confirmDelete'))) return;

    const competitor = getState('competitors').find(c => c.id === id);

    try {
        await withButtonFeedback(button, () => apiDeleteCompetitor(id, competitor?.version));
        await loadCompetitors();
        renderCompetitors();
    } catch (error) {
        if (error.isConflict || error.isNotFound) {
            await loadCompetitors();
            renderCompetitors();
            showMessage(error.isConflict
                ? t('competitors.conflict.changedBeforeDelete')
                : t('competitors.conflict.alreadyDeleted'), 'error');
            return;
        }
        showMessage(t('competitors.error.delete'), 'error');
    }
}

// Manage competitor starts (navigate to starts section)
export async function manageCompetitorStarts(competitorId) {
    const competitors = getState('competitors');
    const competitor = competitors.find(c => c.id === competitorId);
    if (!competitor) {
        showMessage(t('competitors.notFound'), 'error');
        return;
    }
    
    setState('selectedCompetitor', competitor);
    
    // Navigate to starts section
    const navigationModule = await import('../navigation.js');
    navigationModule.navigateTo('starts');
    
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
    
    // Search & filters
    const searchInput = document.getElementById('competitors-search');
    if (searchInput) {
        searchInput.addEventListener('input', renderCompetitors);
    }
    ['competitors-filter-gender', 'competitors-filter-club', 'competitors-filter-country'].forEach(id => {
        const select = document.getElementById(id);
        if (select) {
            select.addEventListener('change', renderCompetitors);
        }
    });
    const clearFiltersBtn = document.getElementById('competitors-clear-filters-btn');
    if (clearFiltersBtn) {
        clearFiltersBtn.addEventListener('click', clearCompetitorFilters);
    }

    // Year of birth can't be in the future
    const yearOfBirthInput = document.getElementById('competitor-year-of-birth');
    if (yearOfBirthInput) {
        yearOfBirthInput.max = new Date().getFullYear();
    }

    const countrySelect = document.getElementById('competitor-country');
    if (countrySelect) {
        getCountries().forEach(({ value, label }) => countrySelect.add(new Option(label, value)));
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