// Navigation and DOM initialization

import { loadCompetitors, loadAvailableDisciplines, loadActiveDisciplines, loadResults } from './api.js';
import { showMessage } from './utils.js';

const SECTIONS = ['competitors', 'starts', 'results'];

// Show the section named in the URL's #hash (the sidebar links there);
// an unknown or missing hash shows the competitors
export function showSection(sectionName) {
    if (!SECTIONS.includes(sectionName)) sectionName = 'competitors';

    // Hide all sections
    document.querySelectorAll('.section').forEach(section => {
        section.classList.add('hidden');
    });
    
    // Show selected section
    document.getElementById(sectionName + '-section').classList.remove('hidden');
    
    // Load data for section
    switch (sectionName) {
        case 'competitors':
            loadCompetitorsData();
            break;
        case 'starts':
            loadStartsData();
            break;
        case 'results':
            loadResultsData();
            break;
    }
}

// Go to a section; the hash change shows it and updates the sidebar
export function navigateTo(sectionName) {
    if (location.hash === `#${sectionName}`) {
        showSection(sectionName);
    } else {
        location.hash = sectionName;
    }
}

// Load competitors data
async function loadCompetitorsData() {
    try {
        await loadCompetitors();
        const { renderCompetitors } = await import('./modules/competitors.js');
        renderCompetitors();
    } catch (error) {
        console.error('Error loading competitors data:', error);
    }
}

// Load starts data
async function loadStartsData() {
    try {
        await Promise.all([
            loadCompetitors(),
            loadAvailableDisciplines(),
            loadActiveDisciplines()
        ]);
    } catch (error) {
        console.error('Error loading starts data:', error);
    }
}

// Load results data
async function loadResultsData() {
    try {
        await Promise.all([
            loadCompetitors(),
            loadAvailableDisciplines(),
            loadActiveDisciplines(),
            loadResults()
        ]);
        const { renderResults } = await import('./modules/results.js');
        renderResults();
    } catch (error) {
        console.error('Error loading results data:', error);
    }
}

// Initialize application
export function initializeApp() {
    window.addEventListener('hashchange', () => showSection(location.hash.substring(1)));
    showSection(location.hash.substring(1));
    
    // Load all initial data
    loadInitialData();
}

// Load all initial data
async function loadInitialData() {
    try {
        await Promise.all([
            loadCompetitors().catch(err => { console.error('Competitors load failed:', err); }),
            loadAvailableDisciplines().catch(err => { console.error('Available disciplines load failed:', err); }),
            loadActiveDisciplines().catch(err => { console.error('Active disciplines load failed:', err); }),
            loadResults().catch(err => { console.error('Results load failed:', err); })
        ]);
        console.log('All data loaded successfully');
    } catch (error) {
        console.error('Error loading initial data:', error);
        showMessage('Error loading application data. Please refresh the page.', 'error');
    }
}