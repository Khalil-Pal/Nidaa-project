// admin-requests.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

if (!localStorage.getItem('token')) window.location.href = 'login.html';
const user = JSON.parse(localStorage.getItem('user') || '{}');
buildSidebar('admin-requests.html');
if ((user.role || '').toLowerCase() !== 'admin') window.location.href = 'dashboard.html';
document.getElementById('topbarName').textContent = (user.fullName || user.name || 'Admin').split(' ')[0];

const typeTagClass = {MEDICAL:'tag-medical',FOOD:'tag-food',SHELTER:'tag-shelter',WATER:'tag-water',CLOTHING:'tag-clothing',PSYCHOLOGICAL:'tag-other',OTHER:'tag-other'};
const statusClass  = {PENDING:'status-pending',ASSIGNED:'status-assigned',IN_PROGRESS:'status-assigned',COMPLETED:'status-completed',CANCELLED:'status-cancelled'};

let allRequests = [];

function fmtDate(d) {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('en-US', {month:'short',day:'numeric',year:'numeric'});
}

function fmtDistance(km) {
    return Number.isFinite(km) ? km.toFixed(1) + ' km' : '';
}

function capacityIndicator(request) {
    if (request.requestType !== 'HELP' || !request.suggestedVolunteerName || request.suggestedVolunteerName === 'N/A') return null;
    if (request.capacitySufficient === true) {
        return { className: 'capacity-sufficient', text: 'Capacity sufficient' };
    }
    if (request.capacitySufficient === false) {
        return { className: 'capacity-insufficient', text: 'Capacity insufficient' };
    }
    return { className: 'capacity-unknown', text: 'Capacity unknown' };
}

function capacityDetail(request) {
    const indicator = capacityIndicator(request);
    if (!indicator) return 'N/A';
    if (request.capacityMode === 'NUMERIC' && Number.isFinite(request.capacityAmount)) {
        return Number.isInteger(request.peopleCount) && request.peopleCount > 0
            ? `${indicator.text}: ${request.capacityAmount} for ${request.peopleCount} people`
            : `${indicator.text}: numeric amount ${request.capacityAmount}, request count not set`;
    }
    return request.capacityMode === 'QUALITATIVE'
        ? 'Capacity unknown (qualitative resource)'
        : indicator.text;
}

function renderTable(list) {
    const tbody = document.getElementById('requestsBody');
    document.getElementById('totalCount').textContent = list.length + ' request' + (list.length !== 1 ? 's' : '');
    if (!list.length) {
        tbody.innerHTML = `<tr><td colspan="10"><div class="empty-state"><i class="fa fa-inbox"></i><p>No requests found.</p></div></td></tr>`;
        return;
    }
    tbody.innerHTML = list.map(r => {
        const type   = (r.helpType   || 'OTHER').toUpperCase();
        const status = (r.status     || 'PENDING').toUpperCase();
        const priority = Number.isFinite(r.priorityScore) ? r.priorityScore : null;
        const priorityClass = priority !== null && priority >= 70 ? 'priority-score high' : 'priority-score';
        const suggestionDistance = fmtDistance(r.distanceKm);
        const capacity = capacityIndicator(r);
        return `<tr data-index="${allRequests.indexOf(r)}" tabindex="0" role="button" aria-label="Open request details">
            <td style="color:var(--muted);font-size:12px">#${escHtml(r.id||'—')}</td>
            <td style="font-weight:600;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escHtml(r.title||'—')}</td>
            <td>${r.isCrisis ? '<i class="fa fa-triangle-exclamation crisis-icon" title="Crisis case"></i>' : r.needsReview ? '<i class="fa fa-flag review-icon" title="Flagged for review: the description contains wording worth a closer look"></i>' : ''}</td>
            <td><span class="tag ${typeTagClass[type]||'tag-other'}">${escHtml(type)}</span></td>
            <td><span class="${priorityClass}">${priority !== null ? priority : '—'}</span></td>
            <td><span class="tag ${statusClass[status]||'status-pending'}">${escHtml(String(status).replace('_', ' '))}</span></td>
            <td>
                <div class="user-cell">
                    <span class="u-name">${escHtml(r.requesterName||'—')}</span>
                    <span class="u-email">${escHtml(r.requesterEmail||'')}</span>
                    ${r.filedByUserId != null ? `<span class="tag tag-filed" data-filed-by="${Number(r.filedByUserId)}">Filed on behalf of ${escHtml(r.requesterName||'this person')}</span>` : ''}
                    ${r.needsAttention ? `<span class="tag tag-attention" data-needs-attention="${Number(r.id)}" title="${escHtml(r.attentionReason||'')}">Needs attention: ${escHtml(r.attentionReason||'unmatched')}</span>` : ''}
                    ${Number(r.declines) > 0 ? `<span class="tag tag-declined">${Number(r.declines)} declined</span>` : ''}
                </div>
            </td>
            <td>
                <div class="user-cell">
                    <span class="u-name">${escHtml(r.workerName||'—')}</span>
                    <span class="u-email" style="color:${r.workerRole&&r.workerRole!=='—'?'var(--blue)':'var(--muted)'}">${escHtml(r.workerRole&&r.workerRole!=='—'?r.workerRole:'')}</span>
                </div>
            </td>
            <td>
                <div class="suggestion-cell">
                    <span class="suggestion-name">${escHtml(r.suggestedVolunteerName||'N/A')}</span>
                    <span class="suggestion-distance">${escHtml(suggestionDistance)}</span>
                    ${capacity ? `<span class="capacity-badge ${capacity.className}">${capacity.text}</span>` : ''}
                </div>
            </td>
            <td style="font-size:13px;color:var(--muted)">${escHtml(fmtDate(r.createdAt))}</td>
        </tr>`;
    }).join('');
}

function filterRequests() {
    const search = document.getElementById('searchInput').value.toLowerCase();
    const status = document.getElementById('statusFilter').value.toUpperCase();
    const type   = document.getElementById('typeFilter').value.toUpperCase();
    renderTable(allRequests.filter(r =>
        (!status || (r.status||'').toUpperCase() === status) &&
        (!type   || (r.helpType||'').toUpperCase() === type) &&
        (!search || (r.title||'').toLowerCase().includes(search) ||
            (r.requesterName||'').toLowerCase().includes(search) ||
            (r.requesterEmail||'').toLowerCase().includes(search) ||
            (r.description||'').toLowerCase().includes(search) ||
            (r.suggestedVolunteerName||'').toLowerCase().includes(search))
    ));
}

function openModal(idx) {
    const r = allRequests[idx];
    if (!r) return;
    document.getElementById('modalTitle').textContent       = r.title || 'Help Request #' + r.id;
    document.getElementById('mType').textContent           = r.helpType    || '—';
    document.getElementById('mUrgency').textContent        = r.urgencyLevel|| '—';
    document.getElementById('mStatus').textContent         = r.status      || '—';
    document.getElementById('mPriority').textContent       = Number.isFinite(r.priorityScore) ? r.priorityScore : '—';
    document.getElementById('mCrisis').textContent         = r.isCrisis ? 'Yes' : r.needsReview ? 'No (flagged for review)' : 'No';
    document.getElementById('mAddress').textContent        = r.address     || 'Not specified';
    document.getElementById('mDesc').textContent           = r.description || '—';
    document.getElementById('mDate').textContent           = fmtDate(r.createdAt);
    document.getElementById('mRequesterName').textContent  = (r.requesterName  || '—') + (r.filedByUserId != null ? ' (filed on their behalf by user #' + r.filedByUserId + ')' : '');
    document.getElementById('mRequesterEmail').textContent = r.requesterEmail || '—';
    document.getElementById('mRequesterPhone').textContent = r.requesterPhone || '—';
    document.getElementById('mWorkerName').textContent = r.workerName || 'Not assigned yet';
    const suggested = r.suggestedVolunteerName ? r.suggestedVolunteerName + (fmtDistance(r.distanceKm) ? ' (' + fmtDistance(r.distanceKm) + ')' : '') : 'N/A';
    const suggestedEl = document.getElementById('mSuggested');
    if (suggestedEl) suggestedEl.textContent = suggested;
    document.getElementById('mCapacity').textContent = capacityDetail(r);
    document.getElementById('detailModal').classList.add('open');
}

function closeModal() { document.getElementById('detailModal').classList.remove('open'); }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

function normalizeRankedDashboard(payload) {
    const material = (payload.rankedMaterialRequests || []).map(item => {
        const request = item.request || {};
        const workerName = request.assignedVolunteerId || request.assignedOrganizationId
            ? 'Assigned'
            : 'Not assigned yet';
        return {
            id: request.id,
            requestType: 'HELP',
            title: request.title || 'Help Request #' + (request.id || ''),
            helpType: request.helpType || 'OTHER',
            urgencyLevel: request.urgencyLevel || '',
            priorityScore: Number.isFinite(item.priorityScore) ? item.priorityScore : request.priorityScore,
            isCrisis: false,
            needsReview: false,
            status: request.status || 'PENDING',
            address: request.address,
            createdAt: request.createdAt,
            description: request.description,
            peopleCount: request.peopleCount,
            requesterName: request.beneficiaryName || (request.beneficiaryId ? 'Beneficiary #' + request.beneficiaryId : 'Beneficiary'),
            requesterEmail: '',
            requesterPhone: '',
            filedByUserId: request.filedByUserId,   // ON-2: badge "Filed on behalf of"
            needsAttention: request.needsAttention,  // GAP-1/GAP-2: three declines, or nobody for too long
            attentionReason: request.needsAttentionReason,
            workerName,
            workerRole: '',
            suggestedVolunteerName: item.suggestedVolunteerName || 'N/A',
            distanceKm: item.distanceKm,
            capacityMode: item.capacityMode,
            capacityAmount: item.capacityAmount,
            capacitySufficient: item.capacitySufficient
        };
    });

    const psychological = (payload.psychologicalRequests || payload.crisisPsychologicalCases || []).map(item => {
        const request = item.request || item;
        return {
            id: request.id,
            requestType: 'PSYCHOLOGICAL',
            title: (request.category || 'Psychological Support') + ' - ' + (request.preferredFormat || 'CHAT'),
            helpType: 'PSYCHOLOGICAL',
            urgencyLevel: request.urgencyLevel || '',
            priorityScore: Number.isFinite(item.priorityScore)
                ? item.priorityScore
                : (Number.isFinite(request.priorityScore) ? request.priorityScore : null),
            isCrisis: Boolean(request.isCrisis),
            needsReview: Boolean(request.needsReview),
            status: request.status || 'PENDING',
            address: null,
            createdAt: request.createdAt,
            description: request.description,
            peopleCount: null,
            requesterName: request.beneficiaryId ? 'Beneficiary #' + request.beneficiaryId : 'Beneficiary',
            requesterEmail: '',
            requesterPhone: '',
            workerName: request.assignedPsychologistId ? 'Assigned' : 'Not assigned yet',
            workerRole: 'Psychologist',
            suggestedVolunteerName: request.isCrisis ? 'On-duty psychologist required' : 'N/A',
            distanceKm: null,
            capacityMode: null,
            capacityAmount: null,
            capacitySufficient: null
        };
    });

    return [...material, ...psychological];
}

async function loadRequests() {
    try {
        const res  = await apiFetch(API + '/v1/admin/dashboard/ranked', { headers: authHeader() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        allRequests = normalizeRankedDashboard((await res.json()).data || {});
        renderTable(allRequests);
    } catch (e) {
        document.getElementById('requestsBody').innerHTML =
            `<tr><td colspan="10" style="text-align:center;padding:40px;color:#b91c1c">
                <i class="fa fa-triangle-exclamation" style="font-size:24px;margin-bottom:8px;display:block"></i>
                Could not load: ${escHtml(e.message)}
            </td></tr>`;
    }
}

loadRequests();

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { document.getElementById('sidebar').classList.toggle('open'); });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('searchInput', 'input', () => { filterRequests(); });
wireEvent('statusFilter', 'change', () => { filterRequests(); });
wireEvent('typeFilter', 'change', () => { filterRequests(); });
wireEvent('detailModal', 'click', function (event) { if(event.target===this)closeModal(); });
wireEvent('closeModalBtn', 'click', () => { closeModal(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
// Table rows open the detail modal: click, or Enter/Space when focused (they are in the tab order).
(() => {
  const body = document.getElementById('requestsBody');
  if (!body) return;
  const open = (event) => {
    const row = event.target.closest('tr[data-index]');
    if (row) openModal(Number(row.dataset.index));
  };
  body.addEventListener('click', open);
  body.addEventListener('keydown', (event) => {
    if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('tr[data-index]')) { event.preventDefault(); open(event); }
  });
})();
