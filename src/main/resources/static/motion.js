// Progressive enhancement: content stays visible if scripts/observers are unavailable.
(() => {
  if (!window.matchMedia || !window.IntersectionObserver) return;
  const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
  const notes = [...document.querySelectorAll('[data-reveal]')];
  const shown = new WeakSet();
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting || shown.has(entry.target)) continue;
      shown.add(entry.target);
      observer.unobserve(entry.target);
      if (!preference.matches) entry.target.classList.add('reveal-once');
    }
  }, { threshold: .15 });
  const update = () => {
    if (preference.matches) {
      observer.disconnect();
      for (const note of notes) note.classList.remove('reveal-once');
    } else {
      for (const note of notes) if (!shown.has(note)) observer.observe(note);
    }
  };
  if (preference.addEventListener) preference.addEventListener('change', update);
  for (const note of notes) note.addEventListener('focusin', () => {
    note.classList.remove('reveal-once');
    shown.add(note);
    observer.unobserve(note);
  });
  update();
})();
