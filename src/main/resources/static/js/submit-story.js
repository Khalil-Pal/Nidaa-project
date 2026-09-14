// submit-story.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if(!localStorage.getItem('token'))window.location.href='login.html';

const user      =JSON.parse(localStorage.getItem('user')||'{}');
const fullName  =user.fullName||user.name||'User';
const firstName =fullName.split(' ')[0];
const userRole  =(user.role||'beneficiary').toLowerCase();
const roleDisplay=userRole.charAt(0).toUpperCase()+userRole.slice(1);

if(userRole!=='beneficiary')window.location.href='dashboard.html';

document.getElementById('topbarName').textContent=firstName;
document.getElementById('topbarRole').textContent=roleDisplay;

const STORIES_KEY='nidaa_stories';
let pendingPhoto=null;

function getStories(){try{return JSON.parse(localStorage.getItem(STORIES_KEY)||'[]');}catch{return[];}}
function saveStories(s){localStorage.setItem(STORIES_KEY,JSON.stringify(s));}

function updateCount(){
    const len=document.getElementById('storyText').value.length;
    const el=document.getElementById('charBar');
    el.textContent=len+' / 1200';
    el.className='char-bar'+(len>1000?' over':len>800?' warn':'');
}

function handlePhoto(input){
    const file=input.files[0];
    if(!file)return;
    if(file.size>3*1024*1024){alert('Photo must be under 3MB.');return;}
    const reader=new FileReader();
    reader.onload=e=>{
        pendingPhoto=e.target.result;
        document.getElementById('photoImg').src=pendingPhoto;
        document.getElementById('photoPreview').style.display='inline-block';
        document.getElementById('photoZone').style.display='none';
    };
    reader.readAsDataURL(file);
}
function removePhoto(e){
    e.preventDefault();
    pendingPhoto=null;
    document.getElementById('photoPreview').style.display='none';
    document.getElementById('photoZone').style.display='';
    document.getElementById('photoInput').value='';
}

function submitStory(){
    const title=document.getElementById('storyTitle').value.trim();
    const text=document.getElementById('storyText').value.trim();
    const location=document.getElementById('storyLocation').value.trim();
    if(!title){alert('Please enter a story title.');return;}
    if(text.length<50){alert('Please write at least 50 characters for your story.');return;}

    const btn=document.getElementById('submitBtn');
    btn.disabled=true;btn.innerHTML='<i class="fa fa-spinner fa-spin"></i> Submitting...';

    const stories=getStories();
    stories.unshift({
        id:Date.now(),
        authorId:user.id||user.email,
        authorName:fullName,
        title,text,location,
        photo:pendingPhoto||null,
        status:'pending',
        timestamp:Date.now(),
        adminNote:''
    });
    saveStories(stories);

    setTimeout(()=>{
        document.getElementById('pendingBanner').classList.add('show');
        document.getElementById('storyTitle').value='';
        document.getElementById('storyText').value='';
        document.getElementById('storyLocation').value='';
        updateCount();
        removePhoto({preventDefault:()=>{}});
        btn.disabled=false;
        btn.innerHTML='<i class="fa fa-paper-plane"></i> Submit Another Story';
        loadMyStories();
        document.getElementById('pendingBanner').scrollIntoView({behavior:'smooth',block:'start'});
    },600);
}

function loadMyStories(){
    const all=getStories();
    const mine=all.filter(s=>s.authorId===(user.id||user.email)||s.authorName===fullName);
    const section=document.getElementById('myStoriesSection');
    const list=document.getElementById('myStoriesList');
    if(!mine.length){section.style.display='none';return;}
    section.style.display='block';
    list.innerHTML=mine.map(s=>{
        const statusClass=s.status==='approved'?'s-approved':s.status==='rejected'?'s-rejected':'s-pending';
        const statusLabel=s.status==='approved'?'✅ Approved & Published':s.status==='rejected'?'❌ Not Approved':'⏳ Pending Review';
        return`<div class="my-story-card">
            <div class="my-story-header">
                <span style="font-size:15px;font-weight:700">${escHtml(s.title||'Untitled')}</span>
                <span class="story-status ${statusClass}">${statusLabel}</span>
            </div>
            ${s.photo?`<img src="${escHtml(s.photo)}" style="width:100%;max-height:140px;object-fit:cover;border-radius:10px;margin-bottom:10px"/>`:''}
            <p style="font-size:13px;color:var(--muted);line-height:1.6">${escHtml((s.text||'').slice(0,200))}${(s.text||'').length>200?'…':''}</p>
            ${s.adminNote?`<div style="margin-top:10px;font-size:12px;background:#f8fafc;border-radius:8px;padding:8px 12px;color:var(--muted)"><b>Admin note:</b> ${escHtml(s.adminNote)}</div>`:''}
        </div>`;
    }).join('');
}


buildSidebar();
loadMyStories();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('storyText', 'input', () => { updateCount(); });
wireEvent('photoInput', 'change', function () { handlePhoto(this); });
wireEvent('removePhotoBtn', 'click', (event) => { removePhoto(event); });
wireEvent('submitBtn', 'click', () => { submitStory(); });
