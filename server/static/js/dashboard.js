(function () {
  const REFRESH_INTERVAL_MS = 15000;
  const currentMap = document.body.dataset.map || '';
  const isDeveloper = document.body.dataset.isDeveloper === 'true';
  const isSuperAdmin = document.body.dataset.isSuperAdmin === 'true';
  let lastEvents = [];

  function applyEventFilter() {
    const textInput = document.getElementById('logFilterText');
    const actionSelect = document.getElementById('logFilterAction');
    const countEl = document.getElementById('logFilterCount');
    const text = (textInput ? textInput.value : '').trim().toLowerCase();
    const action = actionSelect ? actionSelect.value : '';

    const filtered = lastEvents.filter((e) => {
      if (action && e.action !== action) return false;
      if (text) {
        const haystack = `${e.minecraft_username} ${e.item_name}`.toLowerCase();
        if (!haystack.includes(text)) return false;
      }
      return true;
    });

    StoreTrackerRender.events(filtered, isDeveloper);
    if (countEl) {
      countEl.textContent = (text || action) ? `${filtered.length} / ${lastEvents.length}건` : '';
    }
  }

  function bindLogFilter() {
    const textInput = document.getElementById('logFilterText');
    const actionSelect = document.getElementById('logFilterAction');
    const clearBtn = document.getElementById('logFilterClearBtn');
    if (!textInput || !actionSelect) return;
    textInput.addEventListener('input', applyEventFilter);
    actionSelect.addEventListener('change', applyEventFilter);
    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        textInput.value = '';
        actionSelect.value = '';
        applyEventFilter();
      });
    }
  }

  function bindExportRangeButton() {
    const btn = document.getElementById('exportRangeBtn');
    if (!btn) return;
    btn.addEventListener('click', () => {
      const start = document.getElementById('exportStartDate').value;
      const end = document.getElementById('exportEndDate').value;
      const params = new URLSearchParams({ map: currentMap });
      if (start) params.set('start', start);
      if (end) params.set('end', end);
      window.location.href = `/api/admin/events/export?${params.toString()}`;
    });
  }

  async function handleClearUnassignedReturns() {
    const result = await Swal.fire({
      title: '미귀속 반납을 전부 정리할까요?',
      text: '목록에서만 치워지고, 보유 장부나 재고는 바뀌지 않습니다. 몫 주인을 나중에 찾을 수 없게 됩니다.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: '정리',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
    });
    if (!result.isConfirmed) return;

    try {
      const res = await fetch(`/api/admin/unassigned-returns/clear?map=${encodeURIComponent(currentMap)}`, { method: 'POST' });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error || '정리 실패');
      StoreTrackerRender.toast('success', `${body.cleared}건 정리됐습니다`);
      loadUnassignedReturns();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '정리 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  function bindClearUnassignedReturnsButton() {
    const btn = document.getElementById('clearUnassignedReturnsBtn');
    if (!btn) return;
    btn.addEventListener('click', handleClearUnassignedReturns);
  }

  async function refresh(manual) {
    try {
      const data = await StoreTrackerApi.fetchDashboardData(currentMap);
      StoreTrackerRender.stats(data);
      lastEvents = data.events;
      applyEventFilter();
      StoreTrackerRender.inventory(data.inventory, isDeveloper, handleInventoryEdit);
      StoreTrackerRender.manualTransferItemOptions(data.inventory);
      StoreTrackerRender.manualTransferLog(data.events.filter((e) => e.action === 'HOLD' || e.action === 'RELEASE'));
      const [albaStatus, albaHistory] = await Promise.all([
        StoreTrackerApi.fetchAlbaStatus(currentMap),
        StoreTrackerApi.fetchAlbaHistory(currentMap),
      ]);
      StoreTrackerRender.albaStatus(albaStatus);
      StoreTrackerRender.albaHistory(albaHistory);
      loadAlbaPeople();
      if (manual) {
        StoreTrackerRender.toast('success', '새로고침 완료');
      }
    } catch (err) {
      if (manual) {
        StoreTrackerRender.toast('error', '새로고침 실패');
      }
    }
  }

  async function handleInventoryEdit(itemId, itemName, current) {
    const { value: input } = await Swal.fire({
      title: '재고 수동 수정',
      html: `<div class="text-start small text-secondary mb-2">${StoreTrackerRender.escapeHtml(itemName)}</div>`,
      input: 'number',
      inputValue: current,
      inputAttributes: { min: 0, step: 1 },
      showCancelButton: true,
      confirmButtonText: '저장',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
      inputValidator: (value) => (value === '' || Number(value) < 0 ? '0 이상의 숫자를 입력해주세요.' : undefined),
    });

    if (input === undefined || Number(input) === current) {
      return;
    }

    try {
      await StoreTrackerApi.adjustInventory(currentMap, itemId, Number(input));
      StoreTrackerRender.toast('success', '재고가 수정됐습니다');
      refresh(false);
    } catch (err) {
      Swal.fire({
        icon: 'error', title: '수정 실패', text: err.message, ...StoreTrackerRender.swalTheme(),
      });
    }
  }

  function bindManualTransferButtons() {
    const holdBtn = document.getElementById('manualTransferHoldBtn');
    const releaseBtn = document.getElementById('manualTransferReleaseBtn');
    if (!holdBtn || !releaseBtn) return;

    async function submit(action) {
      const itemId = document.getElementById('manualTransferItem').value;
      const count = Number(document.getElementById('manualTransferCount').value);

      if (!itemId || !count || count <= 0) {
        StoreTrackerRender.toast('error', '아이템/개수를 확인해주세요');
        return;
      }

      try {
        await StoreTrackerApi.manualTransfer(currentMap, itemId, count, action);
        StoreTrackerRender.toast('success', action === 'HOLD' ? '수동 사용이 기록됐습니다' : '수동 반납이 기록됐습니다');
        document.getElementById('manualTransferCount').value = '1';
        refresh(false);
      } catch (err) {
        Swal.fire({ icon: 'error', title: '처리 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
      }
    }

    holdBtn.addEventListener('click', () => submit('HOLD'));
    releaseBtn.addEventListener('click', () => submit('RELEASE'));
  }

  function bindAlbaStatusActions() {
    const body = document.getElementById('albaStatusBody');
    if (!body) return;
    body.addEventListener('click', async (ev) => {
      const btn = ev.target.closest('.alba-fire-btn');
      if (!btn) return;
      const result = await Swal.fire({
        title: '이 알바를 해고할까요?',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: '해고',
        cancelButtonText: '취소',
        ...StoreTrackerRender.swalTheme(),
      });
      if (!result.isConfirmed) return;
      try {
        await StoreTrackerApi.fireAlba(btn.dataset.hireId);
        StoreTrackerRender.toast('success', '해고 처리됐습니다');
        refresh(false);
      } catch (err) {
        Swal.fire({ icon: 'error', title: '처리 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
      }
    });
  }

  function bindAlbaHireForm() {
    const btn = document.getElementById('albaHireBtn');
    if (!btn) return;
    btn.addEventListener('click', async () => {
      const employer = document.getElementById('albaHireEmployer').value.trim();
      const employee = document.getElementById('albaHireEmployee').value.trim();
      const job = document.getElementById('albaHireJob').value;
      if (!employer || !employee) {
        StoreTrackerRender.toast('error', '고용주/알바 닉네임을 입력해주세요');
        return;
      }
      try {
        await StoreTrackerApi.hireAlba(currentMap, employer, employee, job || null);
        StoreTrackerRender.toast('success', '고용됐습니다');
        document.getElementById('albaHireEmployer').value = '';
        document.getElementById('albaHireEmployee').value = '';
        document.getElementById('albaHireJob').value = '';
        refresh(false);
      } catch (err) {
        Swal.fire({ icon: 'error', title: '고용 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
      }
    });
  }

  function bindEventRowClicks() {
    document.getElementById('eventsBody').addEventListener('click', async (ev) => {
      const row = ev.target.closest('.event-row');
      if (!row) return;
      try {
        const log = await StoreTrackerApi.fetchRequestLogForEvent(row.dataset.eventId);
        StoreTrackerRender.requestLogDetail(log);
      } catch (err) {
        StoreTrackerRender.toast('error', '상세 로그를 찾을 수 없습니다');
      }
    });
  }

  function bindMapSelect() {
    const mapSelect = document.getElementById('mapSelect');
    if (!mapSelect) return;
    mapSelect.addEventListener('change', () => {
      window.location.href = `/?map=${encodeURIComponent(mapSelect.value)}`;
    });
  }

  function bindRefreshButton() {
    const btn = document.getElementById('refreshBtn');
    const icon = document.getElementById('refreshIcon');
    btn.addEventListener('click', () => {
      icon.classList.remove('refresh-spin');
      // eslint-disable-next-line no-unused-expressions
      icon.offsetWidth; // 리플로우를 강제해서 연속 클릭에도 애니메이션이 다시 시작되게 한다.
      icon.classList.add('refresh-spin');
      refresh(true);
    });
  }

  function bindSidebarNav() {
    const items = document.querySelectorAll('.nav-item[data-section]');
    items.forEach((item) => {
      item.addEventListener('click', () => {
        items.forEach((i) => i.classList.remove('active'));
        item.classList.add('active');
        document.querySelectorAll('.main-content > .panel[id^="section-"]').forEach((panel) => {
          panel.style.display = panel.id === `section-${item.dataset.section}` ? '' : 'none';
        });
        closeMobileSidebar();
      });
    });
  }

  function openMobileSidebar() {
    document.getElementById('sidebar').classList.add('active');
    document.getElementById('modalBackdrop').classList.add('active');
  }

  function closeMobileSidebar() {
    document.getElementById('sidebar').classList.remove('active');
    document.getElementById('modalBackdrop').classList.remove('active');
  }

  function bindMobileMenu() {
    document.getElementById('menuToggle').addEventListener('click', openMobileSidebar);
    document.getElementById('closeMenu').addEventListener('click', closeMobileSidebar);
    document.getElementById('modalBackdrop').addEventListener('click', closeMobileSidebar);
  }

  function bindProfileName() {
    const nameEl = document.getElementById('profileName');
    if (!nameEl) return;
    nameEl.addEventListener('click', async () => {
      const { value: username } = await Swal.fire({
        title: '마크 닉네임 수정',
        input: 'text',
        inputValue: nameEl.textContent.trim(),
        inputPlaceholder: '마인크래프트 닉네임',
        showCancelButton: true,
        confirmButtonText: '저장',
        cancelButtonText: '취소',
        ...StoreTrackerRender.swalTheme(),
        inputValidator: (value) => (!value || !value.trim() ? '닉네임을 입력해주세요.' : undefined),
      });

      if (!username || username.trim() === nameEl.textContent.trim()) {
        return;
      }

      try {
        const result = await StoreTrackerApi.updateProfileUsername(username.trim());
        nameEl.textContent = result.username;
        document.body.dataset.username = result.username;
        StoreTrackerRender.toast('success', '닉네임이 저장됐습니다');
      } catch (err) {
        Swal.fire({
          icon: 'error',
          title: '저장 실패',
          text: err.message,
          ...StoreTrackerRender.swalTheme(),
        });
      }
    });
  }

  function bindDetailedLogToggle() {
    const checkbox = document.getElementById('detailedLogToggle');
    if (!checkbox) return;
    checkbox.addEventListener('change', () => {
      document.querySelectorAll('.admin-col').forEach((el) => {
        el.classList.toggle('d-none', !checkbox.checked);
      });
    });
  }

  async function handleToggleClick(key) {
    try {
      const updated = await StoreTrackerApi.toggleFeature(currentMap, key);
      StoreTrackerRender.featureToggles(updated, handleToggleClick);
      StoreTrackerRender.toast('success', '적용됨 (모든 유저에게 곧 반영)');
    } catch (err) {
      StoreTrackerRender.toast('error', '전환 실패');
      loadFeatureToggles();
    }
  }

  async function loadFeatureToggles() {
    if (!isDeveloper || !currentMap) return;
    try {
      const toggles = await StoreTrackerApi.fetchFeatureToggles(currentMap);
      StoreTrackerRender.featureToggles(toggles, handleToggleClick);
    } catch (err) {
      const container = document.getElementById('featureToggleList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function handleChestStrictClick() {
    try {
      const updated = await StoreTrackerApi.toggleChestStrict(currentMap);
      StoreTrackerRender.chestStrictToggle(updated, handleChestStrictClick);
      StoreTrackerRender.toast('success', '적용됨');
    } catch (err) {
      StoreTrackerRender.toast('error', '전환 실패');
      loadChestStrict();
    }
  }

  async function loadChestStrict() {
    if (!isDeveloper || !currentMap) return;
    try {
      const data = await StoreTrackerApi.fetchChestStrict(currentMap);
      StoreTrackerRender.chestStrictToggle(data, handleChestStrictClick);
    } catch (err) {
      const container = document.getElementById('chestStrictContainer');
      if (container) container.innerHTML = '<div class="text-danger small">등록된 상자가 없습니다</div>';
    }
  }

  async function handleAddMember() {
    const { value: username } = await Swal.fire({
      title: '멤버 추가',
      input: 'text',
      inputPlaceholder: '마인크래프트 닉네임',
      showCancelButton: true,
      confirmButtonText: '추가',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
      inputValidator: (value) => (!value || !value.trim() ? '닉네임을 입력해주세요.' : undefined),
    });

    if (!username) return;

    try {
      await StoreTrackerApi.addMember(username.trim());
      StoreTrackerRender.toast('success', '멤버가 추가됐습니다');
      loadMembers();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '추가 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function handleDeleteMember(memberId, username) {
    const result = await Swal.fire({
      title: `${username} 삭제할까요?`,
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: '삭제',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
    });
    if (!result.isConfirmed) return;

    try {
      await StoreTrackerApi.deleteMember(memberId);
      StoreTrackerRender.toast('success', '멤버가 삭제됐습니다');
      loadMembers();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '삭제 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function loadMembers() {
    if (!isDeveloper) return;
    try {
      const members = await StoreTrackerApi.fetchMembers();
      StoreTrackerRender.members(
        members, document.body.dataset.username, isSuperAdmin, handleDeleteMember,
        async (memberId) => {
          try {
            await StoreTrackerApi.toggleMemberDeveloper(memberId);
            StoreTrackerRender.toast('success', '적용됨');
          } catch (err) {
            StoreTrackerRender.toast('error', '전환 실패');
          }
          loadMembers();
        },
      );
    } catch (err) {
      const container = document.getElementById('memberList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function loadChestLogs() {
    if (!isDeveloper || !currentMap) return;
    try {
      const logs = await StoreTrackerApi.fetchChestLogs(currentMap);
      StoreTrackerRender.chestLogs(logs);
    } catch (err) {
      const container = document.getElementById('chestLogList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function handleAddPublicItem() {
    const { value: form } = await Swal.fire({
      title: '공용템 등록',
      html: `
        <input id="swalItemId" class="swal2-input" placeholder="아이템 ID (예: minecraft:andesite)">
        <input id="swalDisplayName" class="swal2-input" placeholder="표시 이름 (비우면 ID 그대로)">
      `,
      showCancelButton: true,
      confirmButtonText: '등록',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
      preConfirm: () => {
        const itemId = document.getElementById('swalItemId').value.trim();
        if (!itemId) {
          Swal.showValidationMessage('아이템 ID를 입력해주세요.');
          return false;
        }
        return { itemId, displayName: document.getElementById('swalDisplayName').value.trim() };
      },
    });

    if (!form) return;

    try {
      await StoreTrackerApi.addPublicItem(currentMap, form.itemId, form.displayName);
      StoreTrackerRender.toast('success', '공용템이 등록됐습니다');
      loadPublicItems();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '등록 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function handleRemovePublicItem(itemId) {
    const result = await Swal.fire({
      title: '공용템 목록에서 뺄까요?',
      text: '재고/보유 데이터는 그대로 남고, 캡처 목록에서만 빠집니다.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: '빼기',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
    });
    if (!result.isConfirmed) return;

    try {
      await StoreTrackerApi.removePublicItem(currentMap, itemId);
      StoreTrackerRender.toast('success', '공용템 목록에서 뺐습니다');
      loadPublicItems();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '처리 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function loadPublicItems() {
    if (!isDeveloper || !currentMap) return;
    try {
      const items = await StoreTrackerApi.fetchPublicItems(currentMap);
      StoreTrackerRender.publicItems(items, handleRemovePublicItem);
    } catch (err) {
      const container = document.getElementById('publicItemList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  function escapeText(value) {
    return String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  async function loadUnassignedReturns() {
    const container = document.getElementById('unassignedReturnList');
    if (!isDeveloper || !currentMap || !container) return;
    try {
      const [returnsRes, membersRes] = await Promise.all([
        fetch(`/api/admin/unassigned-returns?map=${encodeURIComponent(currentMap)}`),
        fetch('/api/admin/members'),
      ]);
      const returns = await returnsRes.json();
      const members = await membersRes.json();
      if (!returns.length) {
        container.innerHTML = '<div class="text-secondary small">미귀속 반납이 없습니다.</div>';
        return;
      }
      const options = members.map((m) => `<option value="${escapeText(m.minecraft_username)}">${escapeText(m.minecraft_username)}</option>`).join('');
      container.innerHTML = returns.map((r) => `
        <div class="d-flex align-items-center justify-content-between panel p-2 px-3 gap-2 flex-wrap">
          <div>
            <span>${escapeText(r.display_name)} x${r.count}</span>
            <div class="text-secondary small">넣은 사람: ${escapeText(r.depositor)} · ${escapeText(r.created_at.slice(0, 16).replace('T', ' '))}</div>
          </div>
          <div class="d-flex gap-2 align-items-center">
            <select class="form-select form-select-sm" style="width:auto;" data-return-select="${r.id}">
              <option value="">몫 주인 선택</option>${options}
            </select>
            <button class="btn btn-sm btn-accent unassigned-assign-btn" data-return-id="${r.id}">귀속</button>
          </div>
        </div>`).join('');
      container.querySelectorAll('.unassigned-assign-btn').forEach((btn) => {
        btn.addEventListener('click', () => handleAssignReturn(btn.dataset.returnId));
      });
    } catch (err) {
      container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function handleAssignReturn(returnId) {
    const select = document.querySelector(`[data-return-select="${returnId}"]`);
    const username = select ? select.value : '';
    if (!username) {
      Swal.fire({ icon: 'warning', text: '몫 주인을 먼저 고르세요.' });
      return;
    }
    const res = await fetch(`/api/admin/unassigned-returns/${returnId}/assign`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username }),
    });
    const body = await res.json();
    if (!res.ok) {
      Swal.fire({ icon: 'error', text: body.error || '귀속 실패' });
      return;
    }
    loadUnassignedReturns();
    refresh(false);
  }

  function bindAddPublicItemButton() {
    const btn = document.getElementById('addPublicItemBtn');
    if (!btn) return;
    btn.addEventListener('click', handleAddPublicItem);
  }

  async function loadLoginLogs() {
    if (!isDeveloper) return;
    try {
      const logs = await StoreTrackerApi.fetchLoginLogs();
      StoreTrackerRender.loginLogs(logs);
    } catch (err) {
      const container = document.getElementById('loginLogList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function handleApproveApplication(applicationId) {
    try {
      await StoreTrackerApi.approveApplication(applicationId);
      StoreTrackerRender.toast('success', '승인됐습니다');
      loadApplications();
      loadMembers();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '승인 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function handleRejectApplication(applicationId) {
    const result = await Swal.fire({
      title: '신청을 거절할까요?',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: '거절',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
    });
    if (!result.isConfirmed) return;

    try {
      await StoreTrackerApi.rejectApplication(applicationId);
      StoreTrackerRender.toast('success', '거절했습니다');
      loadApplications();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '거절 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function loadApplications() {
    if (!isDeveloper) return;
    try {
      const applications = await StoreTrackerApi.fetchApplications();
      StoreTrackerRender.applications(applications, handleApproveApplication, handleRejectApplication);
    } catch (err) {
      const container = document.getElementById('applicationList');
      if (container) container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  function bindAddMemberButton() {
    const btn = document.getElementById('addMemberBtn');
    if (!btn) return;
    btn.addEventListener('click', handleAddMember);
  }

  async function loadAlbaPendingRecords() {
    const body = document.getElementById('albaPendingRecordsBody');
    if (!body || !currentMap) return;
    try {
      const [list, rates] = await Promise.all([
        StoreTrackerApi.fetchAlbaPendingRecords(currentMap),
        StoreTrackerApi.fetchAlbaWageRates(currentMap),
      ]);
      const rateMap = {};
      rates.forEach((r) => { rateMap[r.job] = r.unit_price; });
      StoreTrackerRender.albaPendingRecords(list, rateMap);
      body.querySelectorAll('.pending-record-save-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const row = btn.closest('tr');
          const boxCount = Number(row.querySelector('[data-field="box_count"]').value);
          const unitPrice = Number(row.querySelector('[data-field="unit_price"]').value);
          if (!Number.isFinite(boxCount) || boxCount < 0) {
            StoreTrackerRender.toast('error', '상자 수를 입력해주세요');
            return;
          }
          try {
            await StoreTrackerApi.updateAlbaRecord(btn.dataset.hireId, { box_count: boxCount, unit_price: unitPrice });
            StoreTrackerRender.toast('success', '기록됐습니다');
            loadAlbaPendingRecords();
            loadAlbaPeople();
          } catch (err) {
            Swal.fire({ icon: 'error', title: '저장 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
          }
        });
      });
      body.querySelectorAll('.pending-record-delete-btn').forEach((btn) => {
        btn.addEventListener('click', () => handleDeleteAlbaRecord(btn.dataset.hireId, loadAlbaPendingRecords));
      });
    } catch (err) {
      body.innerHTML = '<tr><td colspan="7" class="text-danger text-center py-3">불러오기 실패</td></tr>';
    }
  }

  async function handleDeleteAlbaRecord(hireId, onDone) {
    const result = await Swal.fire({
      title: '이 알바 기록을 삭제할까요?',
      text: '고용/해고/상자 기록이 통째로 지워지고 되돌릴 수 없습니다.',
      icon: 'warning',
      showCancelButton: true,
      confirmButtonText: '삭제',
      cancelButtonText: '취소',
      ...StoreTrackerRender.swalTheme(),
    });
    if (!result.isConfirmed) return;

    try {
      await StoreTrackerApi.deleteAlbaRecord(hireId);
      StoreTrackerRender.toast('success', '삭제됐습니다');
      loadAlbaPeople();
      if (onDone) onDone();
    } catch (err) {
      Swal.fire({ icon: 'error', title: '삭제 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
    }
  }

  async function loadAlbaWageRates() {
    const container = document.getElementById('albaWageRateList');
    if (!container || !currentMap) return;
    try {
      const rates = await StoreTrackerApi.fetchAlbaWageRates(currentMap);
      StoreTrackerRender.albaWageRates(rates);
      container.querySelectorAll('.wage-save-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const job = btn.dataset.wageJob;
          const input = container.querySelector(`input[data-wage-job="${job}"]`);
          const value = Number(input.value);
          if (!Number.isFinite(value) || value < 0) {
            StoreTrackerRender.toast('error', '0 이상의 숫자를 입력해주세요');
            return;
          }
          try {
            await StoreTrackerApi.setAlbaWageRate(currentMap, job, value);
            StoreTrackerRender.toast('success', '단가가 저장됐습니다');
          } catch (err) {
            Swal.fire({ icon: 'error', title: '저장 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
          }
        });
      });
    } catch (err) {
      container.innerHTML = '<div class="text-danger small">불러오기 실패</div>';
    }
  }

  async function loadAlbaPeople() {
    const body = document.getElementById('albaPeopleBody');
    if (!body || !currentMap) return;
    try {
      const people = await StoreTrackerApi.fetchAlbaPeople(currentMap);
      StoreTrackerRender.albaPeople(people);
      body.querySelectorAll('.alba-person-row').forEach((row) => {
        row.addEventListener('click', () => openAlbaPersonDetail(row.dataset.player));
      });
    } catch (err) {
      body.innerHTML = '<tr><td colspan="6" class="text-danger text-center py-3">불러오기 실패</td></tr>';
    }
  }

  async function openAlbaPersonDetail(playerName) {
    let detail;
    try {
      detail = await StoreTrackerApi.fetchAlbaPersonDetail(currentMap, playerName);
    } catch (err) {
      StoreTrackerRender.toast('error', '불러오기 실패');
      return;
    }

    async function refreshModal() {
      detail = await StoreTrackerApi.fetchAlbaPersonDetail(currentMap, playerName);
      const container = Swal.getHtmlContainer();
      if (container) {
        container.innerHTML = StoreTrackerRender.albaPersonDetailHtml(detail);
        bindDetailButtons();
      }
    }

    function bindDetailButtons() {
      document.querySelectorAll('.note-delete-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          try {
            await StoreTrackerApi.deleteAlbaPersonNote(Number(btn.dataset.noteId));
            await refreshModal();
          } catch (err) {
            StoreTrackerRender.toast('error', '삭제 실패');
          }
        });
      });
      document.querySelectorAll('.hire-edit-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const { value: form } = await Swal.fire({
            title: '상자 수 / 단가 수정',
            html: `
              <input id="swalBoxCount" type="number" min="0" step="1" class="swal2-input" placeholder="상자 수" value="${btn.dataset.boxCount}">
              <input id="swalUnitPrice" type="number" min="0" step="1" class="swal2-input" placeholder="단가(원/상자)" value="${btn.dataset.unitPrice}">
            `,
            showCancelButton: true,
            confirmButtonText: '저장',
            cancelButtonText: '취소',
            ...StoreTrackerRender.swalTheme(),
            preConfirm: () => {
              const boxCount = Number(document.getElementById('swalBoxCount').value);
              const unitPrice = Number(document.getElementById('swalUnitPrice').value);
              if (!Number.isFinite(boxCount) || boxCount < 0) {
                Swal.showValidationMessage('상자 수를 입력해주세요.');
                return false;
              }
              if (!Number.isFinite(unitPrice) || unitPrice < 0) {
                Swal.showValidationMessage('단가를 입력해주세요.');
                return false;
              }
              return { boxCount, unitPrice };
            },
          });
          if (!form) return;
          try {
            await StoreTrackerApi.updateAlbaRecord(Number(btn.dataset.hireId), { box_count: form.boxCount, unit_price: form.unitPrice });
            StoreTrackerRender.toast('success', '수정됐습니다');
            loadAlbaPeople();
            // Swal.fire를 새로 열면(수정 입력창) 기존 상세 모달이 닫혀버려서 refreshModal로
            // "되돌아갈" 수 없다 - 상세 모달을 처음부터 다시 연다(최신 데이터로).
            openAlbaPersonDetail(playerName);
          } catch (err) {
            Swal.fire({ icon: 'error', title: '수정 실패', text: err.message, ...StoreTrackerRender.swalTheme() });
          }
        });
      });
      document.querySelectorAll('.hire-delete-btn').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const result = await Swal.fire({
            title: '이 고용 기록을 삭제할까요?',
            text: '고용/해고/상자 기록이 통째로 지워지고 되돌릴 수 없습니다.',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '삭제',
            cancelButtonText: '취소',
            ...StoreTrackerRender.swalTheme(),
          });
          if (!result.isConfirmed) return;
          try {
            await StoreTrackerApi.deleteAlbaRecord(Number(btn.dataset.hireId));
            loadAlbaPeople();
            // 삭제 확인 Swal이 상세 모달을 닫아버려서 refreshModal로 되돌아갈 수 없다 -
            // 처음부터 다시 연다.
            openAlbaPersonDetail(playerName);
          } catch (err) {
            StoreTrackerRender.toast('error', '삭제 실패');
          }
        });
      });
    }

    Swal.fire({
      title: playerName,
      html: StoreTrackerRender.albaPersonDetailHtml(detail),
      width: 480,
      showCancelButton: true,
      confirmButtonText: '코멘트 추가',
      cancelButtonText: '닫기',
      ...StoreTrackerRender.swalTheme(),
      didOpen: () => {
        bindDetailButtons();
      },
      preConfirm: async () => {
        const input = document.getElementById('personNoteInput');
        const comment = input ? input.value.trim() : '';
        if (!comment) {
          return false;
        }
        try {
          await StoreTrackerApi.addAlbaPersonNote(currentMap, playerName, comment);
        } catch (err) {
          Swal.showValidationMessage(err.message);
          return false;
        }
        return true;
      },
    }).then((result) => {
      if (result.isConfirmed) {
        StoreTrackerRender.toast('success', '코멘트가 추가됐습니다');
        loadAlbaPeople();
      }
    });
  }

  function bindLogout() {
    document.getElementById('logoutLink').addEventListener('click', (ev) => {
      ev.preventDefault();
      Swal.fire({
        title: '로그아웃 하시겠어요?',
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: '로그아웃',
        cancelButtonText: '취소',
        ...StoreTrackerRender.swalTheme(),
      }).then((result) => {
        if (result.isConfirmed) {
          window.location.href = '/logout';
        }
      });
    });
  }

  bindMapSelect();
  bindRefreshButton();
  bindSidebarNav();
  bindMobileMenu();
  bindProfileName();
  bindDetailedLogToggle();
  bindEventRowClicks();
  bindAddMemberButton();
  const exportBtn = document.getElementById('exportEventsBtn');
  if (exportBtn) {
    exportBtn.href = `/api/admin/events/export?map=${encodeURIComponent(currentMap)}`;
  }

  bindAddPublicItemButton();
  bindManualTransferButtons();
  bindLogFilter();
  bindExportRangeButton();
  bindAlbaStatusActions();
  bindAlbaHireForm();
  bindClearUnassignedReturnsButton();
  bindLogout();
  refresh(false);
  loadFeatureToggles();
  loadChestStrict();
  loadMembers();
  loadPublicItems();
  loadUnassignedReturns();
  loadChestLogs();
  loadLoginLogs();
  loadApplications();
  loadAlbaPendingRecords();
  loadAlbaWageRates();
  loadAlbaPeople();
  setInterval(() => refresh(false), REFRESH_INTERVAL_MS);
})();
