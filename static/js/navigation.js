// Navigation and DOM initialization

import { loadCompetitors, loadAvailableDisciplines, loadActiveDisciplines, loadResults } from './api.js';
import { showMessage } from './utils.js';

// Show specific section
export function showSection(sectionName) {
    // Hide all sections
    document.querySelectorAll('.section').forEach(section => {
        section.classList.add('hidden');
    });
    
    // Show selected section
    document.getElementById(sectionName + '-section').classList.remove('hidden');
    
    // Update nav active state
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.remove('bg-blue-700');
    });
    
    // Load data for section
    switch (sectionName) {
        case 'competitors':
            loadCompetitorsData();
            break;
        case 'disciplines':
            loadDisciplinesData();
            break;
        case 'starts':
            loadStartsData();
            break;
        case 'results':
            loadResultsData();
            break;
        case 'ranking':
            loadRankingData();
            break;
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

// Load disciplines data
async function loadDisciplinesData() {
    try {
        await loadAvailableDisciplines();
    } catch (error) {
        console.error('Error loading disciplines data:', error);
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

// Load ranking data
async function loadRankingData() {
    try {
        const { loadActiveDisciplinesForRanking } = await import('./modules/ranking.js');
        await loadActiveDisciplinesForRanking();
    } catch (error) {
        console.error('Error loading ranking data:', error);
    }
}

// Setup navigation event listeners
export function setupNavigationEventListeners() {
    // Navigation links
    document.querySelectorAll('.nav-link').forEach(link => {
        link.addEventListener('click', (event) => {
            event.preventDefault();
            const sectionName = link.getAttribute('href').substring(1); // Remove the #
            showSection(sectionName);
        });
    });
}

// Initialize application
export function initializeApp() {
    setupNavigationEventListeners();
    
    // Show competitors section by default
    showSection('competitors');
    
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