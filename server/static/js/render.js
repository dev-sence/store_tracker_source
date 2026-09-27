const StoreTrackerRender = {
  escapeHtml(str) {
    return String(str ?? '').replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
  },

  formatTime(iso) {
    if (!iso) return '-';
    const normalized = iso.endsWith('Z') || iso.includes('+') ? iso : `${iso}Z`;
    const date = new Date(normalized);
    return date.toLocaleString('ko-KR', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  },

  stats(data) {
    document.getElementById('statMembers').textContent = data.stats.members;
    document.getElementById('statChests').textContent = data.stats.chests;
    document.getElementById('statItemTypes').textContent = data.stats.item_types;
    document.getElementById('statToday').textContent = data.stats.today_events;
  },

  events(list) {
    const body = document.getElementById('eventsBody');
    if (!list.length) {
      body.innerHTML = '<tr><td colspan="6" class="text-center text-secondary py-4">아직 기록된 이벤트가 없습니다.</td></tr>';
      return;
    }

    const escape = this.escapeHtml;
    const formatTime = this.formatTime;

    body.innerHTML = list.map((e) => {
      const isTake = e.action === 'TAKE';
      const badgeClass = isTake ? 'badge-take' : (e.action === 'DEPOSIT' ? 'badge-deposit' : 'bg-secondary');
      const label = isTake ? '출고' : (e.action === 'DEPOSIT' ? '입고' : e.action);
      const avatar = `https://mc-heads.net/avatar/${encodeURIComponent(e.minecraft_username)}/32`;

      return `<tr>
        <td class="text-secondary small">${formatTime(e.received_at)}</td>
        <td>
          <img class="avatar-sm me-2" src="${avatar}" onerror="this.style.display='none'">
          ${escape(e.minecraft_username)}
        </td>
        <td>${escape(e.item_name)}</td>
        <td><span class="badge ${badgeClass}">${label}</span></td>
        <td class="text-end">${e.count}</td>
        <td class="text-secondary small">${escape(e.chest_label || '경유 상자')}</td>
      </tr>`;
    }).join('');
  },

  inventory(items) {
    const body = document.getElementById('inventoryBody');
    if (!items.length) {
      body.innerHTML = '<tr><td colspan="3" class="text-center text-secondary py-4">등록된 공용템이 없습니다.</td></tr>';
      return;
    }

    const escape = this.escapeHtml;

    body.innerHTML = items.map((item) => {
      const holders = item.holders.length
        ? item.holders.map((h) => `${escape(h.username)} <span class="text-secondary">x${h.count}</span>`).join(', ')
        : '<span class="text-secondary">없음</span>';

      return `<tr>
        <td>${escape(item.display_name)}</td>
        <td class="text-end fw-semibold">${item.stock}</td>
        <td class="small">${holders}</td>
      </tr>`;
    }).join('');
  },

  toast(icon, title) {
    Swal.fire({
      toast: true,
      position: 'top-end',
      icon,
      title,
      showConfirmButton: false,
      timer: icon === 'error' ? 1800 : 1200,
      background: '#121821',
      color: '#e8edf2',
    });
  },
};
