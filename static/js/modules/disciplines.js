// Discipline management module

import { loadAvailableDisciplines as apiLoadAvailableDisciplines, loadActiveDisciplines as apiLoadActiveDisciplines } from '../api.js';

// Wrapper functions for API calls
export async function loadAvailableDisciplines() {
    return await apiLoadAvailableDisciplines();
}

export async function loadActiveDisciplines() {
    return await apiLoadActiveDisciplines();
}
