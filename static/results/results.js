// Standalone Enter Results page: the result form of the main page's results
// section, for a result entry station that stays open all day

import { loadCompetitors, loadAvailableDisciplines, loadActiveDisciplines, loadResults } from '../js/api.js';
import { searchStartById } from '../js/modules/results.js';

// Reload before every search, so starts and results added elsewhere since the page opened are found
async function search() {
    await Promise.all([loadCompetitors(), loadResults()]);
    searchStartById();
}

document.addEventListener('DOMContentLoaded', () => {
    loadCompetitors();
    loadAvailableDisciplines();
    loadActiveDisciplines();
    loadResults();

    const searchInput = document.getElementById('result-start-search');
    document.getElementById('search-start-btn').addEventListener('click', search);
    searchInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            search();
        }
    });

    // Back to the start ID field once a result is saved, deleted or cancelled, ready for the next scan
    const selectedStartInfo = document.getElementById('selected-start-info');
    new MutationObserver(() => {
        if (selectedStartInfo.classList.contains('hidden')) searchInput.focus();
    }).observe(selectedStartInfo, { attributes: true, attributeFilter: ['class'] });
});
