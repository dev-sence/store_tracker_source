(function () {
  const STORAGE_KEY = 'sence_theme';

  function currentTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.setAttribute('data-bs-theme', theme);
    const icon = document.getElementById('themeToggleIcon');
    if (icon) {
      icon.className = theme === 'light' ? 'bi bi-sun-fill' : 'bi bi-moon-stars-fill';
    }
  }

  function toggleTheme() {
    const next = currentTheme() === 'light' ? 'dark' : 'light';
    applyTheme(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch (e) { /* 저장 안 돼도 이번 페이지 보기엔 지장 없음 */ }
  }

  document.addEventListener('DOMContentLoaded', () => {
    applyTheme(currentTheme());
    const btn = document.getElementById('themeToggleBtn');
    if (btn) {
      btn.addEventListener('click', toggleTheme);
    }
  });
})();
