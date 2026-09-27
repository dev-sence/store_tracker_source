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
    document.getElementById('refreshBtn').addEventListener('click', () => refresh(true));
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
        background: '#121821',
        color: '#e8edf2',
        confirmButtonColor: '#f1c40f',
      }).then((result) => {
        if (result.isConfirmed) {
          window.location.href = '/logout';
        }
      });
    });
  }

  bindMapSelect();
  bindRefreshButton();
  bindLogout();
  refresh(false);
  setInterval(() => refresh(false), REFRESH_INTERVAL_MS);
})();
