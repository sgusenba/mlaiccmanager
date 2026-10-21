// Import Registrations page: reads a registration file (format
// "mlaic-registrations", written by the online bulk registration form) and
// creates its competitors and their starts through the regular API. A
// competitor already registered under the same name only gets the starts
// they lack, so importing a file twice changes nothing.

import { withButtonFeedback } from '../js/buttonFeedback.js';
import { countryLabel } from '../js/countries.js';
import { escapeHtml } from '../js/meet.js';
import { t } from '../js/i18n.js';

document.title = `${t('import.title')} · MLAICC Manager`;

const state = {
    file: null,          // the parsed file
    plan: [],            // one entry per starter of the file
    competitors: [],
    disciplines: new Map(),
    program: new Set(),
    meetName: ''
};

class ApiError extends Error {
    constructor(status, body) {
        super(body?.error || t('common.httpError', { status }));
        this.status = status;
    }
}

async function api(path, method = 'GET', body) {
    const options = { method, headers: {} };
    if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
    }
    const response = await fetch(`/api${path}`, options);
    const text = await response.text();
    let json = null;
    if (text) {
        try { json = JSON.parse(text); } catch { json = { error: text }; }
    }
    if (!response.ok) throw new ApiError(response.status, json);
    return json;
}

async function loadAppData() {
    const [competitors, catalog, program, meet] = await Promise.all([
        api('/competitors'),
        api('/available-disciplines'),
        api('/active-disciplines'),
        api('/meet').catch(() => ({}))
    ]);
    state.competitors = competitors || [];
    state.disciplines = new Map((catalog || []).map(d => [d.id, d]));
    state.program = new Set(program || []);
    state.meetName = meet?.name || '';
}

// --- reading the file -----------------------------------------------------------

function showFileError(text) {
    const el = document.getElementById('file-error');
    el.textContent = text;
    el.classList.toggle('hidden', !text);
}

function parse(text) {
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        throw new Error(t('common.invalidJson'));
    }
    if (data?.format !== 'mlaic-registrations' || !Array.isArray(data.starters)) {
        throw new Error(t('import.wrongFormat'));
    }
    if (Number(data.version) > 1) {
        throw new Error(t('import.newerVersion'));
    }
    return data;
}

// A discipline by its id, else by its short name if exactly one catalog discipline has it
function findDiscipline(start) {
    const byId = state.disciplines.get(Number(start.discipline_id));
    if (byId && (!start.short_name || !byId.short_name || byId.short_name === start.short_name)) return byId;
    const byShort = [...state.disciplines.values()].filter(d => d.short_name && d.short_name === start.short_name);
    return byShort.length === 1 ? byShort[0] : null;
}

const hasStart = (competitor, disciplineId) => (competitor?.starts?.[String(disciplineId)] || []).length > 0;

function planStarter(starter) {
    const name = String(starter.name || '').trim();
    const matches = state.competitors.filter(c => (c.name || '').trim().toLowerCase() === name.toLowerCase());
    const competitor = matches.length === 1 ? matches[0] : null;
    const starts = (starter.starts || []).map(start => {
        const discipline = findDiscipline(start);
        let status = 'new';
        if (!discipline) status = 'unknown';
        else if (!state.program.has(discipline.id)) status = 'notInProgram';
        else if (hasStart(competitor, discipline.id)) status = 'have';
        return { discipline, label: discipline?.short_name || discipline?.name || start.short_name || start.discipline || `#${start.discipline_id}`, status };
    });
    const ambiguous = matches.length > 1;
    const todo = starts.filter(s => s.status === 'new').length;
    return {
        starter: { ...starter, name },
        competitor,
        ambiguous,
        starts,
        include: !!name && !ambiguous && (todo > 0 || !competitor),
        result: null
    };
}

async function readFile(text) {
    showFileError('');
    try {
        state.file = parse(text);
        await loadAppData();
        state.plan = state.file.starters.map(planStarter);
        renderPreview();
    } catch (error) {
        state.file = null;
        document.getElementById('preview').classList.add('hidden');
        showFileError(error.message);
    }
}

// --- preview ------------------------------------------------------------------------

const STATUS_STYLE = {
    new: 'bg-blue-50 text-blue-800 border-blue-200',
    have: 'bg-gray-100 text-gray-500 border-gray-200 line-through',
    notInProgram: 'bg-red-50 text-red-700 border-red-200',
    unknown: 'bg-red-50 text-red-700 border-red-200',
    done: 'bg-green-50 text-green-800 border-green-200',
    failed: 'bg-red-50 text-red-700 border-red-200'
};

function startChip(start) {
    return `<span class="inline-block px-1.5 py-0.5 mr-1 mb-1 rounded border text-xs font-semibold ${STATUS_STYLE[start.status]}"
        title="${escapeHtml(`${start.discipline?.name || start.label} · ${t(`import.start.${start.status}`)}`)}">${escapeHtml(start.label)}</span>`;
}

function competitorCell(item) {
    if (!item.starter.name) return `<span class="text-red-700">${escapeHtml(t('import.noName'))}</span>`;
    if (item.ambiguous) return `<span class="text-red-700">${escapeHtml(t('import.ambiguous'))}</span>`;
    if (item.result?.created) return `<span class="text-green-700">${escapeHtml(t('import.createdNow'))}</span>`;
    if (item.competitor) return `<span class="text-gray-700">${escapeHtml(t('import.existing'))}</span>`;
    return `<span class="text-blue-800">${escapeHtml(t('import.new'))}</span>`;
}

function resultCell(item) {
    const r = item.result;
    if (!r) return '';
    const lines = [];
    if (r.ok) lines.push(`<span class="text-green-700">${escapeHtml(r.created ? t('import.result.created', { count: r.added }) : t('import.result.added', { count: r.added }))}</span>`);
    r.errors.forEach(e => lines.push(`<span class="block text-red-700">${escapeHtml(e)}</span>`));
    return lines.join('');
}

function renderPreview() {
    const file = state.file;
    const info = [
        file.meet?.name && t('import.fileMeet', { meet: file.meet.name }),
        // A file of the registration portal lists every club that registered; show how many
        Array.isArray(file.contacts) && file.contacts.length > 1
            ? t('import.fileClubs', { count: file.contacts.length })
            : file.contact?.club && t('import.fileClub', { club: file.contact.club }),
        file.contact?.name && t('import.fileContact', { name: [file.contact.name, file.contact.email].filter(Boolean).join(', ') })
    ].filter(Boolean).join(' · ');
    document.getElementById('file-info').textContent = info;

    const warning = document.getElementById('meet-warning');
    const differs = file.meet?.name && state.meetName && file.meet.name.trim().toLowerCase() !== state.meetName.trim().toLowerCase();
    warning.textContent = differs ? t('import.meetDiffers', { file: file.meet.name, meet: state.meetName }) : '';
    warning.classList.toggle('hidden', !differs);

    document.getElementById('rows').innerHTML = state.plan.map((item, i) => {
        const s = item.starter;
        const locked = !s.name || item.ambiguous || item.result?.ok;
        return `<tr class="border-t border-gray-100 align-top">
            <td class="px-3 py-2"><input type="checkbox" class="h-4 w-4" data-include="${i}" ${item.include && !locked ? 'checked' : ''} ${locked ? 'disabled' : ''} aria-label="${escapeHtml(s.name || '')}"></td>
            <td class="px-3 py-2 font-medium">${escapeHtml(s.name || '–')}${s.year_of_birth ? `<span class="block text-xs text-gray-500">${escapeHtml(s.year_of_birth)}</span>` : ''}</td>
            <td class="px-3 py-2">${escapeHtml(s.club || '')}</td>
            <td class="px-3 py-2">${escapeHtml(s.country ? countryLabel(s.country) : '')}</td>
            <td class="px-3 py-2">${competitorCell(item)}</td>
            <td class="px-3 py-2 min-w-[12rem]">${item.starts.map(startChip).join('') || '<span class="text-gray-400">–</span>'}</td>
            <td class="px-3 py-2 min-w-[10rem]">${resultCell(item)}</td>
        </tr>`;
    }).join('');

    renderSummary();
    document.getElementById('preview').classList.remove('hidden');
}

function renderSummary() {
    const chosen = state.plan.filter(item => item.include && !item.result?.ok);
    const newCompetitors = chosen.filter(item => !item.competitor).length;
    const starts = chosen.reduce((n, item) => n + item.starts.filter(s => s.status === 'new').length, 0);
    const skipped = state.plan.reduce((n, item) => n + item.starts.filter(s => s.status === 'notInProgram' || s.status === 'unknown').length, 0);
    document.getElementById('summary').innerHTML = [
        escapeHtml(t('import.summary', { competitors: newCompetitors, starts })),
        skipped ? `<span class="text-red-700">${escapeHtml(t('import.skipped', { count: skipped }))}</span>` : ''
    ].filter(Boolean).join(' · ');
    document.getElementById('import-btn').disabled = !chosen.length;
    const boxes = [...document.querySelectorAll('[data-include]:not(:disabled)')];
    const all = document.getElementById('select-all');
    all.checked = boxes.length > 0 && boxes.every(b => b.checked);
    all.disabled = !boxes.length;
}

// --- import -------------------------------------------------------------------------

async function importItem(item) {
    const result = { ok: false, created: false, added: 0, errors: [] };
    let competitor = item.competitor;
    if (!competitor) {
        const s = item.starter;
        try {
            competitor = await api('/competitors', 'POST', {
                name: s.name,
                gender: s.gender || null,
                year_of_birth: s.year_of_birth || null,
                club: s.club || null,
                country: s.country || null,
                email: s.email || null,
                phone: s.phone || null
            });
            item.competitor = competitor;
            result.created = true;
        } catch (error) {
            result.errors.push(error.message);
            return result;
        }
    }
    for (const start of item.starts.filter(st => st.status === 'new')) {
        try {
            await api(`/competitors/${competitor.id}/starts`, 'POST', { discipline_id: start.discipline.id });
            start.status = 'done';
            result.added++;
        } catch (error) {
            start.status = 'failed';
            result.errors.push(`${start.label}: ${error.message}`);
        }
    }
    result.ok = result.created || result.added > 0 || !result.errors.length;
    return result;
}

async function runImport() {
    const chosen = state.plan.filter(item => item.include && !item.result?.ok);
    for (const item of chosen) {
        item.result = await importItem(item);
        item.include = false;
        renderPreview();
    }
}

// --- events -------------------------------------------------------------------------

document.getElementById('file').addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    if (file) await readFile(await file.text());
});

const drop = document.getElementById('drop');
drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('border-blue-400', 'bg-blue-50'); });
drop.addEventListener('dragleave', () => drop.classList.remove('border-blue-400', 'bg-blue-50'));
drop.addEventListener('drop', async (e) => {
    e.preventDefault();
    drop.classList.remove('border-blue-400', 'bg-blue-50');
    const file = e.dataTransfer.files?.[0];
    if (file) await readFile(await file.text());
});

document.getElementById('paste-read').addEventListener('click', () => readFile(document.getElementById('paste').value));

document.getElementById('rows').addEventListener('change', (e) => {
    const index = e.target.dataset.include;
    if (index === undefined) return;
    state.plan[Number(index)].include = e.target.checked;
    renderSummary();
});

document.getElementById('select-all').addEventListener('change', (e) => {
    document.querySelectorAll('[data-include]:not(:disabled)').forEach(box => {
        box.checked = e.target.checked;
        state.plan[Number(box.dataset.include)].include = e.target.checked;
    });
    renderSummary();
});

document.getElementById('import-btn').addEventListener('click', (e) => {
    withButtonFeedback(e.currentTarget, runImport).catch(error => showFileError(error.message));
});
