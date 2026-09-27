(function () {
  const params = new URLSearchParams(window.location.search);
  const error = params.get('error');

  if (error === 'rate_limited') {
    Swal.fire({
      icon: 'warning',
      title: '잠시 후 다시 시도해주세요',
      text: '디스코드 쪽에서 요청이 일시적으로 제한되고 있습니다. 1~2분 후 다시 로그인해주세요.',
      background: '#121821',
      color: '#e8edf2',
      confirmButtonColor: '#f1c40f',
    });
  } else if (error === '1') {
    Swal.fire({
      icon: 'error',
      title: '로그인 실패',
      text: '디스코드 로그인에 실패했습니다. 다시 시도해주세요.',
      background: '#121821',
      color: '#e8edf2',
      confirmButtonColor: '#f1c40f',
    });
  } else {
    return;
  }

  params.delete('error');
  const query = params.toString();
  const cleanUrl = window.location.pathname + (query ? `?${query}` : '');
  window.history.replaceState({}, document.title, cleanUrl);
})();
