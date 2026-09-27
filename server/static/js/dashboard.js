(function () {
  const REFRESH_INTERVAL_MS = 15000;
  const currentMap = document.body.dataset.map || '';

  async function refresh(manual) {
    try {
      const data = await StoreTrackerApi.fetchDashboardData(currentMap);
      StoreTrackerRender.stats(data);
      StoreTrackerRender.events(data.events);
      StoreTrackerRender.inventory(data.inventory);
      if (manual) {
        StoreTrackerRender.toast('success', '새로고침 완료');
      }
    } catch (err) {
      if (manual) {
        StoreTrackerRender.toast('error', '새로고침 실패');
      }
    }
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
  bindLogout();
  refresh(false);
  setInterval(() => refresh(false), REFRESH_INTERVAL_MS);
})();
