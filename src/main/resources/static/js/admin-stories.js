// admin-stories.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if(!localStorage.getItem('token'))window.location.href='login.html';

const user   =JSON.parse(localStorage.getItem('user')||'{}');
buildSidebar('admin-stories.html');
const role   =(user.role||'').toLowerCase();
if(role!=='admin')window.location.href='login.html';
document.getElementById('topbarName').textContent=(user.fullName||'Admin').split(' ')[0];

const STORIES_KEY='nidaa_stories';
let currentFilter='all';

function getStories(){try{return JSON.parse(localStorage.getItem(STORIES_KEY)||'[]');}catch{return[];}}
function saveStories(s){localStorage.setItem(STORIES_KEY,JSON.stringify(s));}

function setFilter(f,btn){
    currentFilter=f;
    document.querySelectorAll('.ftab').forEach(b=>b.classList.remove('active'));
    btn.classList.add('active');
    render();
}


function approve(id){
    const stories=getStories();
    const s=stories.find(x=>x.id===id);
    if(!s)return;
    const note=document.getElementById('note-'+id);
    s.status='approved';
    s.adminNote=note?note.value.trim():'';
    s.reviewedAt=Date.now();
    saveStories(stories);
    showToast('✅ Story approved and published on main page!');
    render();
}

function reject(id){
    const stories=getStories();
    const s=stories.find(x=>x.id===id);
    if(!s)return;
    const note=document.getElementById('note-'+id);
    s.status='rejected';
    s.adminNote=note?note.value.trim():'';
    s.reviewedAt=Date.now();
    saveStories(stories);
    showToast('Story rejected.','#fef2f2','#b91c1c');
    render();
}

function deleteStory(id){
    if(!confirm('Permanently delete this story?'))return;
    const stories=getStories().filter(x=>x.id!==id);
    saveStories(stories);
    render();
}

function render(){
    const all=getStories();
    const pending=all.filter(s=>s.status==='pending');
    const approved=all.filter(s=>s.status==='approved');
    const rejected=all.filter(s=>s.status==='rejected');

    // Stats
    document.getElementById('statsRow').innerHTML=
        `<div class="stat-pill"><i class="fa fa-inbox" style="color:var(--blue)"></i> ${all.length} Total</div>`+
        `<div class="stat-pill"><i class="fa fa-clock" style="color:#f59e0b"></i> ${pending.length} Pending</div>`+
        `<div class="stat-pill"><i class="fa fa-circle-check" style="color:var(--green)"></i> ${approved.length} Approved</div>`+
        `<div class="stat-pill"><i class="fa fa-circle-xmark" style="color:#b91c1c"></i> ${rejected.length} Rejected</div>`;
    document.getElementById('pendingCount').textContent=pending.length;

    const filtered=currentFilter==='all'?all:all.filter(s=>s.status===currentFilter);
    const grid=document.getElementById('storiesGrid');

    if(!filtered.length){
        grid.innerHTML=`<div class="empty"><i class="fa fa-newspaper"></i><p>No stories here yet.</p></div>`;
        return;
    }

    grid.innerHTML=filtered.map(s=>{
        const cardClass=s.status==='approved'?'approved-card':s.status==='rejected'?'rejected-card':'pending-card';
        const tagClass=s.status==='approved'?'tag-approved':s.status==='rejected'?'tag-rejected':'tag-pending';
        const tagLabel=s.status==='approved'?'✅ Approved':s.status==='rejected'?'❌ Rejected':'⏳ Pending';
        const initials=(s.authorName||'U').split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
        return`<div class="story-card ${cardClass}" id="card-${s.id}">
            ${s.photo?`<img class="story-photo" src="${escHtml(s.photo)}" alt="story photo" loading="lazy"/>`:''}
            <div class="story-body">
                <div class="story-card-header">
                    <div class="story-title">${escHtml(s.title||'Untitled')}</div>
                    <span class="story-status-tag ${tagClass}">${tagLabel}</span>
                </div>
                <div class="story-meta">
                    <span><i class="fa fa-user"></i> ${escHtml(s.authorName||'Unknown')}</span>
                    ${s.location?`<span><i class="fa fa-location-dot"></i> ${escHtml(s.location)}</span>`:''}
                    <span><i class="fa fa-clock"></i> ${timeAgo(s.timestamp)}</span>
                </div>
                <div class="story-text">${escHtml(s.text).slice(0,320)}${s.text&&s.text.length>320?'…':''}</div>
                ${s.adminNote?`<div class="existing-note"><b>Admin note:</b> ${escHtml(s.adminNote)}</div>`:''}
                <div class="admin-note-wrap">
                    <input class="admin-note-inp" id="note-${s.id}" type="text" placeholder="Add a note to the author (optional)..." value="${escHtml(s.adminNote||'')}"/>
                </div>
                <div class="story-actions" style="margin-top:12px">
                    ${s.status!=='approved'?`<button class="btn-approve" data-action="approve" data-id="${s.id}"><i class="fa fa-check"></i> Approve & Publish</button>`:''}
                    ${s.status!=='rejected'?`<button class="btn-reject" data-action="reject" data-id="${s.id}"><i class="fa fa-xmark"></i> Reject</button>`:''}
                    <button class="btn-delete" data-action="delete" data-id="${s.id}" title="Delete permanently" aria-label="Delete permanently"><i class="fa fa-trash"></i></button>
                </div>
            </div>
        </div>`;
    }).join('');
}

function showToast(msg,bg,col){
    const t=document.createElement('div');
    t.style.cssText=`position:fixed;top:20px;right:24px;background:${bg||'#f0fdf4'};border:1px solid #bbf7d0;color:${col||'#047857'};padding:13px 20px;border-radius:12px;font-weight:600;z-index:9999;box-shadow:0 4px 20px rgba(0,0,0,.1);font-family:Inter,sans-serif;font-size:14px`;
    t.textContent=msg;document.body.appendChild(t);
    setTimeout(()=>t.remove(),3000);
}

render();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('setFilterAll', 'click', function () { setFilter('all',this); });
wireEvent('setFilterPending', 'click', function () { setFilter('pending',this); });
wireEvent('setFilterApproved', 'click', function () { setFilter('approved',this); });
wireEvent('setFilterRejected', 'click', function () { setFilter('rejected',this); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
wireEvent('storiesGrid', 'click', (event) => {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    if (btn.dataset.action === 'approve') approve(id);
    else if (btn.dataset.action === 'reject') reject(id);
    else if (btn.dataset.action === 'delete') deleteStory(id);
});
