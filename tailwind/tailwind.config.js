/** Tailwind v3 (same major as the former cdn.tailwindcss.com Play CDN). */
module.exports = {
  content: ['../static/**/*.html', '../static/**/*.js'],
  // Classes built at runtime that the content scan cannot see
  // (static/ranking/rankingCard.js: `text-${align}`).
  safelist: ['text-left', 'text-center', 'text-right'],
};
