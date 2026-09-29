// Ranking page: one result per competitor and discipline (the best one),
// from /api/ranking/best, laid out for printing and exported as Word or
// Excel files. Team disciplines show the team ranking, which already has one
// total per team.

import { escapeHtml, disciplineDisplayName, teamRankingCard } from '../js/teamRanking.js';
import { loadMeet } from '../js/meet.js';
import { downloadBlob, exportFileName } from '../js/officeFiles.js';
import { coverPage, entryStatistics, statisticsPage } from './printPages.js';
import { rankingDocx, rankingXlsx, timestampLine } from './rankingExport.js';
import { individualCard } from './rankingCard.js';

const DISCIPLINE_KEY = 'ranking.discipline';
const PAGE_BREAK_KEY = 'ranking.pagePerDiscipline';
const COVER_KEY = 'ranking.printCover';
const STATS_KEY = 'ranking.printStatistics';
const FORMAT_KEY = 'ranking.exportFormat';
const RESULT_LABEL_KEY = 'ranking.resultLabel';
const RESULT_LABELS = ['Intermediate Result', 'Final Result'];
const FORMATS = ['print', 'word', 'excel'];

// --- helpers ---------------------------------------------------------------

async function api(path) {
    const response = await fetch(`/api${path}`);
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new Error(json?.error || `HTTP error! status: ${response.status}`);
    return json;
}

let messageTimer;
function showMessage(text) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = 'mx-4 mb-4 px-4 py-3 rounded-md no-print bg-red-100 text-red-800 border border-red-200';
    clearTimeout(messageTimer);
    messageTimer = setTimeout(() => el.classList.add('hidden'), 8000);
}

function readStored(key) {
    try { return localStorage.getItem(key); } catch { return null; }
}

function store(key, value) {
    try { localStorage.setItem(key, value); } catch { /* private mode: not remembered */ }
}

// --- rendering -------------------------------------------------------------

function card(data, extraClass) {
    return data.kind === 'team' ? teamRankingCard(data, { extraClass }) : individualCard(data, { extraClass });
}

function emptyState(text) {
    return `
        <div class="text-center py-12">
            <h3 class="text-lg font-medium text-gray-900 mb-2">No ranking data available</h3>
            <p class="text-gray-500">${text}</p>
        </div>`;
}

// What the page shows, for the Word and Excel export
let shownRankings = [];

function render(rankings, emptyText) {
    const pagePerDiscipline = document.getElementById('page-per-discipline').checked;
    const content = document.getElementById('ranking-content');
    const withData = rankings.filter(data => data.kind === 'team' || data.rankings?.length);
    shownRankings = withData;

    if (withData.length === 0) {
        content.innerHTML = emptyState(emptyText);
        return;
    }
    content.innerHTML = withData
        .map((data, i) => card(data, pagePerDiscipline && i < withData.length - 1 ? 'print-break' : 'keep-together'))
        .join('');
}

// --- loading ---------------------------------------------------------------

async function loadDisciplines() {
    const [active, available] = await Promise.all([api('/active-disciplines'), api('/available-disciplines')]);
    const select = document.getElementById('discipline-select');
    select.innerHTML = '<option value="">All disciplines</option>' + (active || [])
        .map(id => available.find(d => d.id === id))
        .filter(Boolean)
        .map(d => `<option value="${d.id}">${escapeHtml(disciplineDisplayName(d.event, d.type))}</option>`)
        .join('');

    const stored = readStored(DISCIPLINE_KEY);
    select.value = [...select.options].some(o => o.value === stored) ? stored : '';
}

async function loadRanking() {
    const disciplineId = document.getElementById('discipline-select').value;
    try {
        if (disciplineId) {
            render([await api(`/ranking/best/${disciplineId}`)], 'There are no starts or results for this discipline yet.');
        } else {
            render(Object.values(await api('/ranking/best')), 'There are no active disciplines with starts or results to display.');
        }
    } catch (error) {
        shownRankings = [];
        showMessage(`Error loading ranking: ${error.message}`);
    }
}

// --- printing --------------------------------------------------------------

// Loaded ahead of time: the browser's own print command (Ctrl+P) cannot wait for it
let printData = null;

async function loadPrintData() {
    try {
        const [meet, competitors, disciplines] = await Promise.all([
            loadMeet(), api('/competitors'), api('/available-disciplines')
        ]);
        printData = { meet, stats: entryStatistics(competitors || [], disciplines || []) };
    } catch (error) {
        printData = null;
        showMessage(`Error loading the meet details for printing: ${error.message}`);
    }
}

function printScope() {
    const select = document.getElementById('discipline-select');
    return select.value ? select.options[select.selectedIndex].text : 'All disciplines';
}

// "Intermediate Result", "Final Result" or '' as chosen in the export dialog
function resultLabel() {
    const stored = readStored(RESULT_LABEL_KEY);
    return RESULT_LABELS.includes(stored) ? stored : '';
}

function applyPrintOptions() {
    document.body.classList.toggle('with-cover', readStored(COVER_KEY) === 'true');
    document.body.classList.toggle('with-stats', readStored(STATS_KEY) === 'true');
}

// Also runs for the browser's own print command (Ctrl+P)
function fillPrintHeader() {
    const meet = printData?.meet;
    document.querySelector('#print-header h1').textContent = meet?.name ? `${meet.name} · Ranking` : 'Ranking';
    document.getElementById('print-date').textContent = timestampLine(printScope());
    document.getElementById('print-watermark').textContent = resultLabel();
    applyPrintOptions();
    document.querySelector('#print-cover .cover-inner').innerHTML = meet ? coverPage(meet, printScope()) : '';
    document.getElementById('print-stats').innerHTML = printData ? statisticsPage(meet, printData.stats) : '';
}

const chosenFormat = () => document.querySelector('#print-dialog input[name="export-format"]:checked')?.value || 'print';

// A cover page and the watermark are part of a document; Excel only gets the statistics sheet
function updateCoverOption() {
    const excel = chosenFormat() === 'excel';
    document.getElementById('print-with-cover').disabled = excel;
    document.getElementById('print-cover-option').classList.toggle('opacity-50', excel);
    document.querySelectorAll('#print-dialog .print-result-label').forEach(box => { box.disabled = excel; });
    document.getElementById('print-result-options').classList.toggle('opacity-50', excel);
}

const resultLabelBoxes = () => [...document.querySelectorAll('#print-dialog .print-result-label')];
const chosenResultLabel = () => resultLabelBoxes().find(box => box.checked)?.value || '';

function openPrintDialog() {
    const stored = readStored(FORMAT_KEY);
    const format = FORMATS.includes(stored) ? stored : 'print';
    document.querySelector(`#print-dialog input[name="export-format"][value="${format}"]`).checked = true;
    document.getElementById('print-with-cover').checked = readStored(COVER_KEY) === 'true';
    document.getElementById('print-with-stats').checked = readStored(STATS_KEY) === 'true';
    const label = resultLabel();
    resultLabelBoxes().forEach(box => { box.checked = box.value === label; });
    updateCoverOption();
    document.getElementById('print-dialog').showModal();
}

async function exportFromDialog() {
    const format = chosenFormat();
    const withCover = document.getElementById('print-with-cover').checked;
    const withStats = document.getElementById('print-with-stats').checked;
    store(FORMAT_KEY, format);
    store(COVER_KEY, String(withCover));
    store(STATS_KEY, String(withStats));
    store(RESULT_LABEL_KEY, chosenResultLabel());
    await Promise.all([loadPrintData(), loadRanking()]);
    if (format === 'print') {
        window.print();
        return;
    }
    if (shownRankings.length === 0) {
        showMessage('There is no ranking to export yet.');
        return;
    }
    const options = {
        meet: printData?.meet,
        stats: printData?.stats,
        scope: printScope(),
        resultLabel: resultLabel(),
        rankings: shownRankings,
        withCover,
        withStats,
        pagePerDiscipline: document.getElementById('page-per-discipline').checked
    };
    try {
        if (format === 'excel') {
            downloadBlob(rankingXlsx(options), exportFileName('ranking', 'xlsx'));
        } else {
            downloadBlob(rankingDocx(options), exportFileName('ranking', 'docx'));
        }
    } catch (error) {
        showMessage(`Could not export the ranking: ${error.message}`);
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    const select = document.getElementById('discipline-select');
    const pageBreak = document.getElementById('page-per-discipline');
    pageBreak.checked = readStored(PAGE_BREAK_KEY) === 'true';

    select.addEventListener('change', () => {
        store(DISCIPLINE_KEY, select.value);
        loadRanking();
    });
    pageBreak.addEventListener('change', () => {
        store(PAGE_BREAK_KEY, String(pageBreak.checked));
        loadRanking();
    });
    document.getElementById('refresh-btn').addEventListener('click', () => {
        loadRanking();
        loadPrintData();
    });
    document.getElementById('print-btn').addEventListener('click', openPrintDialog);
    // The dialog's form closes it; its Export button also exports
    document.querySelector('#print-dialog form').addEventListener('submit', event => {
        if (event.submitter?.value === 'export') exportFromDialog();
    });
    document.querySelectorAll('#print-dialog input[name="export-format"]').forEach(radio => {
        radio.addEventListener('change', updateCoverOption);
    });
    // Intermediate or final: checking one unchecks the other
    resultLabelBoxes().forEach(box => box.addEventListener('change', () => {
        if (box.checked) resultLabelBoxes().forEach(other => { if (other !== box) other.checked = false; });
    }));
    window.addEventListener('beforeprint', fillPrintHeader);
    applyPrintOptions();
    loadPrintData();

    try {
        await loadDisciplines();
    } catch (error) {
        showMessage(`Error loading disciplines: ${error.message}`);
    }
    loadRanking();
});
