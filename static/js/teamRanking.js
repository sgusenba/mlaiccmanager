// Team ranking table and helpers, used by the Ranking page (/ranking)

export const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
));

/** "Miquelet (original)": the event alone is ambiguous, it exists once per type. */
export const disciplineDisplayName = (event, type) => (type ? `${event} (${type})` : event);

const RINGS = ['10', '9', '8', '7', '6', '5', '4', '3', '2', '1'];

export const formatScore = (value) => (value === null || value === undefined ? '-' : Number(value).toLocaleString('en', { maximumFractionDigits: 2 }));

export function rankBadge(rank) {
    const icon = rank === 1 ? '🥇' : rank === 2 ? '🥈' : rank === 3 ? '🥉' : rank;
    const color = rank === 1 ? 'bg-yellow-100 text-yellow-800 border-yellow-300'
        : rank === 2 ? 'bg-gray-100 text-gray-800 border-gray-300'
        : rank === 3 ? 'bg-orange-100 text-orange-800 border-orange-300'
        : 'bg-blue-50 text-blue-800 border-blue-200';
    return `<div class="flex items-center justify-center w-8 h-8 rounded-full border-2 ${color}"><span class="font-bold text-sm">${icon}</span></div>`;
}

function memberLine(member) {
    if (member.missing) {
        return `<div class="text-sm text-red-600">${escapeHtml(member.start_id)} · start deleted</div>`;
    }
    return `
        <div class="text-sm flex justify-between gap-4">
            <span><span class="font-mono text-xs text-gray-500">${escapeHtml(member.start_id)}</span> ${escapeHtml(member.name)}</span>
            <span class="${member.has_result ? 'text-gray-900' : 'text-gray-400 italic'}">${member.has_result ? formatScore(member.score) : 'no result'}</span>
        </div>`;
}

/** Card with the ranked teams of one team discipline (response of /api/teams/ranking/{id}). */
export function teamRankingCard(data, { extraClass = '' } = {}) {
    const discipline = data.discipline;
    const header = `
        <div class="px-6 py-4 border-b border-gray-200">
            <h3 class="text-lg font-semibold text-gray-900">
                ${escapeHtml(disciplineDisplayName(discipline.name, discipline.type))}
                <span class="text-sm text-gray-500 ml-2">${escapeHtml(discipline.category)} team</span>
            </h3>
            <p class="text-sm text-gray-500">Based on ${escapeHtml(discipline.based_on || '-')} · ${discipline.team_size} shooters per team</p>
        </div>`;

    if (!data.rankings || data.rankings.length === 0) {
        return `
            <div class="bg-white rounded-lg shadow-md ${extraClass}">
                ${header}
                <div class="p-6 text-center text-gray-500">No teams entered for this discipline yet.</div>
            </div>`;
    }

    return `
        <div class="bg-white rounded-lg shadow-md ${extraClass}">
            ${header}
            <div class="p-6">
                <div class="overflow-x-auto">
                    <table class="min-w-full divide-y divide-gray-200">
                        <thead class="bg-gray-50">
                            <tr>
                                <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Rank</th>
                                <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Team</th>
                                <th class="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Members</th>
                                <th class="px-4 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Total</th>
                                ${RINGS.map(ring => `<th class="px-2 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">${ring}s</th>`).join('')}
                                <th class="px-4 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider" title="Distance of the furthest shot; the lower value wins">Tie-break</th>
                            </tr>
                        </thead>
                        <tbody class="bg-white divide-y divide-gray-200">
                            ${data.rankings.map(team => `
                                <tr class="hover:bg-gray-50 align-top ${team.rank <= 3 ? 'font-bold' : ''}">
                                    <td class="px-4 py-3 whitespace-nowrap">${rankBadge(team.rank)}</td>
                                    <td class="px-4 py-3">
                                        <div class="text-sm font-medium text-gray-900">${escapeHtml(team.name)}</div>
                                        ${team.complete ? '' : '<span class="inline-block mt-1 text-xs font-normal px-2 py-0.5 rounded bg-yellow-100 text-yellow-800">incomplete</span>'}
                                        ${team.notes ? `<div class="text-xs font-normal text-gray-500 mt-1">${escapeHtml(team.notes)}</div>` : ''}
                                    </td>
                                    <td class="px-4 py-3 font-normal min-w-[16rem]">${team.members.map(memberLine).join('') || '<span class="text-sm text-gray-400">-</span>'}</td>
                                    <td class="px-4 py-3 whitespace-nowrap text-center text-sm">${formatScore(team.total)}</td>
                                    ${RINGS.map(ring => `<td class="px-2 py-3 text-center text-sm font-normal">${team.freq_counts[ring] ?? 0}</td>`).join('')}
                                    <td class="px-4 py-3 text-center text-sm font-normal">${team.tie_break ?? '-'}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>`;
}
