// profile.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if(!localStorage.getItem('token')) window.location.href='login.html';

const user       = JSON.parse(localStorage.getItem('user')||'{}');
const fullName   = user.fullName||user.name||'User';
const firstName  = fullName.split(' ')[0];
const userRole   = (user.role||'volunteer').toLowerCase();
const roleDisplay= userRole.charAt(0).toUpperCase()+userRole.slice(1);
const initials   = fullName.split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
const isProvider = userRole==='volunteer'||userRole==='organization';
const PROF_KEY   = 'nidaa_profile_'+( user.id||user.email||'me');
const POSTS_KEY  = 'nidaa_community_posts';

const PROVIDER_SERVICE_META={
    MEDICAL:{label:'Medical',icon:'fa-kit-medical',tone:'medical',order:0},
    FOOD:{label:'Food',icon:'fa-bowl-food',tone:'food',order:1},
    WATER:{label:'Water',icon:'fa-droplet',tone:'water',order:2},
    SHELTER:{label:'Shelter',icon:'fa-house',tone:'shelter',order:3},
    CLOTHING:{label:'Clothing',icon:'fa-shirt',tone:'clothing',order:4}
};

const roleColors={volunteer:'#047857',psychologist:'#7c3aed',admin:'#b91c1c',beneficiary:'#1d4ed8',organization:'#ea580c'};
const roleBg    ={volunteer:'#f0fdf4',psychologist:'#faf5ff',admin:'#fef2f2',beneficiary:'#eff6ff',organization:'#fff7ed'};

document.getElementById('topbarName').textContent=firstName;
document.getElementById('topbarRole').textContent=roleDisplay;

function loadProfileData(){
    const saved=JSON.parse(localStorage.getItem(PROF_KEY)||'{}');
    const roleCol=roleColors[userRole]||'#1d4ed8';

    // Avatar
    const avatarEl=document.getElementById('profileAvatar');
    const savedPhoto=localStorage.getItem('nidaa_avatar_'+(user.id||user.email));
    if(savedPhoto){
        avatarEl.innerHTML=`<img src="${savedPhoto}" alt="avatar"/>`;
    } else {
        avatarEl.style.background=`linear-gradient(135deg,${roleCol},#047857)`;
        avatarEl.textContent=initials;
    }

    document.getElementById('profileName').textContent=fullName;
    document.getElementById('profileEmail').textContent=user.email||'';
    document.getElementById('profileSince').textContent=saved.since||new Date().getFullYear();
    document.getElementById('topbarRole').textContent=roleDisplay;

    // Bio / location / avail
    document.getElementById('bioView').textContent=saved.bio||'No bio added yet.';
    document.getElementById('bioEdit').value=saved.bio||'';
    document.getElementById('locationView').textContent=saved.location||'Not set';
    document.getElementById('locationEdit').value=saved.location||'';
    document.getElementById('availView').textContent=saved.availability||'Not specified';
    document.getElementById('availEdit').value=saved.availability||'';

    // Skills
    renderSkillsView(saved.skills||[]);

    // Stats from community posts
    const allPosts=JSON.parse(localStorage.getItem(POSTS_KEY)||'[]');
    const myPosts=allPosts.filter(p=>p.author===fullName||(p.authorId&&p.authorId===(user.id||user.email)));
    const totalLikes=myPosts.reduce((s,p)=>s+(p.likes||0),0);


     // Fetch real people-helped count from completed help requests




    document.getElementById('profileRating').textContent=saved.rating?`⭐ ${saved.rating} rating`:'New member';
    renderMyPosts(myPosts);
    renderActivity(myPosts,saved);
}

function renderSkillsView(skills){
    const wrap=document.getElementById('skillsView');
    if(!skills.length){wrap.innerHTML='<span style="color:var(--muted);font-size:14px">No skills added yet.</span>';return;}
    wrap.innerHTML=skills.map(s=>`<span class="skill-tag">${s}</span>`).join('');
}

let editSkills=[];
function renderSkillsEditTags(){
    const wrap=document.getElementById('skillsEditTags');
    wrap.innerHTML=editSkills.map((s,i)=>`<span class="skill-tag">${escHtml(s)}<button type="button" class="rm" data-action="remove-skill" data-index="${i}" aria-label="Remove ${escHtml(s)}">✕</button></span>`).join('');
}
function removeSkill(i){editSkills.splice(i,1);renderSkillsEditTags();}
function addSkill(){
    const inp=document.getElementById('skillInput');
    const val=inp.value.trim();
    if(!val)return;
    if(!editSkills.includes(val))editSkills.push(val);
    inp.value='';
    renderSkillsEditTags();
}

const ALL_BADGES=[
    {icon:'💬',name:'Communicator',desc:'Posted 10+ times',check:p=>p.length>=10},
    {icon:'🤝',name:'Helper',desc:'Engaged with community',check:(p,s)=>(s.helped||0)>=5},
    {icon:'⭐',name:'Expert',desc:'Earned 450+ contribution pts',check:(p,s)=>(s.points||0)>=450},
    {icon:'🔥',name:'Active',desc:'10-day streak',check:(p,s)=>(s.streak||1)>=10},
    {icon:'🎓',name:'Mentor',desc:'Mentored 3+ people',check:(p,s)=>(s.mentees||0)>=3},
    {icon:'🏆',name:'Top Rated',desc:'Rating 4.8+',check:(p,s)=>parseFloat(s.rating||0)>=4.8},
    {icon:'✅',name:'Verified',desc:'Verified platform member',check:()=>true},
    {icon:'👑',name:'Community Leader',desc:'50+ posts created',check:p=>p.length>=50},
];


const typeLabel={UPDATE:'📢 Update',QUESTION:'❓ Question',GRATITUDE:'🙏 Gratitude',INFO:'ℹ️ Info',
    'success-stories':'🌟 Success Story','tips-advice':'💡 Tips','events':'📅 Event','resources':'📚 Resource'};
const typeTagStyle={UPDATE:'background:#eff6ff;color:#1d4ed8',QUESTION:'background:#fefce8;color:#a16207',
    GRATITUDE:'background:#f0fdf4;color:#047857',INFO:'background:#faf5ff;color:#7c3aed',
    'success-stories':'background:#f0fdf4;color:#047857','tips-advice':'background:#faf5ff;color:#7c3aed',
    'events':'background:#ecfeff;color:#0891b2','resources':'background:#fdf2f8;color:#9d174d'};
function renderMyPosts(myPosts){
    const el=document.getElementById('myPostsList');
    if(!myPosts.length){el.innerHTML='<div class="empty-state"><i class="fa fa-newspaper"></i><p>No posts yet. Share something in the Community!</p></div>';return;}
    el.innerHTML=myPosts.map((p,i)=>{
        const type=p.type||p.category||'UPDATE';
        const style=typeTagStyle[type]||'background:#eff6ff;color:#1d4ed8';
        const label=typeLabel[type]||type;
        return`<div class="post-item">
            <div class="post-item-header">
                <span class="post-type-tag" style="${style}">${label}</span>
                <span style="font-size:12px;color:var(--muted)">${timeAgo(p.timestamp)}</span>
            </div>
            <div class="post-text-preview">${escHtml(p.text).slice(0,200)}${p.text.length>200?'…':''}</div>
            <div class="post-item-footer">
                <span><i class="fa fa-heart" style="color:#ef4444"></i> ${p.likes||0}</span>
                <span><i class="fa fa-comment" style="color:var(--blue)"></i> ${(p.comments||[]).length||0}</span>
                <span style="margin-left:auto"><button class="btn-del" data-action="delete-post" data-index="${i}"><i class="fa fa-trash"></i> Delete</button></span>
            </div>
        </div>`;
    }).join('');
}
function deletePost(localIdx){
    if(!confirm('Delete this post?'))return;
    const all=JSON.parse(localStorage.getItem(POSTS_KEY)||'[]');
    const myPosts=all.filter(p=>p.author===fullName);
    const post=myPosts[localIdx];
    const realIdx=all.indexOf(post);
    if(realIdx>-1)all.splice(realIdx,1);
    localStorage.setItem(POSTS_KEY,JSON.stringify(all));
    loadProfileData();
}

function renderActivity(myPosts,saved){
    const el=document.getElementById('activityList');
    const items=[];
    myPosts.slice(0,5).forEach(p=>items.push({icon:'<i class="fa fa-pen" style="color:var(--blue)"></i>',bg:'#eff6ff',text:`Posted a ${(p.type||'update').toLowerCase()}`,time:p.timestamp}));
    if(saved.joined)items.push({icon:'<i class="fa fa-star" style="color:#f59e0b"></i>',bg:'#fefce8',text:'Joined the Nidaa community',time:saved.joinedTs||Date.now()});
    items.sort((a,b)=>b.time-a.time);
    if(!items.length){el.innerHTML='<div class="empty-state"><i class="fa fa-clock-rotate-left"></i><p>No activity yet.</p></div>';return;}
    el.innerHTML=items.map(it=>`<div class="activity-item">
        <div class="act-icon" style="background:${it.bg}">${it.icon}</div>
        <div><div class="act-text">${escHtml(it.text)}</div><div class="act-time">${timeAgo(it.time)}</div></div>
    </div>`).join('');
}


async function readProfileApiData(response){
    let payload={};
    try{payload=await response.json();}catch(ignored){}
    if(!response.ok)throw new Error(payload.message||`Request failed (${response.status})`);
    return payload.data;
}

async function loadProviderSummary(){
    if(!isProvider)return;
    const card=document.getElementById('providerSummaryCard');
    const list=document.getElementById('providerResourceSummary');
    card.hidden=false;

    try{
        const resourceRequest=apiFetch(`${API}/provider-resources/me`,{headers:authHeader()}).then(readProfileApiData);
        const occupationRequest=userRole==='volunteer'
            ?apiFetch(`${API}/volunteers/me/occupation`,{headers:authHeader()}).then(readProfileApiData)
            :Promise.resolve(null);
        const [resources,occupation]=await Promise.all([resourceRequest,occupationRequest]);

        if(userRole==='volunteer'){
            document.getElementById('occupationSummaryRow').hidden=false;
            document.getElementById('occupationSummaryValue').textContent=occupation?.occupation||'Not specified';
        }

        const rows=(Array.isArray(resources)?resources:[])
            .filter(resource=>PROVIDER_SERVICE_META[String(resource.helpType||'').toUpperCase()])
            .sort((a,b)=>{
                const aMeta=PROVIDER_SERVICE_META[String(a.helpType).toUpperCase()];
                const bMeta=PROVIDER_SERVICE_META[String(b.helpType).toUpperCase()];
                return aMeta.order-bMeta.order;
            });

        if(!rows.length){
            list.innerHTML='<div class="provider-summary-empty">No services added yet. <a href="settings.html#my-services">Manage services</a></div>';
            return;
        }

        list.innerHTML=rows.map(resource=>{
            const meta=PROVIDER_SERVICE_META[String(resource.helpType).toUpperCase()];
            const capacity=resource.capacityMode==='NUMERIC'
                ?`${Number(resource.capacityAmount||0).toLocaleString()} available`
                :escHtml(resource.capacityLabel||'Available');
            return `<div class="provider-resource-item">
                <div class="provider-resource-name">
                    <span class="provider-resource-icon ${meta.tone}"><i class="fa ${meta.icon}"></i></span>
                    ${meta.label}
                </div>
                <div class="provider-resource-capacity">${capacity}</div>
            </div>`;
        }).join('');
    }catch(error){
        list.innerHTML=`<div class="provider-summary-empty">${escHtml(error.message||'Could not load services.')}</div>`;
    }
}

// Edit mode
let isEditing=false;
function toggleEditMode(){
    isEditing=!isEditing;
    const views=['bioView','locationView','availView','skillsView'];
    const edits=['bioEdit','locationEdit','availEdit'];
    if(isEditing){
        document.getElementById('editBtnLabel').textContent='Cancel Edit';
        views.forEach(id=>document.getElementById(id).style.display='none');
        edits.forEach(id=>document.getElementById(id).style.display='');
        document.getElementById('skillsView').style.display='none';
        document.getElementById('skillsEditWrap').style.display='';
        const saved=JSON.parse(localStorage.getItem(PROF_KEY)||'{}');
        editSkills=[...(saved.skills||[])];
        renderSkillsEditTags();
        document.getElementById('saveBar').style.display='flex';
        document.getElementById('editHint').textContent='— editing';
    } else {
        cancelEdit();
    }
}
function cancelEdit(){
    isEditing=false;
    document.getElementById('editBtnLabel').textContent='Edit Profile';
    ['bioView','locationView','availView','skillsView'].forEach(id=>document.getElementById(id).style.display='');
    ['bioEdit','locationEdit','availEdit'].forEach(id=>document.getElementById(id).style.display='none');
    document.getElementById('skillsEditWrap').style.display='none';
    document.getElementById('saveBar').style.display='none';
    document.getElementById('editHint').textContent='';
    loadProfileData();
}
function saveProfile(){
    const saved=JSON.parse(localStorage.getItem(PROF_KEY)||'{}');
    saved.bio=document.getElementById('bioEdit').value.trim();
    saved.location=document.getElementById('locationEdit').value.trim();
    saved.availability=document.getElementById('availEdit').value.trim();
    saved.skills=editSkills;
    saved.joined=true;
    saved.joinedTs=saved.joinedTs||Date.now();
    if(!saved.since)saved.since=new Date().getFullYear();
    localStorage.setItem(PROF_KEY,JSON.stringify(saved));
    isEditing=false;
    document.getElementById('editBtnLabel').textContent='Edit Profile';
    ['bioView','locationView','availView','skillsView'].forEach(id=>document.getElementById(id).style.display='');
    ['bioEdit','locationEdit','availEdit'].forEach(id=>document.getElementById(id).style.display='none');
    document.getElementById('skillsEditWrap').style.display='none';
    document.getElementById('saveBar').style.display='none';
    document.getElementById('editHint').textContent='';
    loadProfileData();
    showToast('✅ Profile saved!');
}

// Avatar upload
function handleAvatarUpload(input){
    const file=input.files[0];
    if(!file)return;
    if(file.size>2*1024*1024){showToast('⚠️ Image must be under 2MB','#fef2f2','#b91c1c');return;}
    const allowed=['image/jpeg','image/png','image/gif'];
    if(!allowed.includes(file.type)){showToast('⚠️ Only JPG, PNG, GIF allowed','#fef2f2','#b91c1c');return;}
    const reader=new FileReader();
    reader.onload=e=>{
        const b64=e.target.result;
        localStorage.setItem('nidaa_avatar_'+(user.id||user.email),b64);
        document.getElementById('profileAvatar').innerHTML=`<img src="${b64}" alt="avatar"/>`;
        showToast('✅ Photo updated!');
    };
    reader.readAsDataURL(file);
}

// Settings
function openSettings(){document.getElementById('settingsOverlay').classList.add('open');}
function closeSettings(){document.getElementById('settingsOverlay').classList.remove('open');}
function saveSettings(){closeSettings();showToast('✅ Settings saved!');}

function switchTab(name,btn){
    document.querySelectorAll('.tab-pane').forEach(p=>p.classList.remove('active'));
    document.querySelectorAll('.tab-btn').forEach(b=>b.classList.remove('active'));
    document.getElementById('tab-'+name).classList.add('active');
    btn.classList.add('active');
}

function showToast(msg,bg,col){
    const t=document.createElement('div');
    t.style.cssText=`position:fixed;top:20px;right:24px;background:${bg||'#f0fdf4'};border:1px solid #bbf7d0;color:${col||'#047857'};padding:13px 20px;border-radius:12px;font-weight:600;z-index:9999;box-shadow:0 4px 20px rgba(0,0,0,.1);font-family:Inter,sans-serif;font-size:14px`;
    t.textContent=msg;
    document.body.appendChild(t);
    setTimeout(()=>t.remove(),2500);
}

// Sidebar
buildSidebar();
loadProfileData();
loadProviderSummary();

// ── Beneficiary role UI customization ──────────────────────────
function setupBeneficiaryUI() {
        if (userRole === 'beneficiary') {
        // Hide non-relevant tabs; show Stories tab instead
        ['tabBtnPosts','tabBtnActivity'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = 'none';
        });
        const storiesBtn = document.getElementById('tabBtnStories');
        if (storiesBtn) storiesBtn.style.display = '';

        // Hide Bio, Availability, Skills rows — keep only Location
        ['rowBio','rowAvailability','rowSkills'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = 'none';
        });

        // Load beneficiary's submitted stories into the Stories tab
        renderProfileStories();

    } else {
        // Non-beneficiary: hide the Stories tab button (not relevant for them)
        const storiesBtn = document.getElementById('tabBtnStories');
        if (storiesBtn) storiesBtn.style.display = 'none';

    }
}

function renderProfileStories() {
    const el = document.getElementById('profileStoriesList');
    if (!el) return;
    let stories = [];
    try { stories = JSON.parse(localStorage.getItem('nidaa_stories') || '[]'); } catch {}
    const mine = stories.filter(s => s.authorId === (user.id || user.email) || s.authorName === fullName);
    if (!mine.length) {
    el.innerHTML = '<div class="empty-state"><i class="fa fa-pen-to-square"></i><p>You haven\'t submitted any stories yet. Click "Write My Story" above to get started!</p></div>';        return;
    }
    el.innerHTML = mine.map(s => {
        const sc = s.status === 'approved' ? '#047857' : s.status === 'rejected' ? '#b91c1c' : '#a16207';
        const sb = s.status === 'approved' ? '#f0fdf4' : s.status === 'rejected' ? '#fef2f2' : '#fefce8';
        const sl = s.status === 'approved' ? '✅ Approved & Published' : s.status === 'rejected' ? '❌ Not Approved' : '⏳ Pending Review';
        return `<div style="border:1px solid var(--border);border-radius:12px;padding:18px;margin-bottom:14px">
            <div style="display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:8px;flex-wrap:wrap">
                <span style="font-size:15px;font-weight:700">${escHtml(s.title || 'Untitled')}</span>
                <span style="font-size:11px;font-weight:700;padding:3px 12px;border-radius:100px;background:${sb};color:${sc}">${sl}</span>
            </div>
            ${s.photo ? `<img src="${escHtml(s.photo)}" style="width:100%;max-height:130px;object-fit:cover;border-radius:9px;margin-bottom:10px"/>` : ''}
            <p style="font-size:13px;color:var(--muted);line-height:1.6">${escHtml((s.text||'').slice(0,200))}${(s.text||'').length > 200 ? '…' : ''}</p>
            ${s.adminNote ? `<div style="margin-top:8px;font-size:12px;background:#f8fafc;border-radius:8px;padding:8px 12px;color:var(--muted)"><b>Admin note:</b> ${escHtml(s.adminNote)}</div>` : ''}
        </div>`;
    }).join('');
}

setupBeneficiaryUI();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wire('avatarPicker', () => { document.getElementById('avatarInput').click(); });
wireEvent('avatarInput', 'change', function () { handleAvatarUpload(this); });
wireEvent('toggleEditModeBtn', 'click', () => { toggleEditMode(); });
wireEvent('openSettingsBtn', 'click', () => { openSettings(); });
wireEvent('switchTabAbout', 'click', function () { switchTab('about',this); });
wireEvent('tabBtnPosts', 'click', function () { switchTab('posts',this); });
wireEvent('tabBtnActivity', 'click', function () { switchTab('activity',this); });
wireEvent('tabBtnStories', 'click', function () { switchTab('stories',this); });
wireEvent('skillInput', 'keydown', (event) => { if(event.key==='Enter')addSkill(); });
wireEvent('addSkillBtn', 'click', () => { addSkill(); });
wireEvent('control', 'mouseover', function () { this.style.background='#1e40af'; });
wireEvent('control', 'mouseout', function () { this.style.background='var(--blue)'; });
wireEvent('cancelEditBtn', 'click', () => { cancelEdit(); });
wireEvent('saveProfileBtn', 'click', () => { saveProfile(); });
wireEvent('settingsOverlay', 'click', function (event) { if(event.target===this)closeSettings(); });
wireEvent('closeSettingsBtn', 'click', () => { closeSettings(); });
wireEvent('togglePublic', 'click', function () { this.classList.toggle('on'); });
wireEvent('toggleEmail', 'click', function () { this.classList.toggle('on'); });
wireEvent('togglePosts', 'click', function () { this.classList.toggle('on'); });
wireEvent('toggleActivity', 'click', function () { this.classList.toggle('on'); });
wireEvent('saveSettingsBtn', 'click', () => { saveSettings(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
wireEvent('skillsEditTags', 'click', (event) => {
    const btn = event.target.closest('button[data-action="remove-skill"]');
    if (btn) removeSkill(Number(btn.dataset.index));
});
wireEvent('myPostsList', 'click', (event) => {
    const btn = event.target.closest('button[data-action="delete-post"]');
    if (btn) deletePost(Number(btn.dataset.index));
});
