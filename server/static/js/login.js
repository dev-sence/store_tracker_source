(function () {
  const params = new URLSearchParams(window.location.search);
  if (params.get('error') !== '1') {
    return;
  }

  Swal.fire({
    icon: 'error',
    title: '로그인 실패',
    text: '토큰이 올바르지 않습니다.',
    background: '#121821',
    color: '#e8edf2',
    confirmButtonColor: '#f1c40f',
  });

  params.delete('error');
  const query = params.toString();
  const cleanUrl = window.location.pathname + (query ? `?${query}` : '');
  window.history.replaceState({}, document.title, cleanUrl);
})();
