// index.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

// Navbar scroll effect
window.addEventListener('scroll', () => {
  document.getElementById('mainNav').classList.toggle('scrolled', window.scrollY > 10);
});

// Mobile menu
function toggleMenu() {
  const menu = document.getElementById('mobileMenu');
  const btn = document.getElementById('hamburger');
  menu.classList.toggle('open');
  btn.classList.toggle('open');
}

// Contact modal
function openContact() { document.getElementById('contactModal').classList.add('open'); }
function closeContact() { document.getElementById('contactModal').classList.remove('open'); }

// Toast notifications
function showToast(msg, type = 'info') {
  const c = document.getElementById('toastContainer');
  const t = document.createElement('div');
  t.className = `toast ${type}`;
  t.innerHTML = `<i class="fa fa-${type==='success'?'check-circle':type==='error'?'exclamation-circle':'info-circle'}" style="color:var(${type==='success'?'green':type==='error'?'red':'blue'})"></i> ${escHtml(msg)}`;
  c.appendChild(t);
  setTimeout(() => { t.style.opacity='0'; t.style.transform='translateX(20px)'; t.style.transition='.3s'; setTimeout(()=>t.remove(),300); }, 3500);
}

// Animated counter
function animateCounter(el, target) {
  if (target === 0) { el.textContent = '0'; return; }
  const duration = 1800;
  const step = target / (duration / 16);
  let current = 0;
  const timer = setInterval(() => {
    current = Math.min(current + step, target);
    el.textContent = Math.floor(current).toLocaleString();
    if (current >= target) clearInterval(timer);
  }, 16);
}

// Load real stats
async function loadPublicStats() {
  const els = document.querySelectorAll('[data-stat]');
  try {
    const res = await fetch(`${API}/dashboard/public-stats`);
    if (!res.ok) throw new Error('Failed');
    const data = (await res.json()).data || {};
    els.forEach(el => {
      const value = data[el.dataset.stat] ?? 0;
      const observer = new IntersectionObserver((entries) => {
        entries.forEach(e => {
          if (e.isIntersecting) { animateCounter(el, value); observer.unobserve(el); }
        });
      }, { threshold: 0.5 });
      observer.observe(el);
    });
  } catch (e) {
    els.forEach(el => { el.textContent = '0'; });
  }
}

document.getElementById('year').textContent = new Date().getFullYear();
    loadPublicStats();

// Load approved stories from localStorage and inject into grid
(function() {
  try {
    const stories = JSON.parse(localStorage.getItem('nidaa_stories') || '[]');
    const approved = stories.filter(s => s.status === 'approved');
    if (!approved.length) return;
    const grid = document.getElementById('storiesGrid');
    approved.forEach(s => {
      const initials = (s.authorName || 'U').split(' ').map(n => n[0]).join('').slice(0,2).toUpperCase();
      const card = document.createElement('div');
      card.className = 'testimonial';
      // Stories are beneficiary-written and shown to every visitor: escape everything.
      card.innerHTML =
        (s.photo ? '<img class="story-img" src="' + escHtml(s.photo) + '" alt="story photo" loading="lazy"/>' : '') +
        '<span class="story-badge">✨ Real Story</span>' +
        '<div class="t-stars">★★★★★</div>' +
        '<p class="t-text">“' + escHtml((s.text || '').slice(0, 220)) + ((s.text||'').length > 220 ? '…' : '') + '”</p>' +
        '<div class="t-author"><div class="t-avatar">' + escHtml(initials) + '</div><div>' +
        '<div class="t-name">' + escHtml(s.authorName || 'Community Member') + '</div>' +
        '<div class="t-role">Beneficiary' + (s.location ? ' — ' + escHtml(s.location) : '') + '</div>' +
        '</div></div>';
      grid.appendChild(card);
    });
  } catch(e) {}
})();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('hamburger', 'click', () => { toggleMenu(); });
wireEvent('toggleMenuBtn', 'click', () => { toggleMenu(); });
wireEvent('toggleMenuBtn2', 'click', () => { toggleMenu(); });
wireEvent('toggleMenuBtn3', 'click', () => { toggleMenu(); });
wireEvent('openContactBtn', 'click', (event) => { event.preventDefault(); openContact(); });
wireEvent('contactModal', 'click', function (event) { if(event.target===this)closeContact(); });
wireEvent('closeContactBtn', 'click', () => { closeContact(); });
