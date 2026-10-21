// The rules for a starter row, as the server checks them
// (RegistrationRules.java), for immediate feedback while typing. The server
// is the one that counts; its answer replaces these marks after saving.
// Pure functions, no page access.

import { t } from './i18n.js';

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/** Whether the row holds nothing at all yet (an empty line of the sheet); anything typed makes it a row to check and send. */
export function isEmpty(row) {
    return !row.name.trim() && row.sel.size === 0 && !String(row.year ?? '').trim() && !row.club.trim()
        && !row.country && !row.gender && !row.email.trim();
}

/**
 * The errors of a row. ctx: { countries: Set of names, defaultCountry,
 * collectEmail, offered: Set of "rankingId:entry", thisYear }.
 */
export function rowErrors(row, ctx) {
    if (isEmpty(row)) return [];
    const errors = [];
    const name = row.name.trim();
    if (!name) errors.push(t('rule.nameRequired'));
    else if (name.length > 100) errors.push(t('rule.nameTooLong'));
    const country = row.country || ctx.defaultCountry;
    if (!country) errors.push(t('rule.countryRequired'));
    else if (!ctx.countries.has(country)) errors.push(t('rule.countryUnknown', { country }));
    if (row.year !== '' && row.year != null) {
        const year = Number(row.year);
        if (!Number.isInteger(year) || year < 1900 || year > ctx.thisYear) errors.push(t('rule.badYear', { max: ctx.thisYear }));
    }
    if (ctx.collectEmail && row.email && !EMAIL.test(row.email.trim())) errors.push(t('rule.badEmail'));
    for (const key of row.sel) {
        if (!ctx.offered.has(key)) errors.push(t('rule.badChoice'));
    }
    return errors;
}

/** The warnings of a row: no ranking ticked, the name twice in the list. */
export function rowWarnings(row, rows) {
    if (isEmpty(row)) return [];
    const warnings = [];
    if (row.sel.size === 0) warnings.push(t('rule.noRankings'));
    const name = row.name.trim().toLowerCase();
    if (name && rows.some(o => o !== row && o.name.trim().toLowerCase() === name)) warnings.push(t('rule.duplicate'));
    return warnings;
}
