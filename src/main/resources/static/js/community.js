// community.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if(!localStorage.getItem('token'))window.location.href='login.html';

const user       =JSON.parse(localStorage.getItem('user')||'{}');
const fullName   =user.fullName||user.name||'User';
const firstName  =fullName.split(' ')[0];
const userRole   =(user.role||'volunteer').toLowerCase();
const roleDisplay=userRole.charAt(0).toUpperCase()+userRole.slice(1);
const initials   =fullName.split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();

if(userRole==='beneficiary')window.location.href='dashboard.html';

document.getElementById('topbarName').textContent=firstName;
document.getElementById('topbarRole').textContent=roleDisplay;
(function(){
    const avatarKey='nidaa_avatar_'+(user.id||user.email||'');
    const savedPhoto=localStorage.getItem(avatarKey);
    const av=document.getElementById('newPostAvatar');
    if(savedPhoto){
        av.style.background='none';
        av.innerHTML=`<img src="${savedPhoto}" style="width:100%;height:100%;object-fit:cover;border-radius:50%"/>`;
    } else {
        av.textContent=initials;
    }
})();

const ENGAGEMENT_KEY='nidaa_community_engagement';
const PAGE_SIZE=20;
const roleColors={volunteer:'#047857',psychologist:'#7c3aed',admin:'#b91c1c',beneficiary:'#1d4ed8',organization:'#c2410c'};
const roleBg={volunteer:'#f0fdf4',psychologist:'#faf5ff',admin:'#fef2f2',organization:'#fff7ed',beneficiary:'#eff6ff'};
const catMeta={
    'ALL':             {label:'All',          color:'#1d4ed8',bg:'#eff6ff'},
    'UPDATE':          {label:'📢 Update',    color:'#1d4ed8',bg:'#eff6ff'},
    'SUCCESS_STORIES': {label:'🌟 Success',   color:'#047857',bg:'#f0fdf4'},
    'QUESTION':        {label:'❓ Question',  color:'#a16207',bg:'#fefce8'},
    'TIPS_ADVICE':     {label:'💡 Tips',      color:'#7c3aed',bg:'#faf5ff'},
    'EVENTS':          {label:'📅 Event',     color:'#0891b2',bg:'#ecfeff'},
    'RESOURCES':       {label:'📚 Resource',  color:'#9d174d',bg:'#fdf2f8'},
    'GRATITUDE':       {label:'🙏 Gratitude', color:'#047857',bg:'#f0fdf4'},
    'INFO':            {label:'ℹ️ Info',      color:'#7c3aed',bg:'#faf5ff'},
};

let currentCat='ALL';
let posts=[];
let nextPage=0;
let feedLastPage=false;
let feedLoading=false;

function readEngagementStore(){
    try{return JSON.parse(localStorage.getItem(ENGAGEMENT_KEY)||'{}');}catch{return{};}
}
function getEngagement(messageId){
    const saved=readEngagementStore()[String(messageId)]||{};
    return{
        likes:Number(saved.likes)||0,
        likedBy:Array.isArray(saved.likedBy)?saved.likedBy:[],
        comments:Array.isArray(saved.comments)?saved.comments:[]
    };
}
function saveEngagement(messageId,value){
    const store=readEngagementStore();
    store[String(messageId)]=value;
    localStorage.setItem(ENGAGEMENT_KEY,JSON.stringify(store));
}
function removeEngagement(messageId){
    const store=readEngagementStore();
    delete store[String(messageId)];
    localStorage.setItem(ENGAGEMENT_KEY,JSON.stringify(store));
}

function setCategory(cat,btn){
    currentCat=cat;
    document.querySelectorAll('.cat-tab').forEach(b=>b.classList.remove('active'));
    btn.classList.add('active');
    renderPosts();
}

function updateCharCount(){
    const len=document.getElementById('postText').value.length;
    const el=document.getElementById('charCount');
    el.textContent=len+' / 1000';
    el.className='char-count'+(len>900?' over':len>750?' warn':'');
}


function normalizePost(raw){
    const author=raw.authorName||'Unknown user';
    const parsedTime=Date.parse(raw.sentAt);
    return{
        id:Number(raw.id),
        authorId:raw.authorId,
        author,
        initials:author.split(' ').filter(Boolean).map(n=>n[0]).join('').slice(0,2).toUpperCase()||'?',
        role:String(raw.authorRole||'').toLowerCase(),
        text:raw.content||'',
        category:raw.communityCategory||'UPDATE',
        timestamp:Number.isNaN(parsedTime)?Date.now():parsedTime
    };
}

async function fetchJson(url,options={}){
    const response=await apiFetch(url,{...options,headers:{...authHeader(),...(options.headers||{})}});
    let payload={};
    try{payload=await response.json();}catch{}
    if(!response.ok)throw new Error(payload.message||'The community request failed.');
    return payload.data;
}

async function loadPosts(reset=false){
    if(feedLoading||(!reset&&feedLastPage))return;
    feedLoading=true;
    if(reset){
        posts=[];
        nextPage=0;
        feedLastPage=false;
        document.getElementById('postsList').innerHTML='<div class="empty-feed"><i class="fa fa-spinner fa-spin"></i><p>Loading community messages...</p></div>';
    }
    updateFeedActions();
    try{
        const page=await fetchJson(`${API}/community/messages?page=${nextPage}&size=${PAGE_SIZE}`);
        const incoming=(page.content||[]).map(normalizePost);
        const knownIds=new Set(posts.map(post=>post.id));
        posts=posts.concat(incoming.filter(post=>!knownIds.has(post.id)));
        feedLastPage=page.last===true||incoming.length<PAGE_SIZE;
        nextPage+=1;
        renderPosts();
    }catch(error){
        if(reset){
            document.getElementById('postsList').innerHTML=`<div class="empty-feed"><i class="fa fa-triangle-exclamation"></i><p>${escHtml(error.message)}</p><button class="btn-load-more" data-action="retry">Try again</button></div>`;
        }else{
            showToast(error.message,'#fef2f2','#b91c1c');
        }
    }finally{
        feedLoading=false;
        updateFeedActions();
    }
}

function loadMorePosts(){loadPosts(false);}

function updateFeedActions(){
    const actions=document.getElementById('feedActions');
    const button=document.getElementById('loadMoreBtn');
    actions.hidden=feedLastPage||posts.length===0;
    button.disabled=feedLoading;
    button.textContent=feedLoading?'Loading...':'Load more';
}

function renderPosts(){
    const search=(document.getElementById('searchInput').value||'').toLowerCase().trim();
    const roleF=document.getElementById('roleFilter').value;
    const dateF=document.getElementById('dateFilter').value;
    const now=Date.now();
    const weekMs=7*24*60*60*1000,monthMs=30*24*60*60*1000;

    const filtered=posts.filter(p=>{
        const cat=p.category||'UPDATE';
        if(currentCat!=='ALL'&&cat!==currentCat)return false;
        if(roleF!=='ALL'&&(p.role||'').toLowerCase()!==roleF)return false;
        if(dateF==='week'&&now-p.timestamp>weekMs)return false;
        if(dateF==='month'&&now-p.timestamp>monthMs)return false;
        if(search){
            const hay=(p.text+' '+(p.author||'')+' '+(cat)).toLowerCase();
            if(!hay.includes(search))return false;
        }
        return true;
    });

    const list=document.getElementById('postsList');
    if(!filtered.length){
        list.innerHTML=`<div class="empty-feed"><i class="fa fa-comments"></i><p>${search?'No posts match your search.':currentCat==='ALL'?'No posts yet. Be the first!':'No posts in this category yet.'}</p></div>`;
        renderTrending(posts);
        return;
    }

    const myKey=String(user.id||user.email||'');
    list.innerHTML=filtered.map(p=>{
        const cat=p.category||'UPDATE';
        const cm=catMeta[cat]||catMeta['UPDATE'];
        const engagement=getEngagement(p.id);
        const liked=engagement.likedBy.includes(myKey);
        const roleCol=roleColors[(p.role||'').toLowerCase()]||'#1d4ed8';
        const photo=localStorage.getItem('nidaa_avatar_'+(p.authorId||''));
        const avatarInner=photo?`<img src="${photo}" alt=""/>`:(p.initials||'?');
        const avatarStyle=photo?'':`background:${roleCol}`;
        const comments=engagement.comments;
        const roleLabel=p.role?p.role.charAt(0).toUpperCase()+p.role.slice(1):'Member';
        return`<div class="post-card" id="pc-${p.id}">
            <div class="post-meta">
                <a class="post-author-av" style="${avatarStyle}" href="profile.html" aria-label="Open profile">${avatarInner}</a>
                <div class="post-author-info">
                    <a class="post-author-name" href="profile.html">${escHtml(p.author)}</a>
                    <div class="post-author-role">${roleLabel}</div>
                </div>
                <span class="post-cat-tag" style="background:${cm.bg};color:${cm.color}">${cm.label}</span>
                <span class="post-time">${timeAgo(p.timestamp)}</span>
            </div>
            <div class="post-text">${escHtml(p.text)}</div>
            <div class="post-engagement">
                <span class="eng-num"><i class="fa fa-heart" style="color:#ef4444"></i> ${engagement.likes} likes</span>
                <span class="eng-num"><i class="fa fa-comment" style="color:var(--blue)"></i> ${comments.length} comments</span>
            </div>
            <div class="post-actions">
                <button type="button" class="post-btn${liked?' liked':''}" data-action="like" data-id="${p.id}" aria-pressed="${liked?'true':'false'}">
                    <i class="fa fa-heart"></i> ${liked?'Liked':'Like'}
                </button>
                <button type="button" class="post-btn" data-action="comments" data-id="${p.id}" aria-controls="cw-${p.id}">
                    <i class="fa fa-comment"></i> Comment${comments.length?` (${comments.length})`:''}
                </button>
                <a href="profile.html" class="post-btn" style="text-decoration:none"><i class="fa fa-user"></i> Profile</a>
                ${userRole==='admin'?`<button type="button" class="post-btn danger" data-action="moderate" data-id="${p.id}"><i class="fa fa-trash"></i> Moderate</button>`:''}
            </div>
            <div class="comments-wrap" id="cw-${p.id}">
                ${comments.map(c=>`<div class="comment-item">
                    <div class="comment-av" style="background:${roleColors[(c.role||'').toLowerCase()]||'#1d4ed8'}">${escHtml(c.initials||'?')}</div>
                    <div class="comment-body"><div class="comment-author">${escHtml(c.author)}</div><div class="comment-text">${escHtml(c.text)}</div></div>
                </div>`).join('')}
                <div class="comment-input-row">
                    <input class="comment-input" id="ci-${p.id}" type="text" maxlength="500" placeholder="Write a comment..." aria-label="Write a comment" data-comment-for="${p.id}"/>
                    <button type="button" class="comment-submit" data-action="comment" data-id="${p.id}">Send</button>
                </div>
            </div>
            ${userRole==='admin'?`<div class="moderation-box" id="moderation-${p.id}">
                <label class="moderation-label" for="moderation-reason-${p.id}">Deletion justification</label>
                <textarea class="moderation-reason" id="moderation-reason-${p.id}" maxlength="1000" placeholder="Explain why this message must be removed" data-moderation-for="${p.id}"></textarea>
                <div class="moderation-error" id="moderation-error-${p.id}" role="alert"></div>
                <div class="moderation-actions">
                    <button type="button" class="btn-moderation-cancel" data-action="moderation-cancel" data-id="${p.id}">Cancel</button>
                    <button type="button" class="btn-moderation-delete" id="moderation-delete-${p.id}" data-action="moderation-delete" data-id="${p.id}">Delete message</button>
                </div>
            </div>`:''}
        </div>`;
    }).join('');
    renderTrending(posts);
}

function toggleLike(messageId){
    const engagement=getEngagement(messageId);
    const key=String(user.id||user.email||'');
    const index=engagement.likedBy.indexOf(key);
    if(index===-1){engagement.likedBy.push(key);engagement.likes+=1;}
    else{engagement.likedBy.splice(index,1);engagement.likes=Math.max(0,engagement.likes-1);}
    saveEngagement(messageId,engagement);
    renderPosts();
}

function toggleComments(messageId){
    const el=document.getElementById('cw-'+messageId);
    if(el)el.classList.toggle('open');
}

function submitComment(messageId){
    const inp=document.getElementById('ci-'+messageId);
    const text=(inp.value||'').trim();if(!text)return;
    const engagement=getEngagement(messageId);
    engagement.comments.push({author:fullName,initials,role:userRole,text,timestamp:Date.now()});
    saveEngagement(messageId,engagement);
    inp.value='';
    renderPosts();
    const cw=document.getElementById('cw-'+messageId);
    if(cw)cw.classList.add('open');
}

function openModeration(messageId){
    document.querySelectorAll('.moderation-box.open').forEach(box=>box.classList.remove('open'));
    const box=document.getElementById('moderation-'+messageId);
    if(!box)return;
    box.classList.add('open');
    document.getElementById('moderation-reason-'+messageId)?.focus();
}

function closeModeration(messageId){
    document.getElementById('moderation-'+messageId)?.classList.remove('open');
    const error=document.getElementById('moderation-error-'+messageId);
    if(error)error.textContent='';
}

function clearModerationError(messageId){
    const reason=document.getElementById('moderation-reason-'+messageId);
    const error=document.getElementById('moderation-error-'+messageId);
    if(reason&&reason.value.trim()&&error)error.textContent='';
}

async function confirmModerationDelete(messageId){
    const reasonInput=document.getElementById('moderation-reason-'+messageId);
    const error=document.getElementById('moderation-error-'+messageId);
    const button=document.getElementById('moderation-delete-'+messageId);
    const reason=(reasonInput?.value||'').trim();
    if(!reason){
        if(error)error.textContent='Enter a justification before deleting this message.';
        reasonInput?.focus();
        return;
    }
    button.disabled=true;
    if(error)error.textContent='';
    try{
        await fetchJson(`${API}/community/messages/${messageId}?reason=${encodeURIComponent(reason)}`,{method:'DELETE'});
        posts=posts.filter(post=>post.id!==messageId);
        removeEngagement(messageId);
        renderPosts();
        updateFeedActions();
        showToast('Message deleted and recorded in the moderation audit.');
        loadModerationAudit();
    }catch(requestError){
        if(error)error.textContent=requestError.message;
        if(button)button.disabled=false;
    }
}

async function submitPost(){
    const text=document.getElementById('postText').value.trim();
    if(!text){showToast('Write something first.','#fefce8','#a16207');return;}
    const btn=document.getElementById('postBtn');btn.disabled=true;
    const cat=document.getElementById('postCat').value;
    try{
        const created=await fetchJson(`${API}/community/messages`,{
            method:'POST',
            body:JSON.stringify({content:text,communityCategory:cat})
        });
        posts.unshift(normalizePost(created));
        document.getElementById('postText').value='';
        updateCharCount();
        renderPosts();
        updateFeedActions();
        showToast('Posted successfully.');
    }catch(error){
        showToast(error.message,'#fef2f2','#b91c1c');
    }finally{
        btn.disabled=false;
    }
}

function renderTrending(posts){
    const trending=[...posts].sort((a,b)=>{
        const aEngagement=getEngagement(a.id),bEngagement=getEngagement(b.id);
        return(bEngagement.likes+bEngagement.comments.length)-(aEngagement.likes+aEngagement.comments.length);
    }).slice(0,5);
    const el=document.getElementById('trendingList');
    if(!trending.length){el.innerHTML='<div style="padding:14px 18px;font-size:13px;color:var(--muted)">No posts yet.</div>';return;}
    el.innerHTML=trending.map(p=>{
        const cat=p.category||'UPDATE';
        const cm=catMeta[cat]||catMeta['UPDATE'];
        const engagement=getEngagement(p.id);
        return`<div class="trending-item" role="button" tabindex="0" data-action="scroll-to" data-id="${p.id}">
            <div class="trending-title">${escHtml(p.text).slice(0,70)}${p.text.length>70?'…':''}</div>
            <div class="trending-meta">
                <span style="color:${cm.color}">${cm.label}</span>
                <span><i class="fa fa-heart" style="color:#ef4444"></i> ${engagement.likes}</span>
                <span><i class="fa fa-comment" style="color:var(--blue)"></i> ${engagement.comments.length}</span>
            </div>
        </div>`;
    }).join('');
}

function scrollToPost(messageId){
    currentCat='ALL';
    document.querySelectorAll('.cat-tab').forEach(b=>b.classList.toggle('active',b.dataset.cat==='ALL'));
    document.getElementById('searchInput').value='';
    renderPosts();
    setTimeout(()=>{
        const el=document.getElementById('pc-'+messageId);
        if(el){el.scrollIntoView({behavior:'smooth',block:'center'});el.style.outline='2px solid var(--blue)';setTimeout(()=>el.style.outline='',2000);}
    },100);
}

async function loadModerationAudit(){
    if(userRole!=='admin')return;
    const panel=document.getElementById('moderationAuditPanel');
    const list=document.getElementById('moderationAuditList');
    panel.hidden=false;
    list.innerHTML='<div style="padding:14px 18px;font-size:13px;color:var(--muted)">Loading history...</div>';
    try{
        const page=await fetchJson(`${API}/admin/community/deletions?page=0&size=5`);
        const entries=page.content||[];
        if(!entries.length){
            list.innerHTML='<div style="padding:14px 18px;font-size:13px;color:var(--muted)">No moderated messages yet.</div>';
            return;
        }
        list.innerHTML=entries.map(item=>{
            const deletedAt=item.deletedAt?new Date(item.deletedAt).toLocaleString():'Unknown time';
            const snapshot=String(item.originalContent||'');
            return`<div class="audit-item">
                <div class="audit-meta">${escHtml(item.originalAuthorName)} · ${escHtml(deletedAt)}</div>
                <div class="audit-reason">${escHtml(item.reason)}</div>
                <div class="audit-snapshot">${escHtml(snapshot.slice(0,100))}${snapshot.length>100?'…':''}</div>
                <div class="audit-meta" style="margin-top:5px">Removed by ${escHtml(item.deletedByAdminName)}</div>
            </div>`;
        }).join('');
    }catch(error){
        list.innerHTML=`<div style="padding:14px 18px;font-size:13px;color:#b91c1c">${escHtml(error.message)}</div>`;
    }
}

async function loadMembers(){
    const list=document.getElementById('membersList');
    try{
        let members=[];
        if(userRole==='admin'){
            const res=await apiFetch(API+'/admin/users',{headers:authHeader()});
            if(res.ok){const all=(await res.json()).data||[];members=all.filter(m=>{const r=(m.role||'').toLowerCase();return r==='volunteer'||r==='psychologist'||r==='organization';}).slice(0,6);}
        }
        if(!members.length){
            const res=await apiFetch(API+'/dashboard/stats',{headers:authHeader()});
            if(res.ok){
                const s=(await res.json()).data||{};
                const vc=s.totalVolunteers||s.volunteers||0;
                const pc=s.totalPsychologists||s.psychologists||0;
                const oc=s.totalOrganizations||s.organizations||0;
                if(vc||pc||oc){
                    list.innerHTML=
                        (vc?`<div class="member-card"><div class="member-card-top"><div class="member-av" style="background:#047857">${vc}<div class="online-dot"></div></div><div><div class="member-name">${vc} Volunteer${vc!==1?'s':''}</div><span class="member-role-badge" style="background:#f0fdf4;color:#047857">Volunteer</span></div></div></div>`:'')+
                        (pc?`<div class="member-card"><div class="member-card-top"><div class="member-av" style="background:#7c3aed">${pc}<div class="online-dot"></div></div><div><div class="member-name">${pc} Psychologist${pc!==1?'s':''}</div><span class="member-role-badge" style="background:#faf5ff;color:#7c3aed">Psychologist</span></div></div></div>`:'')+
                        (oc?`<div class="member-card"><div class="member-card-top"><div class="member-av" style="background:#c2410c">${oc}<div class="online-dot"></div></div><div><div class="member-name">${oc} Organization${oc!==1?'s':''}</div><span class="member-role-badge" style="background:#fff7ed;color:#c2410c">Organization</span></div></div></div>`:'');
                    return;
                }
            }
            members=[{fullName,role:userRole,id:user.id,email:user.email}];
        }
        list.innerHTML=members.map(m=>{
            const mName=m.fullName||m.name||'Member';
            const mRole=(m.role||'volunteer').toLowerCase();
            const mInit=mName.split(' ').map(n=>n[0]).join('').slice(0,2).toUpperCase();
            const col=roleColors[mRole]||'#1d4ed8';
            const bg=roleBg[mRole]||'#eff6ff';
            const photo=localStorage.getItem('nidaa_avatar_'+(m.id||m.email||''));
            const saved=JSON.parse(localStorage.getItem('nidaa_profile_'+(m.id||m.email||'me'))||'{}');
            return`<div class="member-card">
                <div class="member-card-top">
                    <div class="member-av" style="${photo?'':'background:'+col}">
                        ${photo?`<img src="${photo}" style="width:100%;height:100%;object-fit:cover;border-radius:50%"/>`:`<span>${mInit}</span>`}
                        <div class="online-dot"></div>
                    </div>
                    <div>
                        <div class="member-name">${escHtml(mName)}</div>
                        <span class="member-role-badge" style="background:${bg};color:${col}">${mRole.charAt(0).toUpperCase()+mRole.slice(1)}</span>
                    </div>
                </div>
                ${saved.bio?`<div style="font-size:12px;color:var(--muted);margin-bottom:8px;line-height:1.4">"${escHtml(saved.bio).slice(0,60)}${saved.bio.length>60?'…':''}"</div>`:''}
                <div class="member-actions">
                    <a class="btn-sm" href="profile.html"><i class="fa fa-user"></i> Profile</a>
                </div>
            </div>`;
        }).join('');
    }catch{
        list.innerHTML='<div style="padding:16px 18px;font-size:13px;color:var(--muted)">Could not load members.</div>';
    }
}

function showToast(msg,bg,col){
    const t=document.createElement('div');
    t.style.cssText=`position:fixed;top:20px;right:24px;background:${bg||'#f0fdf4'};border:1px solid #bbf7d0;color:${col||'#047857'};padding:13px 20px;border-radius:12px;font-weight:600;z-index:9999;box-shadow:0 4px 20px rgba(0,0,0,.1);font-family:Inter,sans-serif;font-size:14px`;
    t.textContent=msg;document.body.appendChild(t);
    setTimeout(()=>t.remove(),2500);
}


buildSidebar();
loadPosts(true);
loadMembers();
loadModerationAudit();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('setCategoryAll', 'click', function () { setCategory('ALL',this); });
wireEvent('setCategorySuccessStories', 'click', function () { setCategory('SUCCESS_STORIES',this); });
wireEvent('setCategoryQuestion', 'click', function () { setCategory('QUESTION',this); });
wireEvent('setCategoryTipsAdvice', 'click', function () { setCategory('TIPS_ADVICE',this); });
wireEvent('setCategoryEvents', 'click', function () { setCategory('EVENTS',this); });
wireEvent('setCategoryResources', 'click', function () { setCategory('RESOURCES',this); });
wireEvent('setCategoryUpdate', 'click', function () { setCategory('UPDATE',this); });
wireEvent('searchInput', 'input', () => { renderPosts(); });
wireEvent('roleFilter', 'change', () => { renderPosts(); });
wireEvent('dateFilter', 'change', () => { renderPosts(); });
wireEvent('postText', 'input', () => { updateCharCount(); });
wireEvent('postBtn', 'click', () => { submitPost(); });
wireEvent('loadMoreBtn', 'click', () => { loadMorePosts(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener per container dispatches them.
wireEvent('postsList', 'click', (event) => {
    const btn = event.target.closest('[data-action]');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    switch (btn.dataset.action) {
        case 'retry':             loadPosts(true); break;
        case 'like':              toggleLike(id); break;
        case 'comments':          toggleComments(id); break;
        case 'moderate':          openModeration(id); break;
        case 'comment':           submitComment(id); break;
        case 'moderation-cancel': closeModeration(id); break;
        case 'moderation-delete': confirmModerationDelete(id); break;
    }
});
wireEvent('postsList', 'keydown', (event) => {
    if (event.key === 'Enter' && event.target.matches('input[data-comment-for]')) submitComment(Number(event.target.dataset.commentFor));
});
wireEvent('postsList', 'input', (event) => {
    if (event.target.matches('textarea[data-moderation-for]')) clearModerationError(Number(event.target.dataset.moderationFor));
});
(() => {
    const trending = document.getElementById('trendingList');
    if (!trending) return;
    const go = (event) => {
        const item = event.target.closest('[data-action="scroll-to"]');
        if (item) scrollToPost(Number(item.dataset.id));
    };
    trending.addEventListener('click', go);
    trending.addEventListener('keydown', (event) => {
        if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-action="scroll-to"]')) { event.preventDefault(); go(event); }
    });
})();
