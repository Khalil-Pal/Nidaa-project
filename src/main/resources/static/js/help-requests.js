// help-requests.html — page script (moved out of the HTML for the strict Content Security Policy, F-5).
// Loaded after js/nidaa-common.js, which provides API, apiFetch, authHeader, escHtml, wire() and friends.

    if (!localStorage.getItem('token')) window.location.href = 'login.html';

    const user        = JSON.parse(localStorage.getItem('user') || '{}');
    const fullName    = user.fullName || user.name || 'User';
    const firstName   = fullName.split(' ')[0];
    const userRole    = (user.role || 'beneficiary').toLowerCase();
    const roleDisplay = userRole.charAt(0).toUpperCase() + userRole.slice(1);
    const initials    = fullName.split(' ').map(n => n[0]).join('').slice(0, 2).toUpperCase();

    {const _e=document.getElementById('sidebarName'); if(_e) _e.textContent = fullName;}
    {const _e=document.getElementById('sidebarRole'); if(_e) _e.textContent = roleDisplay;}
    {const _e=document.getElementById('sidebarAvatar'); if(_e) _e.textContent = initials;}
    document.getElementById('topbarName').textContent    = firstName;
    document.getElementById('topbarRole').textContent    = roleDisplay;



    // Set page text and show FAB based on role
    if (userRole === 'beneficiary') {
        document.getElementById('pageTitle').textContent = 'My Help Requests';
        document.getElementById('pageDesc').textContent  = 'Submit and track your help requests.';
        document.getElementById('fabBtn').style.display  = 'flex';
    } else if (userRole === 'volunteer' || userRole === 'organization') {
        document.getElementById('pageTitle').textContent = 'Available Help Requests';
        document.getElementById('pageDesc').textContent  = 'Browse requests and offer your help.';
    } else if (userRole === 'admin') {
        document.getElementById('pageTitle').textContent = 'All Help Requests';
        document.getElementById('pageDesc').textContent  = 'Manage all platform requests.';
    }

    let allRequests = [];

    const typeTag = {
        MEDICAL:'tag-medical', FOOD:'tag-food', SHELTER:'tag-shelter',
        WATER:'tag-water', CLOTHING:'tag-clothing', PSYCHOLOGICAL:'tag-psychological', OTHER:'tag-other'
    };
    const urgencyTag = {
        CRITICAL:'urgency-critical', HIGH:'urgency-high', MEDIUM:'urgency-medium', LOW:'urgency-low'
    };
    const statusTag = {
        PENDING:'status-pending', ASSIGNED:'status-assigned',
        COMPLETED:'status-completed', CANCELLED:'status-cancelled'
    };

    function formatDate(d) {
        if (!d) return '';
        return new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function renderCards(list) {
        const grid  = document.getElementById('cardsGrid');
        const empty = document.getElementById('emptyState');
        if (!list.length) {
            grid.innerHTML    = '';
            empty.style.display = 'block';
            return;
        }
        empty.style.display = 'none';

        grid.innerHTML = list.map(r => {
            const type   = (r.helpType   || r.type    || 'OTHER').toUpperCase();
            const urg    = (r.urgencyLevel|| r.urgency || 'MEDIUM').toUpperCase();
            const status = (r.status     || 'PENDING').toUpperCase();
            const title  = r.title       || 'Help Request';
            const desc   = r.description || r.desc    || '';
            const id     = Number(r.requestId || r.id) || '';
            const date   = formatDate(r.createdAt || r.date);
            const people = r.peopleCount || r.people || '';

            // Action button changes based on role
            let actionBtn = '';
            if (userRole === 'beneficiary') {
                if (status === 'ASSIGNED' || status === 'COMPLETED') {
                    actionBtn = `<button class="btn-action btn-view" data-action="contact" data-id="${id}"><i class="fa fa-address-card" style="font-size:11px;margin-right:4px"></i>Contact Responder</button>`;
                }
                // R-1: once the help was delivered the beneficiary is asked to rate it
                if (status === 'COMPLETED') {
                    actionBtn += ` <button class="btn-action btn-view" data-action="report" data-id="${id}"><i class="fa fa-star" style="font-size:11px;margin-right:4px"></i>Rate this help</button>`;
                }
            } else if (userRole === 'volunteer' || userRole === 'organization') {
                if (status === 'PENDING') {
                    actionBtn = `<button class="btn-action btn-accept" style="background:var(--green)" data-action="start" data-id="${id}"><i class="fa fa-play" style="font-size:11px;margin-right:4px"></i>Start Working</button>`;
                } else if (status === 'ASSIGNED' || status === 'COMPLETED') {
                    actionBtn = `<button class="btn-action btn-view" data-action="contact" data-id="${id}"><i class="fa fa-address-card" style="font-size:11px;margin-right:4px"></i>Contact & Status</button>`;
                }
                // R-1: the volunteer records what was delivered (reports are volunteer-only in the data model)
                if (status === 'COMPLETED' && userRole === 'volunteer') {
                    actionBtn += ` <button class="btn-action btn-view" data-action="report" data-id="${id}"><i class="fa fa-clipboard-check" style="font-size:11px;margin-right:4px"></i>Record what you delivered</button>`;
                }
            }

            return `<div class="req-card">
                <div class="card-badges">
                    <span class="tag ${typeTag[type]   || 'tag-other'}">${escHtml(type)}</span>
                    <span class="tag ${urgencyTag[urg] || ''}">${escHtml(urg)}</span>
                    <span class="tag ${statusTag[status] || 'status-pending'}">${escHtml(status)}</span>
                </div>
                <div class="card-title">${escHtml(title)}</div>
                ${desc ? `<div class="card-desc">${escHtml(desc.substring(0,120))}${desc.length>120?'...':''}</div>` : ''}
                <div class="card-meta">

${people ? `<span><i class="fa fa-users" style="font-size:11px"></i> ${escHtml(people)}</span>` : ''}
                    ${r.address ? `<span><i class="fa fa-location-dot" style="font-size:11px"></i> ${escHtml(r.address)}</span>` : ''}
                    <span><i class="fa fa-hashtag" style="font-size:11px"></i>${id}</span>
                </div>
                <div class="card-footer">
                    <span class="card-date">${escHtml(date)}</span>
                    ${actionBtn}
                </div>
            </div>`;
        }).join('');
    }

    function filterCards() {
        const status = (document.getElementById('filterStatus').value || '').toUpperCase();
        const type   = (document.getElementById('filterType').value   || '').toUpperCase();
        const search = ((document.getElementById('filterSearch').value || '') + (document.getElementById('searchBar').value || '')).toLowerCase();

        renderCards(allRequests.filter(r => {
            const rType   = (r.helpType    || r.type    || '').toUpperCase();
            const rStatus = (r.status      || '').toUpperCase();
            const rText   = ((r.title || '') + ' ' + (r.description || '')).toLowerCase();
            return (!status || rStatus === status) &&
                (!type   || rType   === type)   &&
                (!search || rText.includes(search));
        }));
    }

    async function loadRequests() {
        const grid = document.getElementById('cardsGrid');
        grid.innerHTML = '<div style="text-align:center;padding:48px;color:#475569;grid-column:1/-1"><i class="fa fa-spinner fa-spin" style="font-size:24px;margin-bottom:12px;display:block"></i>Loading...</div>';
        try {
            // Beneficiary sees only their own requests
            const endpoint = userRole === 'beneficiary' ? '/help-requests/my' : '/help-requests?page=0&size=100';
            const res  = await apiFetch(API + endpoint, { headers: authHeader() });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            // ApiResponse{ data: Page{ content:[...] } } or ApiResponse{ data: [...] }
            const inner = (await res.json()).data ?? [];
            allRequests = Array.isArray(inner) ? inner : (inner.content ?? []);
        } catch (e) {
            allRequests = [];
            grid.innerHTML = '<div style="text-align:center;padding:48px;color:#475569;grid-column:1/-1"><i class="fa fa-triangle-exclamation" style="font-size:28px;margin-bottom:10px;display:block;color:#f59e0b"></i>Could not load requests. Make sure the server is running.</div>';
            return;
        }
        filterCards();
    }

    // ── START WORKING (volunteer/organization) ──
    let currentContactRequestId = null;

    async function startWork(id, btn) {
        if (!confirm('Start working on this request? The requester\'s contact info will be shown.')) return;
        btn.disabled = true;
        btn.innerHTML = '<i class="fa fa-spinner fa-spin"></i> Starting...';
        try {
            const res = await apiFetch(API + '/help-requests/' + id + '/assign', {
                method: 'PUT', headers: authHeader()
            });
            if (!res.ok) throw new Error('HTTP ' + res.status);

            // Update local list status
            const req = allRequests.find(r => (r.requestId || r.id) == id);
            if (req) req.status = 'ASSIGNED';
            filterCards();

            await openContactModal(id);
        } catch (e) {
            alert('Could not start: ' + e.message);
            btn.disabled = false;
            btn.innerHTML = '<i class="fa fa-play" style="font-size:11px;margin-right:4px"></i>Start Working';
        }
    }

    async function openContactModal(id, title) {
        currentContactRequestId = id;
        const req = allRequests.find(r => (r.requestId || r.id) == id);
        document.getElementById('contactRequestTitle').textContent =
            title || (req && req.title) || 'Help Request';
        document.getElementById('contactName').textContent  = '...';
        document.getElementById('contactEmail').textContent = '...';
        document.getElementById('contactPhone').textContent = '...';
        document.getElementById('contactPartyLabel').textContent = 'Contact';
        document.getElementById('contactStatusSection').style.display =
            userRole === 'beneficiary' ? 'none' : 'block';
        document.getElementById('contactOverlay').classList.add('open');
        // Set current status in dropdown
        if (req && userRole !== 'beneficiary') {
            buildStatusOptions(req.status || 'ASSIGNED');
        }
        try {
            const res = await apiFetch(API + '/help-requests/' + id + '/contact', { headers: authHeader() });
            if (!res.ok) {
                const error = await res.json().catch(() => ({}));
                throw new Error(error.message || 'Contact is not available');
            }
            const d = (await res.json()).data || {};
            document.getElementById('contactPartyLabel').textContent = formatContactRole(d.contactRole);
            document.getElementById('contactName').textContent  = d.name  || '—';
            document.getElementById('contactEmail').textContent = d.email || '—';
            document.getElementById('contactPhone').textContent = d.phone || 'Not provided';
        } catch (error) {
            document.getElementById('contactName').textContent = error.message;
            document.getElementById('contactEmail').textContent = '—';
            document.getElementById('contactPhone').textContent = '—';
        }
    }

    function formatContactRole(role) {
        const normalized = (role || 'Contact').toLowerCase();
        return normalized.charAt(0).toUpperCase() + normalized.slice(1);
    }

    function closeContactModal() {
        document.getElementById('contactOverlay').classList.remove('open');
        currentContactRequestId = null;
    }

    // Build dropdown options based on current status and valid transitions
    function buildStatusOptions(currentStatus) {
        const VALID_TRANSITIONS = {
            'PENDING':   ['ASSIGNED', 'CANCELLED'],
            'ASSIGNED':  ['COMPLETED', 'CANCELLED'],
            'COMPLETED': [],
            'CANCELLED': []
        };
        const LABELS = {
            'ASSIGNED':  'Assigned (In Progress)',
            'COMPLETED': 'Completed',
            'CANCELLED': 'Cancelled',
            'PENDING':   'Pending'
        };
        const sel = document.getElementById('contactStatusSelect');
        const transitions = VALID_TRANSITIONS[currentStatus.toUpperCase()] || [];
        sel.innerHTML = '';

        if (transitions.length === 0) {
            sel.innerHTML = '<option value="" disabled>No further transitions allowed</option>';
            document.querySelector('.btn-update-status').disabled = true;
            return;
        }

        document.querySelector('.btn-update-status').disabled = false;
        transitions.forEach(status => {
            const opt = document.createElement('option');
            opt.value = status;
            opt.textContent = LABELS[status] || status;
            sel.appendChild(opt);
        });
    }

    async function submitStatusChange() {
        if (!currentContactRequestId) return;
        const status = document.getElementById('contactStatusSelect').value;
        const updateBtn = document.querySelector('.btn-update-status');
        if (updateBtn) { updateBtn.disabled = true; updateBtn.textContent = 'Updating...'; }
        try {
            const res = await apiFetch(API + '/help-requests/' + currentContactRequestId + '/status?status=' + status, {
                method: 'PUT', headers: authHeader()
            });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            // Update local list
            const req = allRequests.find(r => (r.requestId || r.id) == currentContactRequestId);
            if (req) req.status = status;
            filterCards();
            closeContactModal();
            // Toast
            const t = document.createElement('div');
            t.style.cssText = 'position:fixed;top:20px;right:20px;background:#f0fdf4;border:1px solid #bbf7d0;color:#047857;padding:14px 22px;border-radius:12px;font-weight:600;z-index:9999;font-size:14px;font-family:Inter,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.1)';
            t.textContent = '✅ Status updated to ' + status.charAt(0) + status.slice(1).toLowerCase();
            document.body.appendChild(t);
            setTimeout(() => t.remove(), 3000);
        } catch (e) {
            alert('Could not update status: ' + e.message);
        }
        if (updateBtn) { updateBtn.disabled = false; updateBtn.textContent = 'Update Status'; }
    }

    document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeContactModal(); closeReportModal(); } });

    // ── R-1: delivery report and beneficiary rating ───────────────────────────
    let reportAssignmentId = null;

    function closeReportModal() {
        document.getElementById('reportOverlay').classList.remove('open');
        reportAssignmentId = null;
    }

    function reportToast(text) {
        const t = document.createElement('div');
        t.style.cssText = 'position:fixed;top:20px;right:20px;background:#f0fdf4;border:1px solid #bbf7d0;color:#047857;padding:14px 22px;border-radius:12px;font-weight:600;z-index:9999;font-size:14px;font-family:Inter,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.1)';
        t.textContent = '✅ ' + text;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), 3000);
    }

    /** The report lives on the assignment; the request's history says which assignment completed it. */
    async function completedAssignmentId(requestId) {
        const res = await apiFetch(API + '/v1/assignments/help-requests/' + requestId);
        if (!res.ok) return null;
        const history = (await res.json()).data || [];
        const done = history.filter(a => (a.status || '').toUpperCase() === 'COMPLETED');
        const pick = done.length ? done[done.length - 1] : history[history.length - 1];
        return pick ? pick.assignmentId : null;
    }

    async function openReportModal(requestId) {
        const req = allRequests.find(r => (r.requestId || r.id) == requestId);
        document.getElementById('reportRequestTitle').textContent = (req && req.title) || 'Help Request';
        document.getElementById('reportTitle').textContent = userRole === 'beneficiary' ? 'Rate this help' : 'Delivery Report';
        const body = document.getElementById('reportBody');
        body.innerHTML = '<p class="report-note">Loading…</p>';
        document.getElementById('reportOverlay').classList.add('open');

        reportAssignmentId = await completedAssignmentId(requestId);
        if (!reportAssignmentId) {
            body.innerHTML = '<p class="report-note">No completed assignment was found for this request.</p>';
            return;
        }
        const res = await apiFetch(API + '/assignments/' + reportAssignmentId + '/report');
        if (res.status === 404) { renderReport(null); return; }
        if (!res.ok) {
            body.innerHTML = '<p class="report-note">Could not load the report (HTTP ' + res.status + ').</p>';
            return;
        }
        renderReport((await res.json()).data || null);
    }

    function renderReport(report) {
        const body = document.getElementById('reportBody');
        let html = '';
        if (report) {
            html += '<div class="report-block"><h4>What was delivered</h4><p>' + escHtml(report.description) + '</p>' +
                '<div class="report-meta">Recorded by ' + escHtml(report.volunteerName || 'the volunteer') +
                (report.createdAt ? ' · ' + escHtml(formatDate(report.createdAt)) : '') + '</div></div>';
            if (report.beneficiaryRating) {
                html += '<div class="report-block"><h4>Beneficiary\'s rating</h4>' +
                    '<div class="rating-given" aria-label="' + report.beneficiaryRating + ' out of 5">' +
                    '★'.repeat(report.beneficiaryRating) + '☆'.repeat(5 - report.beneficiaryRating) + '</div>' +
                    (report.feedbackFromBeneficiary ? '<p>' + escHtml(report.feedbackFromBeneficiary) + '</p>' : '') + '</div>';
            } else if (userRole === 'beneficiary') {
                html += '<form id="feedbackForm"><fieldset style="border:none;padding:0;margin:0">' +
                    '<legend style="font-size:14px;font-weight:600;margin-bottom:4px">How was this help?</legend>' +
                    '<div class="stars">' + [1, 2, 3, 4, 5].map(n =>
                        '<input type="radio" name="rating" id="rating' + n + '" value="' + n + '" required/>' +
                        '<label for="rating' + n + '" title="' + n + ' of 5">' + n + '</label>').join('') + '</div></fieldset>' +
                    '<div class="form-group"><label for="feedbackText">Anything to add? <span style="font-weight:400;color:var(--muted)">(optional)</span></label>' +
                    '<textarea id="feedbackText" maxlength="2000" placeholder="What went well, what could be better"></textarea></div>' +
                    '<button type="submit" class="btn-update-status" id="feedbackSubmitBtn">Send rating</button></form>';
            } else {
                html += '<p class="report-note">The beneficiary has not rated this help yet.</p>';
            }
        } else if (userRole === 'volunteer') {
            html += '<p class="report-note">Record what you delivered. The beneficiary sees it and is asked to rate the help.</p>' +
                '<form id="reportForm"><div class="form-group"><label for="reportDescription">What was delivered</label>' +
                '<textarea id="reportDescription" maxlength="4000" required placeholder="e.g. Food parcels for three people, delivered Tuesday afternoon"></textarea></div>' +
                '<button type="submit" class="btn-update-status" id="reportSubmitBtn">Record delivery</button></form>';
        } else {
            html += '<p class="report-note">The volunteer has not recorded the delivery yet. You can rate this help once they have.</p>';
        }
        body.innerHTML = html;
        const reportForm = document.getElementById('reportForm');
        if (reportForm) reportForm.addEventListener('submit', (e) => { e.preventDefault(); submitReport(); });
        const feedbackForm = document.getElementById('feedbackForm');
        if (feedbackForm) feedbackForm.addEventListener('submit', (e) => { e.preventDefault(); submitFeedback(); });
    }

    async function submitReport() {
        const description = document.getElementById('reportDescription').value.trim();
        if (!description) { alert('Please describe what was delivered.'); return; }
        const btn = document.getElementById('reportSubmitBtn');
        btn.disabled = true; btn.textContent = 'Saving...';
        try {
            const res = await apiFetch(API + '/assignments/' + reportAssignmentId + '/report', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ description })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
            renderReport(data.data);
            reportToast('Delivery recorded.');
        } catch (e) {
            alert('Could not record the delivery: ' + e.message);
            btn.disabled = false; btn.textContent = 'Record delivery';
        }
    }

    async function submitFeedback() {
        const picked = document.querySelector('#feedbackForm input[name="rating"]:checked');
        if (!picked) { alert('Please choose a rating from 1 to 5.'); return; }
        const btn = document.getElementById('feedbackSubmitBtn');
        btn.disabled = true; btn.textContent = 'Sending...';
        try {
            const res = await apiFetch(API + '/assignments/' + reportAssignmentId + '/feedback', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rating: Number(picked.value), feedback: document.getElementById('feedbackText').value.trim() || null })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
            renderReport(data.data);
            reportToast('Thank you for rating this help.');
        } catch (e) {
            alert('Could not send the rating: ' + e.message);
            btn.disabled = false; btn.textContent = 'Send rating';
        }
    }

    function openModal()  { document.getElementById('overlay').classList.add('open'); }
    function closeModal() { document.getElementById('overlay').classList.remove('open'); }
    document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

    // ── Location for automatic matching (L-1) ──
    // Automatic geo-matching only runs when the request carries coordinates.
    // Without them the request is still created and handled manually.
    const MANUAL_MATCH_TEXT = 'No location shared \u2014 this request will be matched manually by the Nidaa team.';
    let requestCoords = null;   // { latitude, longitude, source: 'device' | 'saved' }

    function renderRequestLocation() {
        const status = document.getElementById('reqLocationStatus');
        const clearBtn = document.getElementById('clearLocationBtn');
        if (!requestCoords) {
            status.textContent = MANUAL_MATCH_TEXT;
            status.className = 'location-status';
            clearBtn.hidden = true;
            return;
        }
        const where = requestCoords.latitude.toFixed(4) + ', ' + requestCoords.longitude.toFixed(4);
        status.textContent = requestCoords.source === 'saved'
            ? 'Using the location saved in your Settings (' + where + '). The nearest available provider will be assigned automatically.'
            : 'Location captured (' + where + '). The nearest available provider will be assigned automatically.';
        status.className = 'location-status ok';
        clearBtn.hidden = false;
    }

    function setRequestLocation(latitude, longitude, source) {
        requestCoords = { latitude: Number(latitude), longitude: Number(longitude), source };
        renderRequestLocation();
    }

    function clearRequestLocation() {
        requestCoords = null;
        renderRequestLocation();
    }

    function useMyLocation() {
        const status = document.getElementById('reqLocationStatus');
        const btn = document.getElementById('useMyLocationBtn');
        if (!navigator.geolocation) {
            status.textContent = 'Your browser cannot share its location. ' + MANUAL_MATCH_TEXT;
            status.className = 'location-status error';
            return;
        }
        btn.disabled = true;
        status.textContent = 'Asking your browser for permission\u2026';
        status.className = 'location-status';
        navigator.geolocation.getCurrentPosition(
            position => {
                btn.disabled = false;
                setRequestLocation(position.coords.latitude, position.coords.longitude, 'device');
            },
            error => {
                btn.disabled = false;
                const why = error.code === error.PERMISSION_DENIED
                    ? 'Location permission was declined.'
                    : 'Your location could not be determined.';
                requestCoords = requestCoords && requestCoords.source === 'saved' ? requestCoords : null;
                if (requestCoords) { renderRequestLocation(); return; }
                status.textContent = why + ' ' + MANUAL_MATCH_TEXT;
                status.className = 'location-status error';
            },
            { enableHighAccuracy: false, timeout: 10000, maximumAge: 300000 }
        );
    }

    // A beneficiary who saved a matching location in Settings gets it as the
    // default, so their requests are auto-matched without a permission prompt.
    async function prefillSavedLocation() {
        if (userRole !== 'beneficiary') return;
        try {
            const res = await apiFetch(API + '/users/me/profile', { headers: authHeader() });
            if (!res.ok) return;
            const profile = (await res.json()).data;
            if (profile && profile.latitude != null && profile.longitude != null) {
                setRequestLocation(profile.latitude, profile.longitude, 'saved');
            }
        } catch (ignored) {}
    }
    renderRequestLocation();
    prefillSavedLocation();

    document.getElementById('requestForm').addEventListener('submit', async function(e) {
        e.preventDefault();
        const title       = document.getElementById('reqTitle').value.trim();
        const helpType    = document.getElementById('reqType').value;
        const urgencyLevel= document.getElementById('reqUrgency').value;
        const description = document.getElementById('reqDesc').value.trim();
        const numberOfPeople = document.getElementById('reqPeople').value.trim();

        if (!title)         { alert('Please enter a request title.'); return; }
        if (!helpType)      { alert('Please select the type of help.'); return; }
        if (!urgencyLevel)  { alert('Please select the urgency level.'); return; }

        const btn = document.getElementById('submitBtn');
        btn.disabled = true;
        btn.textContent = 'Submitting...';

        try {
            const res = await apiFetch(API + '/help-requests', {
                method: 'POST',
                headers: authHeader(),
                body: JSON.stringify({
                    title:        title,
                    helpType:     helpType,
                    urgencyLevel: urgencyLevel,
                    description:  description || title,
                    numberOfPeople: numberOfPeople,
                    address:       document.getElementById('reqAddress').value.trim() || null,
                    latitude:      requestCoords ? requestCoords.latitude  : null,
                    longitude:     requestCoords ? requestCoords.longitude : null
                })
            });

            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || 'Server error ' + res.status);
            }

            // ApiResponse{ data: HelpRequest }
            const newReq = (await res.json()).data;

            // Add to the top of the list and re-render
            allRequests.unshift(newReq);
            filterCards();
            closeModal();
            const sentCoords = requestCoords;
            this.reset();
            if (sentCoords && sentCoords.source === 'saved') { renderRequestLocation(); } else { clearRequestLocation(); }

            // Success toast: say what matching did with the request
            const toast = document.createElement('div');
            toast.style.cssText = 'position:fixed;top:20px;right:20px;background:#f0fdf4;border:1px solid #bbf7d0;color:#047857;padding:14px 22px;border-radius:12px;font-weight:600;z-index:9999;box-shadow:0 4px 20px rgba(0,0,0,.1);font-family:Inter,sans-serif;font-size:14px;max-width:360px';
            toast.textContent = newReq.status === 'ASSIGNED'
                ? '\u2705 Request saved and matched to the nearest available provider.'
                : sentCoords
                    ? '\u2705 Request saved. No provider is available nearby right now; it stays in the queue and will be matched as soon as one is.'
                    : '\u2705 Request saved. It will be matched manually by the Nidaa team.';
            document.body.appendChild(toast);
            setTimeout(() => toast.remove(), 6000);

        } catch (err) {
            alert('❌ Could not submit: ' + err.message);
        }

        btn.disabled = false;
        btn.textContent = 'Submit Request';
    });

    loadRequests();

    // ── ACCESS CONTROL: Psychologist cannot access help-requests page ──
    if (userRole === 'psychologist') {
        window.location.href = 'psychological.html';
    }

    // ── BUILD SIDEBAR BASED ON ROLE ──
    buildSidebar('help-requests.html');


    function toggleSidebar() { document.getElementById('sidebar').classList.toggle('open'); }

// ---- Event wiring (F-5) ----------------------------------------------------
// Controls are wired here by id or class instead of with inline on* attributes,
// which script-src 'self' forbids. wire() also makes non-button elements
// keyboard-operable (Enter/Space).
wireEvent('sidebarToggle', 'click', () => { toggleSidebar(); });
wireEvent('searchBar', 'input', () => { filterCards ? filterCards() : null; });
wireEvent('logoutBtn', 'click', () => { logout(); });
wireEvent('filterStatus', 'change', () => { filterCards(); });
wireEvent('filterType', 'change', () => { filterCards(); });
wireEvent('filterRegion', 'input', () => { filterCards(); });
wireEvent('filterSearch', 'input', () => { filterCards(); });
wireEvent('fabBtn', 'click', () => { openModal(); });
wireEvent('overlay', 'click', function (event) { if(event.target===this)closeModal(); });
wireEvent('closeModalBtn', 'click', () => { closeModal(); });
wireEvent('useMyLocationBtn', 'click', () => { useMyLocation(); });
wireEvent('clearLocationBtn', 'click', () => { clearRequestLocation(); });
wireEvent('submitStatusChangeBtn', 'click', () => { submitStatusChange(); });
wireEvent('closeContactModalBtn', 'click', () => { closeContactModal(); });
wireEvent('closeReportModalBtn', 'click', () => { closeReportModal(); });
wireEvent('reportOverlay', 'click', function (event) { if (event.target === this) closeReportModal(); });

// ---- Delegated actions (F-5) ----------------------------------------------
// Rendered markup carries data-action / data-id instead of inline handlers;
// one listener on the container dispatches them.
wireEvent('cardsGrid', 'click', (event) => {
    const btn = event.target.closest('button[data-action]');
    if (!btn) return;
    const id = Number(btn.dataset.id);
    if (btn.dataset.action === 'start') startWork(id, btn);
    else if (btn.dataset.action === 'contact') openContactModal(id);
    else if (btn.dataset.action === 'report') openReportModal(id);
});
