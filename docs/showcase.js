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

(() => {
  'use strict';
  const buttons=[...document.querySelectorAll('[data-tour-step]')];
  const panels=[...document.querySelectorAll('.tour-panel')];
  if (!buttons.length || buttons.length !== panels.length) return;
  let index=0;
  const prev=document.querySelector('[data-tour-prev]'), next=document.querySelector('[data-tour-next]');
  const status=document.getElementById('tour-status');
  function show(i) {
    index=Math.max(0,Math.min(panels.length-1,i));
    buttons.forEach((button,j)=>button.setAttribute('aria-pressed',String(j===index)));
    panels.forEach((panel,j)=>panel.hidden=j!==index);
    prev.disabled=index===0; next.disabled=index===panels.length-1;
    status.textContent=`Step ${index+1} of ${panels.length}`;
  }
  buttons.forEach((button,i)=>{button.addEventListener('click',()=>show(i));button.addEventListener('keydown',event=>{if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;event.preventDefault();const at=event.key==='Home'?0:event.key==='End'?buttons.length-1:(i+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length;show(at);buttons[at].focus();});});
  prev.addEventListener('click',()=>show(index-1));next.addEventListener('click',()=>show(index+1));show(0);
})();
