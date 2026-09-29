// A start ID such as 1-52-1 (competitor, discipline, start number) as an
// EAN-13 code for barcodes on target labels, and back when one is scanned.
// Layout: 2 | competitor (5 digits) | discipline (3) | start number (3) | check
// digit. The leading 2 keeps it in the GS1 range for in-house numbers, so it
// cannot be mistaken for a product code.

const PREFIX = '2';
const START_ID = /^(\d{1,5})-(\d{1,3})-(\d{1,3})$/;
const EAN13 = /^2(\d{5})(\d{3})(\d{3})(\d)$/;

function checkDigit(first12) {
    const sum = [...first12].reduce((total, digit, i) => total + Number(digit) * (i % 2 ? 3 : 1), 0);
    return String((10 - (sum % 10)) % 10);
}

/** The EAN-13 code of a start ID, or '' when it does not fit (e.g. old unseparated IDs). */
export function startIdToEan13(startId) {
    const match = START_ID.exec(String(startId ?? '').trim());
    if (!match) return '';
    const [, competitor, discipline, startNumber] = match;
    const first12 = PREFIX + competitor.padStart(5, '0') + discipline.padStart(3, '0') + startNumber.padStart(3, '0');
    return first12 + checkDigit(first12);
}

/** The start ID of a scanned EAN-13 code, or null when it is not one of ours or the check digit is wrong. */
export function ean13ToStartId(code) {
    const text = String(code ?? '').trim();
    const match = EAN13.exec(text);
    if (!match || checkDigit(text.slice(0, 12)) !== match[4]) return null;
    return [match[1], match[2], match[3]].map(Number).join('-');
}
