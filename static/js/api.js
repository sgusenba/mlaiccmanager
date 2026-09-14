// API calls and data loading functions

import { API_BASE, getState, setState } from './config.js';

// Generic API helper with error handling
async function apiCall(endpoint, options = {}) {
    try {
        const response = await fetch(`${API_BASE}${endpoint}`, options);
        if (!response.ok) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }
        return await response.json();
    } catch (error) {
        console.error(`API call to ${endpoint} failed:`, error);
        throw error;
    }
}

// Load competitors
export async function loadCompetitors() {
    try {
        const data = await apiCall('/competitors');
        setState('competitors', Array.isArray(data) ? data : []);
        return getState('competitors');
    } catch (error) {
        console.error('Error loading competitors:', error);
        setState('competitors', []);
        return [];
    }
}

// Load available disciplines
export async function loadAvailableDisciplines() {
    try {
        const data = await apiCall('/available-disciplines');
        setState('availableDisciplines', Array.isArray(data) ? data : []);
        return getState('availableDisciplines');
    } catch (error) {
        console.error('Error loading available disciplines:', error);
        setState('availableDisciplines', []);
        return [];
    }
}

// Load active disciplines
export async function loadActiveDisciplines() {
    try {
        const data = await apiCall('/active-disciplines');
        setState('activeDisciplines', Array.isArray(data) ? data : []);
        return getState('activeDisciplines');
    } catch (error) {
        console.error('Error loading active disciplines:', error);
        setState('activeDisciplines', []);
        return [];
    }
}

// Load results
export async function loadResults() {
    try {
        const data = await apiCall('/results');
        setState('results', Array.isArray(data) ? data : []);
        return getState('results');
    } catch (error) {
        console.error('Error loading results:', error);
        setState('results', []);
        return [];
    }
}

// Save competitor
export async function saveCompetitor(data) {
    return await apiCall('/competitors', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
}

// Delete competitor
export async function deleteCompetitor(id) {
    return await apiCall(`/competitors/${id}`, { method: 'DELETE' });
}

// Save active disciplines
export async function saveActiveDisciplines(disciplineIds) {
    return await apiCall('/active-disciplines', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ discipline_ids: disciplineIds })
    });
}

// Add start to competitor
export async function addStart(competitorId, disciplineId) {
    return await apiCall(`/competitors/${competitorId}/starts`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ discipline_id: disciplineId })
    });
}

// Delete start
export async function deleteStart(competitorId, generatedId) {
    return await apiCall(`/competitors/${competitorId}/starts/${generatedId}`, {
        method: 'DELETE'
    });
}

// Save result
export async function saveResult(data) {
    return await apiCall('/results', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
}

// Delete result
export async function deleteResult(id) {
    return await apiCall(`/results/${id}`, { method: 'DELETE' });
}

// Load ranking for specific discipline
export async function loadRanking(disciplineId) {
    if (disciplineId) {
        return await apiCall(`/ranking/${disciplineId}`);
    }
    return await apiCall('/ranking');
}

// Load all initial data
export async function loadInitialData() {
    try {
        await Promise.all([
            loadCompetitors(),
            loadAvailableDisciplines(),
            loadActiveDisciplines(),
            loadResults()
        ]);
        console.log('All data loaded successfully');
    } catch (error) {
        console.error('Error loading initial data:', error);
        throw error;
    }
}