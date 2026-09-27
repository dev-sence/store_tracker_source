import json
import secrets
from datetime import datetime, timezone
from functools import wraps

from flask import Blueprint, current_app, jsonify, redirect, render_template, request, session, url_for

import discord_backoff
import discord_oauth
from models import (
    ChestInventoryItem, Event, FeatureToggle, Member, MemberApplication, PlayerItemLedger, PublicItemType,
    RequestLog, TrackedChest, db,
)

dashboard_bp = Blueprint("dashboard", __name__)

# 최고관리자 - 이 계정만 다른 멤버를 개발자로 지정/해제할 수 있다 (마크 닉네임 기준, 소문자 비교).
SUPER_ADMIN_USERNAME = "sence1012"
TOGGLE_KEYS = ("label_overlay", "public_tag", "passthrough_tracking", "chest_log")


def _redirect_uri() -> str:
    configured = current_app.config.get("DISCORD_REDIRECT_URI")
    return configured or url_for("dashboard.discord_callback", _external=True)


def _current_member():
    discord_id = session.get("discord_id")
    if not discord_id:
        return None
    return Member.query.filter_by(discord_id=discord_id).first()


def _log_login(username, result, payload=None):
    """디스코드 로그인 시도를 성공/실패 가리지 않고 전부 남긴다 - 로그인이 왜 안 되는지
    문의가 왔을 때 개발자 탭에서 바로 원인을 확인할 수 있게 하려는 용도."""
    db.session.add(RequestLog(
        endpoint="discord-login", minecraft_username=username, ip=request.remote_addr,
        result=result,
        payload=json.dumps(payload, ensure_ascii=False, default=str) if payload is not None else None,
    ))
    db.session.commit()


def _is_super_admin(member) -> bool:
    return member is not None and member.minecraft_username.lower() == SUPER_ADMIN_USERNAME


def _is_developer(member) -> bool:
    return member is not None and (_is_super_admin(member) or member.is_developer)


def developer_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        member = _current_member()
        if not _is_developer(member):
            return jsonify({"error": "unauthorized"}), 403
        return fn(*args, **kwargs)

    return wrapper


def super_admin_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        member = _current_member()
        if not _is_super_admin(member):
            return jsonify({"error": "unauthorized"}), 403
        return fn(*args, **kwargs)

    return wrapper


def login_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        # 매 요청마다 다시 확인해서, 관리자가 나중에 멤버를 빼면 바로 접근이 끊기게 한다.
        if _current_member() is None:
            return redirect(url_for("dashboard.login"))
        return fn(*args, **kwargs)

    return wrapper


@dashboard_bp.get("/login")
def login():
    if _current_member() is not None:
        return redirect(url_for("dashboard.index"))
    return render_template("login.html")


@dashboard_bp.get("/discord/login")
def discord_login():
    state = secrets.token_urlsafe(16)
    session["oauth_state"] = state
    authorize_url = discord_oauth.build_authorize_url(
        current_app.config["DISCORD_CLIENT_ID"], _redirect_uri(), state,
    )
    return redirect(authorize_url)


@dashboard_bp.get("/discord/callback")
def discord_callback():
    if request.args.get("error"):
        _log_login(None, f"실패: Discord가 error 파라미터를 반환함 ({request.args.get('error')})",
                   payload=dict(request.args))
        return redirect(url_for("dashboard.login", error="1"))

    state = request.args.get("state")
    expected_state = session.pop("oauth_state", None)
    code = request.args.get("code")
    if not code or not state or state != expected_state:
        _log_login(None, "실패: state 불일치 (CSRF 방지 실패 또는 세션 만료)",
                   payload={"code_present": bool(code), "state": state, "expected": expected_state})
        return redirect(url_for("dashboard.login", error="1"))

    token_data = discord_oauth.exchange_code(
        current_app.config["DISCORD_CLIENT_ID"],
        current_app.config["DISCORD_CLIENT_SECRET"],
        _redirect_uri(),
        code,
    )
    if not token_data or "access_token" not in token_data:
        error_code = "rate_limited" if discord_backoff.in_backoff() else "1"
        _log_login(None, f"실패: 토큰 교환 실패 ({error_code})", payload=token_data)
        return redirect(url_for("dashboard.login", error=error_code))

    user = discord_oauth.fetch_oauth_user(token_data["access_token"])
    if not user or "id" not in user:
        error_code = "rate_limited" if discord_backoff.in_backoff() else "1"
        _log_login(None, f"실패: 유저 정보 조회 실패 ({error_code})", payload=user)
        return redirect(url_for("dashboard.login", error=error_code))

    discord_id = str(user["id"])
    discord_username = user.get("global_name") or user.get("username") or "디스코드 사용자"
    session["discord_id"] = discord_id
    session["discord_username"] = discord_username
    session["discord_avatar"] = discord_oauth.avatar_url(user)
    session.permanent = True

    member = Member.query.filter_by(discord_id=discord_id).first()
    if member is not None:
        member.discord_username = discord_username
        db.session.commit()
        _log_login(member.minecraft_username, "성공: 기존 연동 계정 로그인",
                   payload={"discord_id": discord_id, "discord_username": discord_username})
        return redirect(url_for("dashboard.index"))

    # 아직 이 디스코드 계정과 연결된 멤버가 없다 - 서버 별명에서 마크 닉네임을 추정해본다.
    guessed = discord_oauth.guess_minecraft_username(
        current_app.config["DISCORD_BOT_TOKEN"], current_app.config["DISCORD_GUILD_ID"], discord_id,
    )
    if guessed:
        candidate = Member.query.filter(
            db.func.lower(Member.minecraft_username) == guessed.lower(),
            Member.discord_id.is_(None),
        ).first()
        if candidate is not None:
            candidate.discord_id = discord_id
            candidate.discord_username = discord_username
            db.session.commit()
            _log_login(candidate.minecraft_username, "성공: 별명 자동 매칭으로 최초 연동",
                       payload={"discord_id": discord_id, "discord_username": discord_username, "guessed": guessed})
            return redirect(url_for("dashboard.index"))

    _log_login(None, "미등록 - 닉네임 확인/가입 신청 페이지로 이동",
               payload={"discord_id": discord_id, "discord_username": discord_username, "guessed": guessed})
    return redirect(url_for("dashboard.link_profile", guessed=guessed or ""))


@dashboard_bp.get("/link")
def link_profile():
    discord_id = session.get("discord_id")
    if not discord_id:
        return redirect(url_for("dashboard.login"))
    if _current_member() is not None:
        return redirect(url_for("dashboard.index"))

    application = MemberApplication.query.filter_by(discord_id=discord_id).first()
    return render_template(
        "link.html",
        guessed=application.minecraft_username if application else request.args.get("guessed", ""),
        error=request.args.get("error"),
        pending=application is not None,
    )


@dashboard_bp.post("/link")
def link_profile_submit():
    discord_id = session.get("discord_id")
    if not discord_id:
        return redirect(url_for("dashboard.login"))

    username = (request.form.get("username") or "").strip()
    if not username:
        return redirect(url_for("dashboard.link_profile", error="1"))

    member = Member.query.filter(
        db.func.lower(Member.minecraft_username) == username.lower(),
        Member.discord_id.is_(None),
    ).first()

    if member is not None:
        member.discord_id = discord_id
        member.discord_username = session.get("discord_username")
        db.session.commit()
        _log_login(member.minecraft_username, "성공: 수동 입력으로 연동", payload={"username": username})
        return redirect(url_for("dashboard.index"))

    # 등록된 멤버와 매칭되지 않으면(아직 관리자가 등록 안 함) 바로 막지 않고 가입 신청을 올려서
    # 개발자가 나중에 검토/승인할 수 있게 한다. 같은 디스코드 계정이 재제출하면 신청 내용만 갱신한다.
    application = MemberApplication.query.filter_by(discord_id=discord_id).first()
    if application is None:
        application = MemberApplication(discord_id=discord_id)
        db.session.add(application)
    application.minecraft_username = username
    application.discord_username = session.get("discord_username")
    application.discord_avatar = session.get("discord_avatar")
    application.requested_at = datetime.now(timezone.utc)
    db.session.commit()

    _log_login(username, "가입 신청 접수/갱신", payload={"username": username, "discord_id": discord_id})
    return redirect(url_for("dashboard.link_profile"))


@dashboard_bp.get("/logout")
def logout():
    session.clear()
    return redirect(url_for("dashboard.login"))


@dashboard_bp.get("/")
@login_required
def index():
    member = _current_member()
    map_keys = sorted({
        row[0] for row in db.session.query(TrackedChest.map_key).distinct()
        if row[0]
    })
    selected_map = request.args.get("map") or (map_keys[0] if map_keys else "")
    return render_template(
        "dashboard.html",
        map_keys=map_keys,
        selected_map=selected_map,
        profile_name=member.minecraft_username,
        discord_username=session.get("discord_username"),
        discord_avatar=session.get("discord_avatar"),
        is_developer=_is_developer(member),
        is_super_admin=_is_super_admin(member),
    )


@dashboard_bp.post("/api/profile/username")
@login_required
def update_profile_username():
    member = _current_member()
    new_username = (request.get_json(silent=True) or {}).get("username", "").strip()
    if not new_username:
        return jsonify({"error": "username is required"}), 400
    if len(new_username) > 32:
        return jsonify({"error": "username too long"}), 400

    existing = Member.query.filter(
        db.func.lower(Member.minecraft_username) == new_username.lower(),
        Member.id != member.id,
    ).first()
    if existing is not None:
        return jsonify({"error": "이미 사용 중인 닉네임입니다"}), 409

    member.minecraft_username = new_username
    db.session.commit()
    return jsonify({"status": "ok", "username": member.minecraft_username})


def _dashboard_data(map_key: str):
    member_count = Member.query.count()
    chest_count = TrackedChest.query.filter_by(map_key=map_key).count() if map_key else 0
    item_type_count = PublicItemType.query.filter_by(map_key=map_key).count() if map_key else 0

    today_start = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    today_event_count = Event.query.filter(Event.received_at >= today_start).count()

    events_query = Event.query.order_by(Event.received_at.desc())
    if map_key:
        events_query = events_query.filter_by(map_key=map_key)
    events = [e.to_dict() for e in events_query.limit(200).all()]

    inventory_rows = ChestInventoryItem.query.filter_by(map_key=map_key).all() if map_key else []
    catalog = {c.item_id: c.display_name for c in PublicItemType.query.filter_by(map_key=map_key).all()} \
        if map_key else {}
    stock_by_item = {}
    for row in inventory_rows:
        stock_by_item[row.item_id] = stock_by_item.get(row.item_id, 0) + row.count

    ledger_rows = PlayerItemLedger.query.filter_by(map_key=map_key).filter(
        PlayerItemLedger.held_count > 0
    ).all() if map_key else []
    holders_by_item = {}
    for row in ledger_rows:
        holders_by_item.setdefault(row.item_id, []).append(
            {"username": row.minecraft_username, "count": row.held_count}
        )

    item_ids = sorted(set(stock_by_item) | set(holders_by_item) | set(catalog))
    inventory = []
    for item_id in item_ids:
        if item_id in catalog:
            display_name = catalog[item_id]
        elif "#" in item_id:
            display_name = item_id.split("#", 1)[1]
        else:
            display_name = item_id
        inventory.append({
            "item_id": item_id,
            "display_name": display_name,
            "stock": stock_by_item.get(item_id, 0),
            "holders": holders_by_item.get(item_id, []),
        })

    return {
        "stats": {
            "members": member_count,
            "chests": chest_count,
            "item_types": item_type_count,
            "today_events": today_event_count,
        },
        "events": events,
        "inventory": inventory,
    }


@dashboard_bp.get("/api/dashboard-data")
@login_required
def dashboard_data():
    map_key = request.args.get("map", "")
    return jsonify(_dashboard_data(map_key))


@dashboard_bp.get("/api/admin/feature-toggles")
@developer_required
def admin_get_feature_toggles():
    map_key = request.args.get("map", "")
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    toggle = FeatureToggle.query.filter_by(map_key=map_key).first()
    if toggle is None:
        return jsonify({k: True for k in TOGGLE_KEYS})
    return jsonify(toggle.to_dict())


@dashboard_bp.post("/api/admin/feature-toggles")
@developer_required
def admin_toggle_feature():
    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    key = data.get("key")
    if not map_key or key not in TOGGLE_KEYS:
        return jsonify({"error": "invalid request"}), 400

    toggle = FeatureToggle.query.filter_by(map_key=map_key).first()
    if toggle is None:
        # Column(default=True)는 flush 전까지 적용 안 돼서, 바로 아래 getattr가 None을 볼 수 있다
        # (not None == True라서 "뒤집기"가 항상 True로만 가버리는 버그가 됨) - 그래서 명시적으로 채운다.
        toggle = FeatureToggle(
            map_key=map_key, label_overlay=True, public_tag=True,
            passthrough_tracking=True, chest_log=True,
        )
        db.session.add(toggle)

    setattr(toggle, key, not getattr(toggle, key))
    db.session.commit()
    return jsonify(toggle.to_dict())


@dashboard_bp.get("/api/admin/chest-strict")
@developer_required
def admin_get_chest_strict():
    """등록된 상자의 "개인템 차단"(strict_mode) 상태 - 인게임 개발자 빌드의 상자 안 버튼과 같은 값이다."""
    map_key = request.args.get("map", "")
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "no registered chest for this map"}), 404
    return jsonify({"strict_mode": chest.strict_mode, "label": chest.label})


@dashboard_bp.post("/api/admin/chest-strict")
@developer_required
def admin_toggle_chest_strict():
    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    if not map_key:
        return jsonify({"error": "map_key is required"}), 400
    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "no registered chest for this map"}), 404
    chest.strict_mode = not chest.strict_mode
    db.session.commit()
    return jsonify({"strict_mode": chest.strict_mode, "label": chest.label})


@dashboard_bp.get("/api/admin/public-items")
@developer_required
def admin_list_public_items():
    map_key = request.args.get("map", "")
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    items = PublicItemType.query.filter_by(map_key=map_key).order_by(PublicItemType.display_name).all()
    return jsonify([i.to_dict() for i in items])


@dashboard_bp.post("/api/admin/public-items")
@developer_required
def admin_add_public_item():
    """인게임 "현재 아이템 캡처"(전체 재캡처)와 달리, 기존 목록은 그대로 두고 한 종류만 추가한다 -
    아직 상자에 한 번도 안 들어와 본 새 아이템을 미리 공용템으로 등록해두고 싶을 때 쓴다."""
    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    item_id = (data.get("item_id") or "").strip()
    display_name = (data.get("display_name") or "").strip() or item_id
    if not map_key or not item_id:
        return jsonify({"error": "map_key, item_id가 필요합니다"}), 400

    existing = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).first()
    if existing is not None:
        return jsonify({"error": "이미 등록된 아이템입니다"}), 409

    item = PublicItemType(map_key=map_key, item_id=item_id, display_name=display_name)
    db.session.add(item)
    db.session.commit()
    return jsonify(item.to_dict()), 201


@dashboard_bp.post("/api/admin/public-items/remove")
@developer_required
def admin_remove_public_item():
    """공용템 목록에서 한 종류만 뺀다. item_id에 '#'/':' 같은 문자가 섞여 있어서 URL 경로 대신
    바디로 받는다 (재고/보유 장부는 그대로 - 캡처 목록에서만 빠지고 기존 데이터는 안 건드림)."""
    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    item_id = data.get("item_id")
    if not map_key or not item_id:
        return jsonify({"error": "map_key, item_id가 필요합니다"}), 400

    deleted = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).delete()
    db.session.commit()
    if not deleted:
        return jsonify({"error": "not found"}), 404
    return jsonify({"status": "ok", "item_id": item_id})


@dashboard_bp.get("/api/admin/request-logs/by-event/<int:event_id>")
@developer_required
def admin_request_log_for_event(event_id):
    """이 입출고 로그(Event)를 만든 실제 통신 한 건의 상세 내역 - 디스코드 개발자 로그 채널에
    찍히던 것과 같은 정보(엔드포인트/닉네임/아이피/결과/원본 요청)를 웹에서도 볼 수 있게 한다."""
    log = RequestLog.query.filter_by(event_id=event_id).order_by(RequestLog.id.desc()).first()
    if log is None:
        return jsonify({"error": "not found"}), 404
    return jsonify(log.to_dict())


@dashboard_bp.get("/api/admin/chest-logs")
@developer_required
def admin_chest_logs():
    """상자를 열고 닫을 때마다 남는 전체 내용물 스냅샷 로그 (감사/디버깅용, 입출고 로그와는 별개)."""
    map_key = request.args.get("map", "")
    limit = min(int(request.args.get("limit", 100)), 300)
    rows = RequestLog.query.filter_by(endpoint="chest-log").order_by(RequestLog.id.desc()).limit(limit * 2).all()

    result = []
    for row in rows:
        if map_key and row.payload:
            try:
                payload = json.loads(row.payload)
            except (TypeError, ValueError):
                payload = {}
            if payload.get("map_key") and payload["map_key"] != map_key:
                continue
        result.append(row.to_dict())
        if len(result) >= limit:
            break

    return jsonify(result)


@dashboard_bp.get("/api/admin/login-logs")
@developer_required
def admin_login_logs():
    """디스코드 로그인 시도 기록 (성공/실패 무관) - 로그인이 안 된다는 문의가 왔을 때 원인 확인용."""
    limit = min(int(request.args.get("limit", 100)), 300)
    rows = RequestLog.query.filter_by(endpoint="discord-login").order_by(RequestLog.id.desc()).limit(limit).all()
    return jsonify([r.to_dict() for r in rows])


@dashboard_bp.get("/api/admin/applications")
@developer_required
def admin_list_applications():
    apps = MemberApplication.query.order_by(MemberApplication.requested_at.desc()).all()
    return jsonify([a.to_dict() for a in apps])


@dashboard_bp.post("/api/admin/applications/<int:application_id>/approve")
@developer_required
def admin_approve_application(application_id):
    application = MemberApplication.query.get(application_id)
    if application is None:
        return jsonify({"error": "not found"}), 404

    if Member.query.filter(
        db.func.lower(Member.minecraft_username) == application.minecraft_username.lower(),
    ).first():
        return jsonify({"error": "이미 등록된 닉네임입니다"}), 409

    admin_member = _current_member()
    member = Member(
        minecraft_username=application.minecraft_username,
        discord_id=application.discord_id,
        discord_username=application.discord_username,
        added_by=admin_member.minecraft_username,
    )
    db.session.add(member)
    db.session.delete(application)
    db.session.commit()
    return jsonify(member.to_dict())


@dashboard_bp.post("/api/admin/applications/<int:application_id>/reject")
@developer_required
def admin_reject_application(application_id):
    application = MemberApplication.query.get(application_id)
    if application is None:
        return jsonify({"error": "not found"}), 404

    db.session.delete(application)
    db.session.commit()
    return "", 204


@dashboard_bp.post("/api/admin/members/<int:member_id>/developer")
@super_admin_required
def admin_toggle_member_developer(member_id):
    """개발자 지정/해제 - 최고관리자(sence1012)만 다른 멤버를 개발자 탭에 들어올 수 있게 할 수 있다."""
    member = Member.query.get(member_id)
    if member is None:
        return jsonify({"error": "not found"}), 404
    if member.minecraft_username.lower() == SUPER_ADMIN_USERNAME:
        return jsonify({"error": "최고관리자는 대상이 될 수 없습니다"}), 400

    member.is_developer = not member.is_developer
    db.session.commit()
    return jsonify(member.to_dict())


@dashboard_bp.get("/api/admin/members")
@developer_required
def admin_list_members():
    members = Member.query.order_by(Member.added_at.desc()).all()
    return jsonify([m.to_dict() for m in members])


@dashboard_bp.post("/api/admin/members")
@developer_required
def admin_add_member():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    if not username:
        return jsonify({"error": "username is required"}), 400
    if len(username) > 32:
        return jsonify({"error": "username too long"}), 400

    if Member.query.filter(db.func.lower(Member.minecraft_username) == username.lower()).first():
        return jsonify({"error": "이미 등록된 닉네임입니다"}), 409

    admin_member = _current_member()
    member = Member(minecraft_username=username, added_by=admin_member.minecraft_username)
    db.session.add(member)
    db.session.commit()
    return jsonify(member.to_dict()), 201


@dashboard_bp.delete("/api/admin/members/<int:member_id>")
@developer_required
def admin_remove_member(member_id):
    member = Member.query.get(member_id)
    if member is None:
        return jsonify({"error": "not found"}), 404
    if member.id == _current_member().id:
        return jsonify({"error": "자기 자신은 삭제할 수 없습니다"}), 400

    db.session.delete(member)
    db.session.commit()
    return "", 204


@dashboard_bp.post("/api/admin/inventory/adjust")
@developer_required
def admin_adjust_inventory():
    """재고 수동 수정 - 실측치 동기화가 아직 못 따라잡았거나 뭔가 어긋났을 때 관리자가 직접 바로잡는다."""
    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    item_id = data.get("item_id")
    try:
        count = int(data.get("count"))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid count"}), 400
    if not map_key or not item_id or count < 0:
        return jsonify({"error": "invalid request"}), 400

    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "no registered chest for this map"}), 404

    row = ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=chest.dimension, x=chest.x, y=chest.y, z=chest.z, item_id=item_id,
    ).first()
    if row is None:
        catalog_entry = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).first()
        display_name = catalog_entry.display_name if catalog_entry else item_id
        row = ChestInventoryItem(
            map_key=map_key, dimension=chest.dimension, x=chest.x, y=chest.y, z=chest.z,
            item_id=item_id, display_name=display_name, count=count,
        )
        db.session.add(row)
    else:
        row.count = count
    db.session.commit()
    return jsonify({"status": "ok", "item_id": item_id, "count": row.count})


@dashboard_bp.post("/api/manual-transfer")
@login_required
def manual_transfer():
    """상자를 거치지 않고(=모드를 안 쓰는 사람이) 공용템을 가져가거나 반납한 경우를 위한
    본인 계정 셀프 기록. 실제 TAKE/DEPOSIT과 똑같이 재고와 보유 장부를 증감시키고 Event로도
    남기지만, 실제 상자 상호작용 없이 수동으로 입력됐다는 걸 구분하려고 action은
    HOLD(사용)/RELEASE(반납)를 쓴다. 로그인한 본인 계정으로만 기록할 수 있다 (username은 서버가
    세션에서 직접 채우고 클라이언트가 보낸 값은 쓰지 않는다 - 다른 사람 이름으로 조작 방지)."""
    username = _current_member().minecraft_username

    data = request.get_json(silent=True) or {}
    map_key = data.get("map_key")
    item_id = data.get("item_id")
    action = data.get("action")
    try:
        count = int(data.get("count"))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid count"}), 400

    if not map_key or not item_id or count <= 0 or action not in ("HOLD", "RELEASE"):
        return jsonify({"error": "invalid request"}), 400

    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return jsonify({"error": "no registered chest for this map"}), 404

    catalog_entry = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).first()
    display_name = catalog_entry.display_name if catalog_entry else item_id

    stock_row = ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=chest.dimension, x=chest.x, y=chest.y, z=chest.z, item_id=item_id,
    ).first()
    current_stock = stock_row.count if stock_row else 0
    ledger_row = PlayerItemLedger.query.filter_by(
        map_key=map_key, minecraft_username=username, item_id=item_id,
    ).first()
    current_held = ledger_row.held_count if ledger_row else 0

    if action == "HOLD" and count > current_stock:
        return jsonify({"error": f"재고({current_stock}개)보다 많이 뺄 수 없습니다"}), 400
    if action == "RELEASE" and count > current_held:
        return jsonify({"error": f"{username}님이 들고 있는 개수({current_held}개)보다 많이 반납할 수 없습니다"}), 400

    stock_delta = -count if action == "HOLD" else count
    ledger_delta = count if action == "HOLD" else -count

    if stock_row is None:
        stock_row = ChestInventoryItem(
            map_key=map_key, dimension=chest.dimension, x=chest.x, y=chest.y, z=chest.z,
            item_id=item_id, display_name=display_name, count=0,
        )
        db.session.add(stock_row)
    stock_row.count = max(0, stock_row.count + stock_delta)

    if ledger_row is None:
        ledger_row = PlayerItemLedger(map_key=map_key, minecraft_username=username, item_id=item_id, held_count=0)
        db.session.add(ledger_row)
    ledger_row.held_count = max(0, ledger_row.held_count + ledger_delta)

    event = Event(
        minecraft_username=username, item_id=item_id, item_name=display_name,
        action=action, count=count, occurred_at=datetime.now(timezone.utc),
        map_key=map_key, dimension=chest.dimension, pos_x=chest.x, pos_y=chest.y, pos_z=chest.z,
        chest_label=f"{chest.label} (수동)",
    )
    db.session.add(event)
    db.session.flush()

    db.session.add(RequestLog(
        endpoint="manual-transfer", minecraft_username=username, ip=request.remote_addr,
        result=f"{'수동 사용' if action == 'HOLD' else '수동 반납'}: {display_name} x{count} (본인 신고)",
        payload=json.dumps(data, ensure_ascii=False), event_id=event.id,
    ))
    db.session.commit()

    return jsonify(event.to_dict())
