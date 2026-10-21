// QR codes (ISO/IEC 18004) built in the browser, without any library: byte
// mode, error correction level M, versions 1 to 10 (up to 213 bytes), which
// is plenty for start IDs such as 1-52-1. Follows the reference algorithm of
// Project Nayuki's QR Code generator.

// Error correction level M, by version (index 0 unused)
const ECC_PER_BLOCK = [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
const BLOCKS = [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
const FORMAT_ECL_M = 0;
const MAX_VERSION = 10;

const bit = (value, i) => ((value >>> i) & 1) !== 0;

/** Modules of a version without the function patterns, in bits. */
function rawDataModules(version) {
    let result = (16 * version + 128) * version + 64;
    if (version >= 2) {
        const align = Math.floor(version / 7) + 2;
        result -= (25 * align - 10) * align - 55;
        if (version >= 7) result -= 36;
    }
    return result;
}

const dataCodewords = (version) => Math.floor(rawDataModules(version) / 8) - ECC_PER_BLOCK[version] * BLOCKS[version];

// --- Reed-Solomon over GF(2^8), polynomial 0x11D ---------------------------

function gfMultiply(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
        z = (z << 1) ^ ((z >>> 7) * 0x11D);
        z ^= ((y >>> i) & 1) * x;
    }
    return z;
}

function rsDivisor(degree) {
    const result = new Array(degree).fill(0);
    result[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
        for (let j = 0; j < result.length; j++) {
            result[j] = gfMultiply(result[j], root);
            if (j + 1 < result.length) result[j] ^= result[j + 1];
        }
        root = gfMultiply(root, 0x02);
    }
    return result;
}

function rsRemainder(data, divisor) {
    const result = divisor.map(() => 0);
    for (const byte of data) {
        const factor = byte ^ result.shift();
        result.push(0);
        divisor.forEach((coefficient, i) => { result[i] ^= gfMultiply(coefficient, factor); });
    }
    return result;
}

/** The data codewords split into blocks, each with its error correction, interleaved. */
function withErrorCorrection(data, version) {
    const blockCount = BLOCKS[version];
    const eccLength = ECC_PER_BLOCK[version];
    const raw = Math.floor(rawDataModules(version) / 8);
    const shortBlocks = blockCount - raw % blockCount;
    const shortLength = Math.floor(raw / blockCount);
    const divisor = rsDivisor(eccLength);

    const blocks = [];
    for (let i = 0, k = 0; i < blockCount; i++) {
        const block = data.slice(k, k + shortLength - eccLength + (i < shortBlocks ? 0 : 1));
        k += block.length;
        const ecc = rsRemainder(block, divisor);
        if (i < shortBlocks) block.push(0);
        blocks.push(block.concat(ecc));
    }

    const result = [];
    for (let i = 0; i < blocks[0].length; i++) {
        blocks.forEach((block, j) => {
            // the padding byte of the short blocks is not part of the code
            if (i !== shortLength - eccLength || j >= shortBlocks) result.push(block[i]);
        });
    }
    return result;
}

// --- data ------------------------------------------------------------------

function dataBytes(bytes, version) {
    const bits = [];
    const append = (value, length) => { for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1); };
    append(0b0100, 4); // byte mode
    append(bytes.length, version <= 9 ? 8 : 16);
    bytes.forEach(byte => append(byte, 8));

    const capacity = dataCodewords(version) * 8;
    append(0, Math.min(4, capacity - bits.length)); // terminator
    append(0, (8 - bits.length % 8) % 8);
    for (let pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) append(pad, 8);

    const result = [];
    for (let i = 0; i < bits.length; i += 8) result.push(bits.slice(i, i + 8).reduce((byte, b) => (byte << 1) | b, 0));
    return result;
}

// --- matrix ----------------------------------------------------------------

function alignmentPositions(version, size) {
    if (version === 1) return [];
    const count = Math.floor(version / 7) + 2;
    const step = Math.ceil((version * 4 + 4) / (count * 2 - 2)) * 2;
    const result = [6];
    for (let position = size - 7; result.length < count; position -= step) result.splice(1, 0, position);
    return result;
}

const MASKS = [
    (x, y) => (x + y) % 2 === 0,
    (x, y) => y % 2 === 0,
    (x) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
    (x, y) => x * y % 2 + x * y % 3 === 0,
    (x, y) => (x * y % 2 + x * y % 3) % 2 === 0,
    (x, y) => ((x + y) % 2 + x * y % 3) % 2 === 0
];

class Matrix {
    constructor(version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
        this.isFunction = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
    }

    setFunction(x, y, dark) {
        this.modules[y][x] = dark;
        this.isFunction[y][x] = true;
    }

    drawFunctionPatterns() {
        const { size } = this;
        for (let i = 0; i < size; i++) {
            this.setFunction(6, i, i % 2 === 0);
            this.setFunction(i, 6, i % 2 === 0);
        }
        for (const [cx, cy] of [[3, 3], [size - 4, 3], [3, size - 4]]) {
            for (let dy = -4; dy <= 4; dy++) {
                for (let dx = -4; dx <= 4; dx++) {
                    const distance = Math.max(Math.abs(dx), Math.abs(dy));
                    const x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) this.setFunction(x, y, distance !== 2 && distance !== 4);
                }
            }
        }
        const positions = alignmentPositions(this.version, size);
        const last = positions.length - 1;
        positions.forEach((cx, i) => positions.forEach((cy, j) => {
            // not where the finder patterns are
            if ((i === 0 && j === 0) || (i === 0 && j === last) || (i === last && j === 0)) return;
            for (let dy = -2; dy <= 2; dy++) {
                for (let dx = -2; dx <= 2; dx++) this.setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
            }
        }));
        this.drawFormatBits(0); // reserves the area; drawn again once the mask is chosen
        this.drawVersionBits();
    }

    drawFormatBits(mask) {
        const { size } = this;
        const data = (FORMAT_ECL_M << 3) | mask;
        let remainder = data;
        for (let i = 0; i < 10; i++) remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        const bits = ((data << 10) | remainder) ^ 0x5412;

        for (let i = 0; i <= 5; i++) this.setFunction(8, i, bit(bits, i));
        this.setFunction(8, 7, bit(bits, 6));
        this.setFunction(8, 8, bit(bits, 7));
        this.setFunction(7, 8, bit(bits, 8));
        for (let i = 9; i < 15; i++) this.setFunction(14 - i, 8, bit(bits, i));

        for (let i = 0; i < 8; i++) this.setFunction(size - 1 - i, 8, bit(bits, i));
        for (let i = 8; i < 15; i++) this.setFunction(8, size - 15 + i, bit(bits, i));
        this.setFunction(8, size - 8, true); // the dark module
    }

    drawVersionBits() {
        if (this.version < 7) return;
        let remainder = this.version;
        for (let i = 0; i < 12; i++) remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
        const bits = (this.version << 12) | remainder;
        for (let i = 0; i < 18; i++) {
            const a = this.size - 11 + i % 3, b = Math.floor(i / 3);
            this.setFunction(a, b, bit(bits, i));
            this.setFunction(b, a, bit(bits, i));
        }
    }

    /** Places the codewords in the zigzag order, two columns at a time from the right. */
    drawCodewords(codewords) {
        const { size } = this;
        let i = 0;
        for (let right = size - 1; right >= 1; right -= 2) {
            if (right === 6) right = 5; // skips the vertical timing pattern
            for (let vertical = 0; vertical < size; vertical++) {
                for (let j = 0; j < 2; j++) {
                    const x = right - j;
                    const upward = ((right + 1) & 2) === 0;
                    const y = upward ? size - 1 - vertical : vertical;
                    if (!this.isFunction[y][x] && i < codewords.length * 8) {
                        this.modules[y][x] = bit(codewords[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    applyMask(mask) {
        const test = MASKS[mask];
        for (let y = 0; y < this.size; y++) {
            for (let x = 0; x < this.size; x++) {
                if (!this.isFunction[y][x] && test(x, y)) this.modules[y][x] = !this.modules[y][x];
            }
        }
    }

    /** The penalty score of the standard: runs, 2x2 blocks, finder-like patterns and dark balance. */
    penalty() {
        const { size, modules } = this;
        let score = 0;
        const lines = [];
        for (let i = 0; i < size; i++) {
            lines.push(modules[i]);
            lines.push(modules.map(row => row[i]));
        }
        const finderLike = [[1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1]];
        for (const line of lines) {
            for (let start = 0; start < size;) {
                let end = start;
                while (end < size && line[end] === line[start]) end++;
                if (end - start >= 5) score += 3 + (end - start - 5);
                start = end;
            }
            // outside the symbol counts as light
            const padded = [0, 0, 0, 0, ...line.map(Number), 0, 0, 0, 0];
            for (let i = 0; i + 11 <= padded.length; i++) {
                if (finderLike.some(pattern => pattern.every((value, k) => padded[i + k] === value))) score += 40;
            }
        }
        for (let y = 0; y + 1 < size; y++) {
            for (let x = 0; x + 1 < size; x++) {
                const dark = modules[y][x];
                if (dark === modules[y][x + 1] && dark === modules[y + 1][x] && dark === modules[y + 1][x + 1]) score += 3;
            }
        }
        const total = size * size;
        const darkCount = modules.reduce((sum, row) => sum + row.filter(Boolean).length, 0);
        score += (Math.ceil(Math.abs(darkCount * 20 - total * 10) / total) - 1) * 10;
        return score;
    }
}

/**
 * The QR code of a text: { size, modules, version, mask }, modules[y][x] true
 * where dark, without the quiet zone. mask: 0-7 to force one (for checking
 * against other encoders); by default the one with the lowest penalty.
 */
export function qrCode(text, { mask: forcedMask } = {}) {
    const bytes = [...new TextEncoder().encode(String(text ?? ''))];
    let version = 1;
    while (version <= MAX_VERSION && 4 + (version <= 9 ? 8 : 16) + bytes.length * 8 > dataCodewords(version) * 8) version++;
    if (version > MAX_VERSION) throw new Error(`Too long for a QR code: ${text}`);

    const matrix = new Matrix(version);
    matrix.drawFunctionPatterns();
    matrix.drawCodewords(withErrorCorrection(dataBytes(bytes, version), version));

    let mask = forcedMask;
    if (mask === undefined) {
        let best = Infinity;
        for (let candidate = 0; candidate < 8; candidate++) {
            matrix.applyMask(candidate);
            matrix.drawFormatBits(candidate);
            const score = matrix.penalty();
            if (score < best) { best = score; mask = candidate; }
            matrix.applyMask(candidate); // XOR again undoes it
        }
    }
    matrix.applyMask(mask);
    matrix.drawFormatBits(mask);
    return { size: matrix.size, modules: matrix.modules, version, mask };
}

/** SVG path of the dark modules, one unit per module. */
function darkPath({ size, modules }) {
    const parts = [];
    for (let y = 0; y < size; y++) {
        for (let x = 0; x < size;) {
            if (!modules[y][x]) { x++; continue; }
            let end = x;
            while (end < size && modules[y][end]) end++;
            parts.push(`M${x} ${y}h${end - x}v1h${x - end}z`);
            x = end;
        }
    }
    return parts.join('');
}

/** The QR code of a text as inline SVG, exactly sizeMm wide and high (quiet zone not included). */
export function qrSvg(text, sizeMm) {
    const code = qrCode(text);
    return `<svg xmlns="http://www.w3.org/2000/svg" width="${sizeMm}mm" height="${sizeMm}mm" viewBox="0 0 ${code.size} ${code.size}" `
        + `shape-rendering="crispEdges" role="img" aria-label="QR code ${String(text).replace(/[&<>"]/g, '')}">`
        + `<path fill="#000" d="${darkPath(code)}"/></svg>`;
}

/** The QR code of a text as PNG bytes, pixelsPerModule per module, without the quiet zone. */
export async function qrPng(text, pixelsPerModule = 16) {
    const code = qrCode(text);
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = code.size * pixelsPerModule;
    const context = canvas.getContext('2d');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#000';
    code.modules.forEach((row, y) => row.forEach((dark, x) => {
        if (dark) context.fillRect(x * pixelsPerModule, y * pixelsPerModule, pixelsPerModule, pixelsPerModule);
    }));
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    return new Uint8Array(await blob.arrayBuffer());
}
