// Sidebar navigation shared by every page. The menu is grouped by the
// order a competition runs in: set up, register starters, enter results,
// publish the ranking. Each entry links to a page and, for pages with
// several sections, to the section's #hash.

import { loadBuildInfo } from './buildInfo.js';

// `default` marks the section a page shows when the URL has no known #hash
const GROUPS = [
    {
        label: 'Management',
        items: [
            { label: 'Disciplines', path: '/dmgmt/' },
            { label: 'Ranges & Relays', path: '/rmgmt/', hash: 'settings' },
            { label: 'Meet Days', path: '/rmgmt/', hash: 'schedule', default: true }
        ]
    },
    {
        label: 'Starters',
        items: [
            { label: 'Competitors', path: '/', hash: 'competitors', default: true },
            { label: 'Starts', path: '/', hash: 'starts' },
            { label: 'Teams', path: '/tmgmt/' },
            { label: 'Lane Assignment', path: '/rmgmt/', hash: 'assignment' },
            { label: 'Starter Overview', path: '/rmgmt/', hash: 'overview' }
        ]
    },
    {
        label: 'Results',
        items: [
            { label: 'Enter Results', path: '/', hash: 'results' }
        ]
    },
    {
        label: 'Rankings',
        items: [
            { label: 'Ranking', path: '/ranking/' }
        ]
    }
];

const ITEMS = GROUPS.flatMap(group => group.items);

const href = item => item.path + (item.hash ? `#${item.hash}` : '');

function currentPath() {
    const path = location.pathname.replace(/index\.html$/, '');
    return path.endsWith('/') ? path : `${path}/`;
}

// The entry for the current page and #hash; an unknown or missing hash
// means the page's default section, as the pages themselves handle it
function activeItem() {
    const onPage = ITEMS.filter(item => item.path === currentPath());
    const hash = location.hash.substring(1);
    return onPage.find(item => item.hash === hash)
        || onPage.find(item => item.default)
        || onPage[0]
        || null;
}

function render() {
    const nav = document.createElement('div');
    nav.className = 'no-print';
    nav.innerHTML = `
        <div class="app-topbar">
            <button type="button" class="app-menu-btn" aria-label="Open menu" aria-controls="app-sidebar" aria-expanded="false">
                <svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16"></path>
                </svg>
            </button>
            <span class="font-bold">MLAICC Manager</span>
            <span class="app-topbar-current text-blue-100 text-sm truncate"></span>
        </div>
        <div class="app-backdrop"></div>
        <aside id="app-sidebar" class="app-sidebar">
            <a href="/" class="flex items-center px-4 h-16 border-b border-blue-500 shrink-0">
                <svg class="h-7 w-7 mr-2 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                </svg>
                <span class="text-lg font-bold">MLAICC Manager</span>
            </a>
            <nav class="flex-1 overflow-y-auto py-3">
                ${GROUPS.map(group => `
                    <div class="mb-4">
                        <div class="px-4 mb-1 text-xs font-semibold uppercase tracking-wider text-blue-200">${group.label}</div>
                        ${group.items.map(item => `
                            <a href="${href(item)}" class="app-nav-item" data-nav-index="${ITEMS.indexOf(item)}">${item.label}</a>
                        `).join('')}
                    </div>
                `).join('')}
            </nav>
            <div class="px-4 py-3 border-t border-blue-500 text-xs leading-tight text-blue-100">
                <div>Frontend: <span id="frontend-build-time">–</span></div>
                <div>Backend: <span id="backend-build-time">–</span></div>
            </div>
        </aside>`;
    document.body.prepend(nav);

    const menuButton = nav.querySelector('.app-menu-btn');
    const setOpen = open => {
        document.body.classList.toggle('app-menu-open', open);
        menuButton.setAttribute('aria-expanded', String(open));
    };
    menuButton.addEventListener('click', () => setOpen(!document.body.classList.contains('app-menu-open')));
    nav.querySelector('.app-backdrop').addEventListener('click', () => setOpen(false));
    nav.querySelectorAll('.app-nav-item').forEach(link => link.addEventListener('click', () => setOpen(false)));
}

function highlight() {
    const active = activeItem();
    document.querySelectorAll('.app-nav-item').forEach(link => {
        const isActive = ITEMS[Number(link.dataset.navIndex)] === active;
        link.classList.toggle('active', isActive);
        if (isActive) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });

    const label = active ? active.label : '';
    document.querySelector('.app-topbar-current').textContent = label;
    // Pages without their own section headings show the entry's name
    const title = document.getElementById('page-title');
    if (title) title.textContent = label;
    document.title = label ? `${label} · MLAICC Manager` : 'MLAICC Manager';
}

render();
highlight();
window.addEventListener('hashchange', highlight);
loadBuildInfo();
