import logging
import secrets
from datetime import datetime, timezone
from functools import wraps

from flask import Blueprint, current_app, jsonify, redirect, render_template, request, session, url_for

import discord_backoff
import discord_oauth
from models import (
    ChestInventoryItem, Event, FeatureToggle, Member, PlayerItemLedger, PublicItemType, RequestLog,
    TrackedChest, db,
)

logger = logging.getLogger(__name__)

dashboard_bp = Blueprint("dashboard", __name__)

# 대시보드에서 로그 상세보기/기능 토글처럼 민감한 걸 만질 수 있는 사람 (마크 닉네임 기준, 소문자 비교).
ADMIN_USERNAMES = {"sence1012"}
TOGGLE_KEYS = ("label_overlay", "public_tag", "passthrough_tracking", "chest_log")


def _redirect_uri() -> str:
    configured = current_app.config.get("DISCORD_REDIRECT_URI")
    return configured or url_for("dashboard.discord_callback", _external=True)


def _current_member():
    discord_id = session.get("discord_id")
    if not discord_id:
        return None
    return Member.query.filter_by(discord_id=discord_id).first()


def _is_admin(member) -> bool:
    return member is not None and member.minecraft_username.lower() in ADMIN_USERNAMES


def admin_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        member = _current_member()
        if not _is_admin(member):
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
        logger.warning("discord callback: discord가 error 파라미터를 보냄: %s", request.args.get("error"))
        return redirect(url_for("dashboard.login", error="1"))

    state = request.args.get("state")
    expected_state = session.pop("oauth_state", None)
    code = request.args.get("code")
    if not code or not state or state != expected_state:
        logger.warning("discord callback: state 불일치 (code_present=%s, state=%r, expected=%r, session_keys=%s)",
                        bool(code), state, expected_state, list(session.keys()))
        return redirect(url_for("dashboard.login", error="1"))

    token_data = discord_oauth.exchange_code(
        current_app.config["DISCORD_CLIENT_ID"],
        current_app.config["DISCORD_CLIENT_SECRET"],
        _redirect_uri(),
        code,
    )
    if not token_data or "access_token" not in token_data:
        logger.warning("discord callback: 토큰 교환 실패, token_data=%r", token_data)
        error_code = "rate_limited" if discord_backoff.in_backoff() else "1"
        return redirect(url_for("dashboard.login", error=error_code))

    user = discord_oauth.fetch_oauth_user(token_data["access_token"])
    if not user or "id" not in user:
        logger.warning("discord callback: 유저 정보 조회 실패, user=%r", user)
        error_code = "rate_limited" if discord_backoff.in_backoff() else "1"
        return redirect(url_for("dashboard.login", error=error_code))

    discord_id = str(user["id"])
    session["discord_id"] = discord_id
    session["discord_username"] = user.get("global_name") or user.get("username") or "디스코드 사용자"
    session["discord_avatar"] = discord_oauth.avatar_url(user)
    session.permanent = True

    member = Member.query.filter_by(discord_id=discord_id).first()
    if member is not None:
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
            db.session.commit()
            return redirect(url_for("dashboard.index"))

    return redirect(url_for("dashboard.link_profile", guessed=guessed or ""))


@dashboard_bp.get("/link")
def link_profile():
    if "discord_id" not in session:
        return redirect(url_for("dashboard.login"))
    if _current_member() is not None:
        return redirect(url_for("dashboard.index"))
    return render_template(
        "link.html",
        guessed=request.args.get("guessed", ""),
        error=request.args.get("error"),
    )


@dashboard_bp.post("/link")
def link_profile_submit():
    discord_id = session.get("discord_id")
    if not discord_id:
        return redirect(url_for("dashboard.login"))

    username = (request.form.get("username") or "").strip()
    member = Member.query.filter(
        db.func.lower(Member.minecraft_username) == username.lower(),
        Member.discord_id.is_(None),
    ).first() if username else None

    if member is None:
        return redirect(url_for("dashboard.link_profile", error="1"))

    member.discord_id = discord_id
    db.session.commit()
    return redirect(url_for("dashboard.index"))


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
        is_admin=_is_admin(member),
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
@admin_required
def admin_get_feature_toggles():
    map_key = request.args.get("map", "")
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    toggle = FeatureToggle.query.filter_by(map_key=map_key).first()
    if toggle is None:
        return jsonify({k: True for k in TOGGLE_KEYS})
    return jsonify(toggle.to_dict())


@dashboard_bp.post("/api/admin/feature-toggles")
@admin_required
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


@dashboard_bp.get("/api/admin/request-logs/by-event/<int:event_id>")
@admin_required
def admin_request_log_for_event(event_id):
    """이 입출고 로그(Event)를 만든 실제 통신 한 건의 상세 내역 - 디스코드 개발자 로그 채널에
    찍히던 것과 같은 정보(엔드포인트/닉네임/아이피/결과/원본 요청)를 웹에서도 볼 수 있게 한다."""
    log = RequestLog.query.filter_by(event_id=event_id).order_by(RequestLog.id.desc()).first()
    if log is None:
        return jsonify({"error": "not found"}), 404
    return jsonify(log.to_dict())


@dashboard_bp.post("/api/admin/inventory/adjust")
@admin_required
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
