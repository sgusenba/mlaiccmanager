// Frontend and backend build dates shown in the sidebar

import { API_BASE } from './config.js';

// static/build-info.json is written by the release workflow; it is absent
// in a local checkout, which then shows "dev"
async function fetchBuildTime(url) {
    try {
        const response = await fetch(url, { cache: 'no-store' });
        if (!response.ok) return null;
        const data = await response.json();
        return data?.buildTime || null;
    } catch {
        return null;
    }
}

function formatBuildTime(isoString) {
    if (!isoString) return 'dev';
    const date = new Date(isoString);
    return isNaN(date) ? isoString : date.toLocaleString();
}

export async function loadBuildInfo() {
    const [frontendTime, backendTime] = await Promise.all([
        fetchBuildTime('/build-info.json'),
        fetchBuildTime(`${API_BASE}/build-info`)
    ]);

    const frontendEl = document.getElementById('frontend-build-time');
    const backendEl = document.getElementById('backend-build-time');
    if (frontendEl) {
        frontendEl.textContent = formatBuildTime(frontendTime);
        if (frontendTime) frontendEl.title = frontendTime;
    }
    if (backendEl) {
        backendEl.textContent = formatBuildTime(backendTime);
        if (backendTime) backendEl.title = backendTime;
    }
}
