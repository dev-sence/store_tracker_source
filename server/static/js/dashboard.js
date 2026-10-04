(function () {
  const REFRESH_INTERVAL_MS = 15000;
  const currentMap = document.body.dataset.map || '';
  const isDeveloper = document.body.dataset.isDeveloper === 'true';
  const isSuperAdmin = document.body.dataset.isSuperAdmin === 'true';

  async function refresh(manual) {
    try {
      const data = await StoreTrackerApi.fetchDashboardData(currentMap);
      StoreTrackerRender.stats(data);
      StoreTrackerRender.events(data.events, isDeveloper);
      StoreTrackerRender.inventory(data.inventory, isDeveloper, handleInventoryEdit);
      StoreTrackerRender.manualTransferItemOptions(data.inventory);
      StoreTrackerRender.manualTransferLog(data.events.filter((e) => e.action === 'HOLD' || e.action === 'RELEASE'));
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
  bindAddPublicItemButton();
  bindManualTransferButtons();
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
  setInterval(() => refresh(false), REFRESH_INTERVAL_MS);
})();
