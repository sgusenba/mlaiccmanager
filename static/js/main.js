// Main entry point and module initialization

import { initializeApp } from './navigation.js';
import { setupCompetitorEventListeners } from './modules/competitors.js';
import { setupDisciplineEventListeners } from './modules/disciplines.js';
import { setupStartsEventListeners } from './modules/starts.js';
import { setupResultsEventListeners } from './modules/results.js';
import { setupRankingEventListeners } from './modules/ranking.js';

// Setup all event listeners when DOM is ready
document.addEventListener('DOMContentLoaded', function() {
    // Initialize the application
    initializeApp();
    
    // Setup event listeners for each section
    setupCompetitorEventListeners();
    setupDisciplineEventListeners();
    setupStartsEventListeners();
    setupResultsEventListeners();
    setupRankingEventListeners();
    
    console.log('Application initialized successfully');
});