// Ranking management module

import { getState, setState } from '../config.js';
import { loadActiveDisciplines, loadRanking } from '../api.js';
import { showMessage, setElementContent, getElementValue } from '../utils.js';

// Load active disciplines for ranking dropdown
export async function loadActiveDisciplinesForRanking() {
    await loadActiveDisciplines();
    
    const rankingSelect = document.getElementById('ranking-discipline-select');
    const activeDisciplines = getState('activeDisciplines');
    const availableDisciplines = getState('availableDisciplines');
    
    rankingSelect.innerHTML = '<option value="">All Disciplines</option>' +
        activeDisciplines.map(disciplineId => {
            const discipline = availableDisciplines.find(d => d.id === disciplineId);
            return discipline ? `<option value="${disciplineId}">${discipline.event}</option>` : '';
        }).join('');
    
    // Show all disciplines by default
    rankingSelect.value = '';
    loadRankingData();
}

// Load ranking data
async function loadRankingData() {
    const disciplineId = getElementValue('ranking-discipline-select');
    
    if (!disciplineId) {
        // Show all disciplines
        try {
            const data = await loadRanking(null);
            renderAllRanking(data);
        } catch (error) {
            showMessage('Error loading ranking', 'error');
        }
        return;
    }
    
    try {
        const data = await loadRanking(disciplineId);
        renderRanking(data);
    } catch (error) {
        showMessage('Error loading ranking', 'error');
    }
}

// Render all rankings
function renderAllRanking(data) {
    const contentDiv = document.getElementById('ranking-content');
    
    if (Object.keys(data).length === 0) {
        contentDiv.innerHTML = `
            <div class="text-center py-12">
                <svg class="h-12 w-12 mx-auto mb-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z"></path>
                </svg>
                <h3 class="text-lg font-medium text-gray-900 mb-2">No ranking data available</h3>
                <p class="text-gray-500">There are no active disciplines with results to display.</p>
            </div>
        `;
        return;
    }
    
    contentDiv.innerHTML = Object.values(data).map(disciplineData => `
        <div class="bg-white rounded-lg shadow-md mb-8 print-break">
            <div class="px-6 py-4 border-b border-gray-200">
                <h3 class="text-lg font-semibold text-gray-900">
                    ${disciplineData.discipline.name}
                    <span class="text-sm text-gray-500 ml-2">(${disciplineData.discipline.category})</span>
                </h3>
            </div>
            
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Rank</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">ID</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Name</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 1</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 2</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 3</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 4</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">10s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">9s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">8s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">7s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">mm</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Notes</th>
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${disciplineData.rankings.map(item => {
                                const rankIcon = item.rank === 1 ? '🥇' : item.rank === 2 ? '🥈' : item.rank === 3 ? '🥉' : item.rank;
                                const rankColor = item.rank <= 3 ? 'font-bold' : '';
                                
                                return `
                                    <tr class="hover:bg-gray-50 ${rankColor}">
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="flex items-center">
                                                <div class="flex items-center justify-center w-8 h-8 rounded-full border-2 ${
                                                    item.rank === 1 ? 'bg-yellow-100 text-yellow-800 border-yellow-300' :
                                                    item.rank === 2 ? 'bg-gray-100 text-gray-800 border-gray-300' :
                                                    item.rank === 3 ? 'bg-orange-100 text-orange-800 border-orange-300' :
                                                    'bg-blue-50 text-blue-800 border-blue-200'
                                                }">
                                                    <span class="font-bold text-sm">${rankIcon}</span>
                                                </div>
                                            </div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="text-sm text-gray-900">${item.competitor.id}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="text-sm font-medium text-gray-900">${item.competitor.name}</div>
                                            ${item.competitor.team ? `
                                                <div class="text-sm text-gray-500">${item.competitor.team}</div>
                                            ` : ''}
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[0]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[1]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[2]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[3]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['10']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['9']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['8']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['7']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.override_value || '-'}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                            ${item.notes || ''}
                                        </td>
                                    </tr>
                                `;
                            }).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `).join('');
}

// Render single discipline ranking
function renderRanking(data) {
    const contentDiv = document.getElementById('ranking-content');
    
    if (!data.rankings || data.rankings.length === 0) {
        contentDiv.innerHTML = `
            <div class="text-center py-12">
                <svg class="h-12 w-12 mx-auto mb-4 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z"></path>
                </svg>
                <h3 class="text-lg font-medium text-gray-900 mb-2">No ranking data available</h3>
                <p class="text-gray-500">There are no results for this discipline yet.</p>
            </div>
        `;
        return;
    }
    
    contentDiv.innerHTML = `
        <div class="bg-white rounded-lg shadow-md">
            <div class="px-6 py-4 border-b border-gray-200">
                <h3 class="text-lg font-semibold text-gray-900">
                    ${data.discipline.name}
                    <span class="text-sm text-gray-500 ml-2">(${data.discipline.category})</span>
                </h3>
            </div>
            
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Rank</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">ID</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Name</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 1</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 2</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 3</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Result 4</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">10s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">9s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">8s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">7s</th>
                                <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">mm</th>
                                <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Notes</th>
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${data.rankings.map(item => {
                                const rankIcon = item.rank === 1 ? '🥇' : item.rank === 2 ? '🥈' : item.rank === 3 ? '🥉' : item.rank;
                                const rankColor = item.rank <= 3 ? 'font-bold' : '';
                                
                                return `
                                    <tr class="hover:bg-gray-50 ${rankColor}">
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="flex items-center">
                                                <div class="flex items-center justify-center w-8 h-8 rounded-full border-2 ${
                                                    item.rank === 1 ? 'bg-yellow-100 text-yellow-800 border-yellow-300' :
                                                    item.rank === 2 ? 'bg-gray-100 text-gray-800 border-gray-300' :
                                                    item.rank === 3 ? 'bg-orange-100 text-orange-800 border-orange-300' :
                                                    'bg-blue-50 text-blue-800 border-blue-200'
                                                }">
                                                    <span class="font-bold text-sm">${rankIcon}</span>
                                                </div>
                                            </div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="text-sm text-gray-900">${item.competitor.id}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap">
                                            <div class="text-sm font-medium text-gray-900">${item.competitor.name}</div>
                                            ${item.competitor.team ? `
                                                <div class="text-sm text-gray-500">${item.competitor.team}</div>
                                            ` : ''}
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[0]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[1]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[2]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.result_totals[3]}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['10']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['9']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['8']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.freq_counts['7']}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-center">
                                            <div class="text-sm text-gray-900">${item.override_value || '-'}</div>
                                        </td>
                                        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                                            ${item.notes || ''}
                                        </td>
                                    </tr>
                                `;
                            }).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    `;
}

// Setup event listeners for ranking section
export function setupRankingEventListeners() {
    // Ranking discipline select
    const rankingSelect = document.getElementById('ranking-discipline-select');
    if (rankingSelect) {
        rankingSelect.addEventListener('change', loadRankingData);
    }
    
    // Print button
    const printBtn = document.getElementById('print-ranking-btn');
    if (printBtn) {
        printBtn.addEventListener('click', () => window.print());
    }
}