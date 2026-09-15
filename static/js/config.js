// Configuration and centralized state management

const API_BASE = '/api';

// Centralized state object
const state = {
    competitors: [],
    availableDisciplines: [],
    activeDisciplines: [],
    results: [],
    selectedCompetitor: null,
    selectedStart: null,
    currentEditingId: null,
    currentEditingVersion: null,
    currentEditingResultId: null,
    currentEditingResultVersion: null,
    activeDisciplinesBase: null
};

// State getter/setter functions for controlled access
const getState = (key) => state[key];
const setState = (key, value) => { state[key] = value; };
const updateState = (updates) => {
    Object.keys(updates).forEach(key => {
        if (key in state) {
            state[key] = updates[key];
        }
    });
};

// Export for use in other modules
export { API_BASE, getState, setState, updateState, state };