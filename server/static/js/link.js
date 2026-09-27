(function () {
  const params = new URLSearchParams(window.location.search);
  if (params.get('error') !== '1') {
    return;
  }

  Swal.fire({
    icon: 'error',
    title: '매칭 실패',
    text: '등록된 멤버 중에 그 닉네임을 찾지 못했습니다. 정확히 입력했는지 확인하거나 관리자에게 문의하세요.',
    background: '#121821',
    color: '#e8edf2',
    confirmButtonColor: '#f1c40f',
  });

  params.delete('error');
  const query = params.toString();
  const cleanUrl = window.location.pathname + (query ? `?${query}` : '');
  window.history.replaceState({}, document.title, cleanUrl);
})();
