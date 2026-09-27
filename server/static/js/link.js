(function () {
  function swalTheme() {
    const isLight = document.documentElement.getAttribute('data-theme') === 'light';
    return isLight
      ? { background: '#ffffff', color: '#24292f', confirmButtonColor: '#d4a700' }
      : { background: '#161b22', color: '#c9d1d9', confirmButtonColor: '#d4a700' };
  }

  const params = new URLSearchParams(window.location.search);
  if (params.get('error') !== '1') {
    return;
  }

  Swal.fire({
    icon: 'error',
    title: '매칭 실패',
    text: '등록된 멤버 중에 그 닉네임을 찾지 못했습니다. 정확히 입력했는지 확인하거나 관리자에게 문의하세요.',
    ...swalTheme(),
  });

  params.delete('error');
  const query = params.toString();
  const cleanUrl = window.location.pathname + (query ? `?${query}` : '');
  window.history.replaceState({}, document.title, cleanUrl);
})();
