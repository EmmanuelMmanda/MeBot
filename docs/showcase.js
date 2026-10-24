/* Progressive enhancement: downloads and images also work without JavaScript. */
(() => {
  'use strict';
  const filters = [...document.querySelectorAll('[data-screen-filter]')];
  const cards = [...document.querySelectorAll('#screen-gallery [data-screen-group]')];
  const count = document.getElementById('screen-count');
  filters.forEach(button => button.addEventListener('click', () => {
    const group = button.dataset.screenFilter;
    filters.forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    let visible = 0;
    cards.forEach(card => { card.hidden = group !== 'all' && card.dataset.screenGroup !== group; if (!card.hidden) visible++; });
    if (count) count.textContent = `${visible} ${visible === 1 ? 'screen' : 'screens'}`;
  }));
})();
