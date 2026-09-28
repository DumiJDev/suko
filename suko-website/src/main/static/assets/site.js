// Signature interaction: the home hero "compiles" its .sk station into the
// .jte station. The rails fill like a line being drawn, then the template's
// characters settle split-flap style. Content is fully visible without JS
// and under prefers-reduced-motion; this script only adds motion.
(() => {
  const hero = document.querySelector('[data-pipeline]');
  if (!hero) return;
  const target = hero.querySelector('[data-flap]');
  const replay = hero.querySelector('[data-replay]');
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (reduce || !target) {
    if (replay) replay.hidden = true;
    return;
  }

  const GLYPHS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<>{}$@/=';
  const finalText = target.textContent;
  const PER_CHAR = 14;
  const SETTLE = 280;
  let raf = 0;
  let flapStarted = false;
  let runId = 0;

  function flap() {
    if (flapStarted) return;
    flapStarted = true;
    const chars = [...finalText];
    const start = performance.now();
    cancelAnimationFrame(raf);
    const frame = (now) => {
      const t = now - start;
      let out = '';
      let done = true;
      for (let i = 0; i < chars.length; i++) {
        const c = chars[i];
        if (c === '\n' || c === ' ') { out += c; continue; }
        const local = t - i * PER_CHAR;
        if (local < 0) { out += ' '; done = false; }
        else if (local < SETTLE) { out += GLYPHS[(Math.random() * GLYPHS.length) | 0]; done = false; }
        else { out += c; }
      }
      target.textContent = out;
      if (done) {
        target.textContent = finalText;
        hero.classList.remove('is-running');
        hero.classList.add('is-done');
      } else {
        raf = requestAnimationFrame(frame);
      }
    };
    raf = requestAnimationFrame(frame);
  }

  function run() {
    cancelAnimationFrame(raf);
    flapStarted = false;
    hero.classList.remove('is-done', 'is-running');
    target.textContent = finalText.replace(/[^\n ]/g, ' ');
    void hero.offsetWidth; // restart CSS animations
    hero.classList.add('is-running');
    // Never leave the template blank if the rail animation doesn't fire.
    const id = ++runId;
    setTimeout(() => { if (id === runId) flap(); }, 1400);
  }

  hero.classList.add('js-anim');
  const lastRail = hero.querySelector('[data-rail-last]');
  if (lastRail) lastRail.addEventListener('animationend', flap);
  if (replay) replay.addEventListener('click', run);

  const io = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) {
      io.disconnect();
      run();
    }
  }, { threshold: 0.4 });
  io.observe(hero);
})();
