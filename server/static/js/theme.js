(function () {
  const STORAGE_KEY = 'sence_theme';

  function currentTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    const icon = document.getElementById('themeToggleIcon');
    const text = document.getElementById('themeToggleText');
    if (icon) icon.textContent = theme === 'light' ? '☀️' : '🌙';
    if (text) text.textContent = theme === 'light' ? '라이트 모드' : '다크 모드';
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
