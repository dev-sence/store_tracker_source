const StoreTrackerRender = {
  swalTheme() {
    const isLight = document.documentElement.getAttribute('data-theme') === 'light';
    return isLight
      ? { background: '#ffffff', color: '#24292f', confirmButtonColor: '#d4a700' }
      : { background: '#161b22', color: '#c9d1d9', confirmButtonColor: '#d4a700' };
  },

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

  events(list, isDeveloper) {
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

      const position = e.dimension ? `${e.dimension} (${e.pos_x}, ${e.pos_y}, ${e.pos_z})` : '-';
      const rowAttrs = isDeveloper ? `class="event-row" data-event-id="${e.id}" style="cursor:pointer;"` : '';

      return `<tr ${rowAttrs}>
        <td class="text-secondary small">${formatTime(e.received_at)}</td>
        <td>
          <img class="avatar-sm me-2" src="${avatar}" onerror="this.style.display='none'">
          ${escape(e.minecraft_username)}
        </td>
        <td>${escape(e.item_name)}</td>
        <td><span class="badge ${badgeClass}">${label}</span></td>
        <td class="text-end">${e.count}</td>
        <td class="text-secondary small">${escape(e.chest_label || '경유 상자')}</td>
        <td class="admin-col d-none text-secondary small">${escape(e.item_id)}</td>
        <td class="admin-col d-none text-secondary small">${escape(position)}</td>
        <td class="admin-col d-none text-secondary small">${escape(e.map_key || '-')}</td>
      </tr>`;
    }).join('');
  },

  inventory(items, isDeveloper, onEdit) {
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
      const editBtn = isDeveloper
        ? `<button class="btn btn-sm btn-outline-light py-0 px-1 ms-2 inventory-edit-btn"
             data-item-id="${escape(item.item_id)}" data-item-name="${escape(item.display_name)}"
             data-current="${item.stock}" title="재고 수동 수정">
             <span class="material-symbols-outlined" style="font-size:.9rem; vertical-align:-2px;">edit</span>
           </button>`
        : '';

      return `<tr>
        <td>${escape(item.display_name)}</td>
        <td class="text-end fw-semibold">${item.stock}${editBtn}</td>
        <td class="small">${holders}</td>
      </tr>`;
    }).join('');

    if (isDeveloper) {
      body.querySelectorAll('.inventory-edit-btn').forEach((btn) => {
        btn.addEventListener('click', () => onEdit(btn.dataset.itemId, btn.dataset.itemName, Number(btn.dataset.current)));
      });
    }
  },

  featureToggles(toggles, onToggle) {
    const labels = {
      label_overlay: '상자 제목 강조 오버레이',
      public_tag: '[공용템] 툴팁 표시',
      passthrough_tracking: '경유 상자 추적',
      chest_log: '상자 열림/닫힘 로그',
    };
    const container = document.getElementById('featureToggleList');
    container.innerHTML = Object.keys(labels).map((key) => {
      const on = !!toggles[key];
      return `<div class="d-flex align-items-center justify-content-between panel p-2 px-3">
        <span class="small">${labels[key]}</span>
        <div class="form-check form-switch mb-0">
          <input class="form-check-input" type="checkbox" role="switch" data-toggle-key="${key}" ${on ? 'checked' : ''}>
        </div>
      </div>`;
    }).join('');

    container.querySelectorAll('input[data-toggle-key]').forEach((input) => {
      input.addEventListener('change', () => onToggle(input.dataset.toggleKey));
    });
  },

  chestStrictToggle(data, onToggle) {
    const container = document.getElementById('chestStrictContainer');
    if (!container) return;
    // strict_mode=true면 공용템 외 아이템을 못 넣는다 - 스위치는 사용자가 물어본 그대로
    // "넣을 수 있음"을 의미하게 뒤집어서 보여준다 (헷갈리지 않게).
    const allowOthers = !data.strict_mode;
    container.innerHTML = `<div class="d-flex align-items-center justify-content-between panel p-2 px-3">
      <span class="small">공용템 외 아이템 넣기 ${this.escapeHtml(data.label ? `(${data.label})` : '')}</span>
      <div class="form-check form-switch mb-0">
        <input class="form-check-input" type="checkbox" role="switch" id="chestStrictSwitch" ${allowOthers ? 'checked' : ''}>
      </div>
    </div>`;
    document.getElementById('chestStrictSwitch').addEventListener('change', onToggle);
  },

  members(list, currentUsername, isSuperAdmin, onDelete, onToggleDeveloper) {
    const container = document.getElementById('memberList');
    const escape = this.escapeHtml;
    if (!list.length) {
      container.innerHTML = '<div class="text-secondary small">등록된 멤버가 없습니다.</div>';
      return;
    }

    container.innerHTML = list.map((m) => {
      const linked = m.discord_id
        ? '<span class="badge bg-success">연동됨</span>'
        : '<span class="badge bg-secondary">미연동</span>';
      const isSuperAdminRow = m.minecraft_username.toLowerCase() === 'sence1012';
      const isSelf = m.minecraft_username.toLowerCase() === currentUsername.toLowerCase();

      let devControl;
      if (isSuperAdminRow) {
        devControl = '<span class="badge" style="background:#d4a700;">최고관리자</span>';
      } else if (isSuperAdmin) {
        devControl = `<div class="form-check form-switch mb-0" title="개발자 지정/해제">
          <input class="form-check-input" type="checkbox" role="switch" data-dev-toggle-id="${m.id}" ${m.is_developer ? 'checked' : ''}>
        </div>`;
      } else if (m.is_developer) {
        devControl = '<span class="badge bg-info text-dark">개발자</span>';
      } else {
        devControl = '';
      }

      const deleteBtn = isSelf
        ? ''
        : `<button class="btn btn-sm btn-outline-danger py-0 px-2 member-delete-btn"
             data-member-id="${m.id}" data-username="${escape(m.minecraft_username)}">
             <span class="material-symbols-outlined" style="font-size:.9rem; vertical-align:-2px;">delete</span>
           </button>`;

      return `<div class="d-flex align-items-center justify-content-between panel p-2 px-3">
        <div class="d-flex align-items-center gap-2">
          <span>${escape(m.minecraft_username)}</span>
          ${linked}
          ${devControl}
        </div>
        ${deleteBtn}
      </div>`;
    }).join('');

    container.querySelectorAll('.member-delete-btn').forEach((btn) => {
      btn.addEventListener('click', () => onDelete(btn.dataset.memberId, btn.dataset.username));
    });
    container.querySelectorAll('input[data-dev-toggle-id]').forEach((input) => {
      input.addEventListener('change', () => onToggleDeveloper(input.dataset.devToggleId));
    });
  },

  chestLogs(list) {
    const container = document.getElementById('chestLogList');
    if (!container) return;
    const escape = this.escapeHtml;

    if (!list.length) {
      container.innerHTML = '<div class="text-secondary small">아직 기록된 상자 열림/닫힘 로그가 없습니다.</div>';
      return;
    }

    container.innerHTML = list.map((log) => {
      const firstLine = (log.result || '').split('\n')[0];
      return `<div class="panel p-2 px-3 chest-log-row" style="cursor:pointer;" data-log='${escape(JSON.stringify(log))}'>
        <div class="d-flex justify-content-between">
          <span class="small">${escape(log.minecraft_username || '(알 수 없음)')}</span>
          <span class="text-secondary small">${this.formatTime(log.created_at)}</span>
        </div>
        <div class="text-secondary small">${escape(firstLine)}</div>
      </div>`;
    }).join('');

    container.querySelectorAll('.chest-log-row').forEach((row) => {
      row.addEventListener('click', () => {
        this.requestLogDetail(JSON.parse(row.dataset.log));
      });
    });
  },

  requestLogDetail(log) {
    const escape = this.escapeHtml;
    let payloadPretty = '(없음)';
    if (log.payload) {
      try {
        payloadPretty = JSON.stringify(JSON.parse(log.payload), null, 2);
      } catch (e) {
        payloadPretty = log.payload;
      }
    }

    const html = `
      <div class="text-start small">
        <div class="mb-2"><span class="text-secondary">엔드포인트</span><br><code>${escape(log.endpoint)}</code></div>
        <div class="mb-2"><span class="text-secondary">닉네임</span><br>${escape(log.minecraft_username || '(알 수 없음)')}</div>
        <div class="mb-2"><span class="text-secondary">아이피</span><br>${escape(log.ip || '(알 수 없음)')}</div>
        <div class="mb-2"><span class="text-secondary">시각</span><br>${this.formatTime(log.created_at)}</div>
        <div class="mb-2"><span class="text-secondary">결과</span><br>${escape(log.result)}</div>
        <div><span class="text-secondary">요청 내용(복호화됨)</span>
          <pre class="small p-2 mt-1" style="background:var(--hover-bg); border-radius:8px; max-height:260px; overflow:auto; white-space:pre-wrap; word-break:break-all;">${escape(payloadPretty)}</pre>
        </div>
      </div>`;

    Swal.fire({
      title: '상세 통신 로그',
      html,
      width: 560,
      confirmButtonText: '닫기',
      ...this.swalTheme(),
    });
  },

  toast(icon, title) {
    Swal.fire({
      toast: true,
      position: 'top-end',
      icon,
      title,
      showConfirmButton: false,
      timer: icon === 'error' ? 1800 : 1200,
      ...this.swalTheme(),
    });
  },
};
