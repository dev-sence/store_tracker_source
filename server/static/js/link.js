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
    title: '입력 오류',
    text: '마인크래프트 닉네임을 입력해주세요.',
    ...swalTheme(),
  });

  params.delete('error');
  const query = params.toString();
  const cleanUrl = window.location.pathname + (query ? `?${query}` : '');
  window.history.replaceState({}, document.title, cleanUrl);
})();
