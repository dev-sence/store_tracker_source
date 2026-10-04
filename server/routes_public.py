import json
import logging
import time
from datetime import datetime, timezone

from cryptography.exceptions import InvalidTag
from flask import Blueprint, current_app, jsonify, request

import discord_client
import inventory
from models import (
    ChestInventoryItem, DashboardMessage, Event, FeatureToggle, Member, PlayerItemLedger, PublicItemType,
    RequestLog, TrackedChest, db,
)

logger = logging.getLogger(__name__)

public_bp = Blueprint("public", __name__, url_prefix="/api")

VALID_ACTIONS = ("TAKE", "DEPOSIT", "HOLD", "RELEASE")
VALID_TOGGLE_KEYS = ("label_overlay", "public_tag", "passthrough_tracking", "chest_log")

# 상자 하나에서 아이템 여러 종류를 한꺼번에 넣고/빼면 이벤트마다 대시보드를 다시 그리게 되는데,
# 그때마다 디스코드에 PATCH를 날리면 채널당 요청 제한(5회/5초)에 금방 걸린다(Discord/Cloudflare 429).
# 그래서 이 간격 안에 다시 호출되면 이번 갱신은 건너뛴다 - DB는 이미 최신이니 다음 이벤트가 올 때
# (또는 다음에 상자를 여닫을 때) 자연스럽게 최신 상태로 따라잡는다.
_DASHBOARD_REFRESH_MIN_INTERVAL = 3.0
_last_dashboard_refresh: dict[str, float] = {}

# 같은 서버인데 클라이언트마다 다른 문자열로 저장된 주소를 하나의 맵으로 합친다
# (예: 상자 등록자의 마크 서버 목록엔 "playf.kr"로 저장돼 있었는데 실제 접속 주소는
# "playfarm.kr"라서, 그 계정만 계속 정상 작동하고 실제 주소로 접속하는 일반 유저들은
# 등록된 상자가 하나도 없는 것처럼 보였던 문제 - 데이터는 이미 playfarm.kr로 옮겼지만,
# 등록자가 자기 서버 목록 주소를 안 바꾸면 또 갈라질 수 있어서 아예 코드에서 합친다).
_MAP_KEY_ALIASES = {"playf.kr": "playfarm.kr"}


def _normalize_map_key(map_key):
    return _MAP_KEY_ALIASES.get(map_key, map_key)


def _parse_encrypted_body():
    body = request.get_json(silent=True) or {}
    payload = body.get("payload")
    if not payload:
        return None
    try:
        data = current_app.secure_channel.decrypt_json(payload)
    except (InvalidTag, ValueError):
        return None
    if isinstance(data, dict) and "map_key" in data:
        data["map_key"] = _normalize_map_key(data["map_key"])
    return data


def _parse_occurred_at(raw: str) -> datetime:
    return datetime.fromisoformat(raw.replace("Z", "+00:00"))


def _client_ip() -> str:
    return request.headers.get("X-Forwarded-For", request.remote_addr) or "(알 수 없음)"


def _dev_log(endpoint: str, username, result: str, payload=None, event_id=None):
    """모든 API 요청 시도(성공/실패 무관)를 디스코드 개발자 로그 채널 + DB(RequestLog) 양쪽에 남긴다.
    디스코드는 꺼져 있을 수도 있으니(DISCORD_POSTING_ENABLED), 웹 대시보드의 "상세 통신 로그" 기능은
    DB 쪽만 보고 동작한다."""
    ip = _client_ip()
    try:
        payload_json = json.dumps(payload, ensure_ascii=False, default=str) if payload is not None else None
    except TypeError:
        payload_json = str(payload)
    db.session.add(RequestLog(
        endpoint=endpoint, minecraft_username=username, ip=ip,
        result=result, payload=payload_json, event_id=event_id,
    ))
    db.session.commit()

    discord_client.post_dev_log(
        current_app.config["DISCORD_BOT_TOKEN"],
        current_app.config["DISCORD_DEV_LOG_CHANNEL_ID"],
        endpoint=endpoint,
        username=username,
        ip=ip,
        result=result,
        payload=payload,
    )


def _mark_mod_linked(member):
    now = datetime.now(timezone.utc)
    if member.mod_first_linked_at is None:
        member.mod_first_linked_at = now
    member.mod_last_linked_at = now


def _require_admin():
    return request.headers.get("X-Admin-Token", "") == current_app.config["ADMIN_TOKEN"]


def _parse_xyz(data):
    """x/y/z를 정수로 변환한다. 하나라도 형식이 잘못되면 None을 반환한다."""
    try:
        return int(data["x"]), int(data["y"]), int(data["z"])
    except (KeyError, TypeError, ValueError):
        return None


def _registered_chest_position(map_key):
    chest = TrackedChest.query.filter_by(map_key=map_key).first()
    if chest is None:
        return None
    return {"dimension": chest.dimension, "x": chest.x, "y": chest.y, "z": chest.z}


def _snapshot_mismatches(map_key, position, items):
    """상자 열림/닫힘 시점의 실측 수량과 재고 장부를 비교만 한다 (재고는 건드리지 않음).
    어긋난 항목 설명 목록을 반환한다 - 비어 있으면 일치."""
    dimension = position.get("dimension")
    observed = {}
    for item in items:
        if isinstance(item, dict) and item.get("item_id"):
            try:
                observed[item["item_id"]] = observed.get(item["item_id"], 0) + int(item.get("count", 0))
            except (TypeError, ValueError):
                continue

    stock_rows = ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=dimension, x=position.get("x"), y=position.get("y"), z=position.get("z"),
    ).all()
    expected = {row.item_id: row.count for row in stock_rows}

    mismatches = []
    for item_id in sorted(set(observed) | set(expected)):
        catalog = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).first()
        if catalog is None:
            continue
        real = observed.get(item_id, 0)
        book = expected.get(item_id, 0)
        if real != book:
            mismatches.append(f"{catalog.display_name}(실측 {real}/장부 {book})")
    return mismatches


def _refresh_dashboard(map_key):
    """재고(ChestInventoryItem)/보유 장부(PlayerItemLedger)를 합쳐서 디스코드에 한 개 메시지로
    계속 수정(edit)하며 보여준다. 실패해도 조용히 넘어간다 (핵심 기능이 아니라 보조 대시보드라서)."""
    bot_token = current_app.config["DISCORD_BOT_TOKEN"]
    channel_id = current_app.config["DISCORD_INVENTORY_CHANNEL_ID"]
    if not bot_token or not channel_id:
        return

    now = time.monotonic()
    last = _last_dashboard_refresh.get(map_key, 0.0)
    if now - last < _DASHBOARD_REFRESH_MIN_INTERVAL:
        return
    _last_dashboard_refresh[map_key] = now

    inventory_rows = ChestInventoryItem.query.filter_by(map_key=map_key).all()
    ledger_rows = PlayerItemLedger.query.filter_by(map_key=map_key).filter(
        PlayerItemLedger.held_count > 0
    ).all()
    catalog = {c.item_id: c.display_name for c in PublicItemType.query.filter_by(map_key=map_key).all()}

    stock_by_item = {}
    for row in inventory_rows:
        stock_by_item[row.item_id] = stock_by_item.get(row.item_id, 0) + row.count

    holders_by_item = {}
    for row in ledger_rows:
        holders_by_item.setdefault(row.item_id, []).append((row.minecraft_username, row.held_count))

    item_ids = sorted(set(stock_by_item) | set(holders_by_item) | set(catalog))

    if not item_ids:
        fields = [{"name": "공용템 없음", "value": "아직 캡처된 공용템이 없습니다.", "inline": False}]
    else:
        fields = []
        for item_id in item_ids:
            # 캡처 목록(catalog)에서 빠진 아이템이면 "바닐라ID#표시이름" 중 표시이름만 꺼내서 보여준다
            # (원본 키를 그대로 보여주면 지저분함). 그래도 장부/재고에 남아있는 한 계속 보여준다.
            if item_id in catalog:
                display_name = catalog[item_id]
            elif "#" in item_id:
                display_name = item_id.split("#", 1)[1] + " (캡처 목록에서 빠짐)"
            else:
                display_name = item_id
            stock = stock_by_item.get(item_id, 0)
            holders = holders_by_item.get(item_id, [])
            holders_text = ", ".join(f"{name} x{count}" for name, count in holders) if holders else "없음"
            fields.append({
                "name": f"{display_name} (재고 {stock}개)",
                "value": f"보유 중: {holders_text}",
                "inline": False,
            })

    # 디스코드 임베드 하나는 필드 25개까지만 허용하니, 넘치면 임베드를 여러 개로 나눈다
    # (메시지당 임베드는 최대 10개까지 가능 = 최대 250개 아이템까지 표시 가능).
    field_chunks = [fields[i:i + 25] for i in range(0, len(fields), 25)] or [fields]
    timestamp = datetime.now(timezone.utc).isoformat()
    embeds = []
    for i, chunk in enumerate(field_chunks[:10]):
        chunk_embed = {"color": 0xF1C40F, "fields": chunk, "timestamp": timestamp}
        if i == 0:
            chunk_embed["title"] = "📦 공용템 재고/보유 현황"
        if i == len(field_chunks) - 1:
            chunk_embed["footer"] = {"text": f"map={map_key} · 자동 갱신"}
        embeds.append(chunk_embed)

    dashboard = DashboardMessage.query.filter_by(map_key=map_key).first()
    existing_message_id = dashboard.message_id if dashboard else None
    if not dashboard:
        dashboard = DashboardMessage(map_key=map_key, channel_id=channel_id)
        db.session.add(dashboard)

    new_message_id = discord_client.post_or_edit(bot_token, channel_id, existing_message_id, embeds)
    if new_message_id:
        dashboard.message_id = new_message_id
        dashboard.channel_id = channel_id
    db.session.commit()


@public_bp.post("/check-member")
def check_member():
    data = _parse_encrypted_body()
    if data is None or "username" not in data:
        _dev_log("check-member", None, "실패: 잘못된 요청(복호화/필드 오류)")
        return jsonify({"error": "invalid payload"}), 400

    username = data["username"]
    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        _dev_log("check-member", username, "미등록 (404)", data)
        return jsonify({"status": "not_found"}), 404

    _mark_mod_linked(member)
    _dev_log("check-member", username, "인증됨 (200)", data)
    return jsonify({"status": "ok"}), 200


@public_bp.post("/log-event")
def log_event():
    data = _parse_encrypted_body()
    if data is None:
        _dev_log("log-event", None, "실패: 잘못된 요청(복호화 실패)")
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "item_id", "item_name", "action", "count", "occurred_at")
    if not all(field in data for field in required):
        _dev_log("log-event", data.get("username"), "실패: 필드 누락", data)
        return jsonify({"error": "missing fields"}), 400

    if data["action"] not in VALID_ACTIONS:
        _dev_log("log-event", data["username"], "실패: 잘못된 action", data)
        return jsonify({"error": "invalid action"}), 400

    username = data["username"]
    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        # 서버측 방어적 재검증: 클라이언트 게이트가 우회되어도 미등록 유저 이벤트는 저장/게시하지 않는다.
        _dev_log("log-event", username, "거부: 미등록 사용자 (404)", data)
        return jsonify({"status": "not_found"}), 404

    _mark_mod_linked(member)

    try:
        occurred_at = _parse_occurred_at(data["occurred_at"])
        count = int(data["count"])
    except (ValueError, TypeError):
        _dev_log("log-event", username, "실패: 필드 형식 오류", data)
        return jsonify({"error": "invalid fields"}), 400

    if count <= 0:
        _dev_log("log-event", username, "실패: count는 양수여야 함", data)
        return jsonify({"error": "count must be positive"}), 400

    position = data.get("position") or {}
    if not isinstance(position, dict):
        _dev_log("log-event", username, "실패: position 형식 오류", data)
        return jsonify({"error": "invalid position"}), 400

    event = Event(
        minecraft_username=username,
        item_id=str(data["item_id"]),
        item_name=str(data["item_name"]),
        action=data["action"],
        count=count,
        occurred_at=occurred_at,
        map_key=data.get("map_key"),
        dimension=position.get("dimension"),
        pos_x=position.get("x"),
        pos_y=position.get("y"),
        pos_z=position.get("z"),
        chest_label=data.get("chest_label"),
    )
    db.session.add(event)

    # 등록된 공용템 상자에서 일어난 입출고만 재고/보유 장부에 반영한다 (경유 상자는 장부와 무관한 감사 기록).
    anomaly = None
    if event.chest_label and event.action in ("TAKE", "DEPOSIT"):
        chest = {"dimension": position.get("dimension"), "x": position.get("x"),
                 "y": position.get("y"), "z": position.get("z")}
        if None in chest.values():
            anomaly = "상자 좌표 없음"
        elif event.action == "TAKE":
            _, anomaly = inventory.take(event.map_key, chest, username, event.item_id, count)
        else:
            _, anomaly = inventory.deposit(event.map_key, chest, username, event.item_id, count)

    db.session.commit()

    if anomaly:
        _dev_log("ledger-anomaly", username,
                 f"{event.action} {event.item_name} x{count} 반영 이상: {anomaly}", data, event_id=event.id)

    if event.chest_label and event.action in ("TAKE", "DEPOSIT"):
        _refresh_dashboard(event.map_key)

    if event.action in ("TAKE", "DEPOSIT"):
        # HOLD/RELEASE는 세부 감사 기록용이라 개발자 로그에만 남기고, 공개 입출고 채널은 순 증감만 보여준다.
        discord_client.post_event(
            current_app.config["DISCORD_BOT_TOKEN"],
            current_app.config["DISCORD_CHANNEL_ID"],
            event.to_dict(),
        )
    _dev_log("log-event", username,
              f"기록됨 (202): {data['action']} {data['item_name']} x{count}"
              f" @ {event.chest_label or '경유 상자'}({event.pos_x},{event.pos_y},{event.pos_z})",
              data, event_id=event.id)

    return jsonify({"status": "accepted"}), 202


@public_bp.post("/chest-log")
def chest_log():
    """상자를 열거나 닫는 순간의 전체 내용물 스냅샷을 개발자 로그에 남긴다 (감사/디버깅용,
    재고나 보유 장부에는 영향 없음 - TAKE/DEPOSIT과 별개로 그냥 "이 순간 실제로 뭐가 있었는지" 기록)."""
    data = _parse_encrypted_body()
    if data is None:
        _dev_log("chest-log", None, "실패: 잘못된 요청(복호화 실패)")
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "map_key", "position", "session")
    if not all(field in data for field in required):
        _dev_log("chest-log", data.get("username"), "실패: 필드 누락", data)
        return jsonify({"error": "missing fields"}), 400

    username = data["username"]
    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        _dev_log("chest-log", username, "거부: 미등록 사용자 (404)", data)
        return jsonify({"status": "not_found"}), 404

    if data["session"] not in ("OPEN", "CLOSE"):
        _dev_log("chest-log", username, "실패: 잘못된 session 값", data)
        return jsonify({"error": "invalid session"}), 400

    _mark_mod_linked(member)

    position = data["position"]
    items = data.get("items", [])
    label = "닫힘" if data["session"] == "CLOSE" else "열림"

    if not isinstance(position, dict) or not isinstance(items, list):
        _dev_log("chest-log", username, "실패: position/items 형식 오류", data)
        return jsonify({"error": "invalid fields"}), 400

    if items:
        lines = "\n".join(
            f"- {i.get('item_name', i.get('item_id'))} x{i.get('count', 0)}"
            for i in items if isinstance(i, dict)
        )
    else:
        lines = "(비어 있음)"

    chest_label = data.get("chest_label")
    if chest_label:
        mismatches = _snapshot_mismatches(data["map_key"], position, items)
        if mismatches:
            _dev_log("stock-mismatch", username,
                     f"{label} 시점 실측과 재고 불일치: " + ", ".join(mismatches), data)

    _dev_log(
        "chest-log", username,
        f"상자 {label}: {chest_label or '경유 상자'} @ {position.get('dimension')}"
        f" ({position.get('x')},{position.get('y')},{position.get('z')})\n{lines}",
        data,
    )

    return jsonify({"status": "ok"}), 200


@public_bp.get("/chests")
def list_chests():
    map_key = _normalize_map_key(request.args.get("map", ""))
    if not map_key:
        return jsonify({"error": "map is required"}), 400

    chests = TrackedChest.query.filter_by(map_key=map_key).all()
    return jsonify([c.to_dict() for c in chests])


@public_bp.post("/chests")
def register_chest():
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("map_key", "dimension", "x", "y", "z")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    xyz = _parse_xyz(data)
    if xyz is None:
        return jsonify({"error": "invalid x/y/z"}), 400
    x, y, z = xyz

    label = data.get("label") or "공용템 상자"
    existing = TrackedChest.query.filter_by(
        map_key=data["map_key"], dimension=data["dimension"], x=x, y=y, z=z,
    ).first()
    if existing:
        existing.label = label
        chest = existing
    else:
        chest = TrackedChest(
            map_key=data["map_key"],
            dimension=data["dimension"],
            x=x, y=y, z=z,
            label=label,
            registered_by=data.get("username"),
        )
        db.session.add(chest)
    db.session.commit()

    _dev_log("chest-add", data.get("username"),
              f"상자 등록됨: {label} @ {data['dimension']} ({x},{y},{z})", data)

    return jsonify(chest.to_dict()), 201


@public_bp.post("/chests/toggle-strict")
def toggle_chest_strict():
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("map_key", "dimension", "x", "y", "z")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    xyz = _parse_xyz(data)
    if xyz is None:
        return jsonify({"error": "invalid x/y/z"}), 400
    x, y, z = xyz

    chest = TrackedChest.query.filter_by(
        map_key=data["map_key"], dimension=data["dimension"], x=x, y=y, z=z,
    ).first()
    if chest is None:
        return jsonify({"error": "not found"}), 404

    chest.strict_mode = not chest.strict_mode
    db.session.commit()

    _dev_log("chest-toggle-strict", data.get("username"),
              f"개인템 차단 {'켜짐' if chest.strict_mode else '꺼짐'}: {chest.label}", data)

    return jsonify(chest.to_dict()), 200


@public_bp.get("/public-items")
def list_public_items():
    map_key = _normalize_map_key(request.args.get("map", ""))
    if not map_key:
        return jsonify({"error": "map is required"}), 400

    items = PublicItemType.query.filter_by(map_key=map_key).all()
    return jsonify([i.to_dict() for i in items])


@public_bp.post("/public-items")
def save_public_items():
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None or "map_key" not in data or "items" not in data:
        return jsonify({"error": "invalid payload"}), 400

    position = data.get("position") or {}
    if not isinstance(position, dict) or not isinstance(data["items"], list):
        return jsonify({"error": "invalid fields"}), 400
    map_key = data["map_key"]

    if not position.get("dimension") or position.get("x") is None or position.get("y") is None or position.get("z") is None:
        return jsonify({"error": "캡처 위치가 필요합니다"}), 400
    chest = {"dimension": position["dimension"], "x": position["x"], "y": position["y"], "z": position["z"]}

    # 캡처 = 지금 이 상자 내용물이 공용템 전체라는 기준선. 최대값을 고정하고 모든 보유 장부를 0으로 되돌린다.
    captured = []
    for item in data["items"]:
        if not isinstance(item, dict) or not item.get("item_id"):
            continue
        try:
            count = int(item.get("count", 0))
        except (TypeError, ValueError):
            continue
        if count <= 0:
            continue
        captured.append((item["item_id"], item.get("display_name", item["item_id"]), count))

    inventory.capture(map_key, chest, captured)
    saved = [item_id for item_id, _, _ in captured]
    db.session.commit()
    _refresh_dashboard(map_key)

    _dev_log("public-items-save", data.get("username"), f"공용템 타입 {len(saved)}개 저장됨(기준 재고 포함)", data)

    return jsonify({"status": "ok", "saved": saved}), 201


@public_bp.post("/public-items/remove")
def remove_public_items():
    """캡처가 잘못 포함시킨 아이템 타입을 공용템 목록에서 빼준다 (/item_reset 명령어용)."""
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None or "map_key" not in data or "item_ids" not in data:
        return jsonify({"error": "invalid payload"}), 400

    map_key = data["map_key"]
    removed = []
    for item_id in data["item_ids"]:
        deleted = PublicItemType.query.filter_by(map_key=map_key, item_id=item_id).delete()
        if deleted:
            removed.append(item_id)
    db.session.commit()

    _dev_log("public-items-remove", data.get("username"), f"공용템 타입 {len(removed)}개 해제됨", data)

    return jsonify({"status": "ok", "removed": removed}), 200


@public_bp.get("/held-items")
def list_held_items():
    """이 플레이어가 등록 상자를 통해 지금 들고 있다고 인정되는 아이템 타입별 개수."""
    map_key = _normalize_map_key(request.args.get("map", ""))
    username = request.args.get("username", "")
    if not map_key or not username:
        return jsonify({"error": "map, username이 필요합니다"}), 400

    rows = PlayerItemLedger.query.filter_by(map_key=map_key, minecraft_username=username).all()
    return jsonify([r.to_dict() for r in rows])


@public_bp.post("/held-items/reset")
def reset_held_items():
    """/item_reset 명령어용: 이 플레이어의 보유 장부를 0으로 되돌린다 (item_id 지정 시 그것만, 없으면 전부)."""
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None or "map_key" not in data or "username" not in data:
        return jsonify({"error": "invalid payload"}), 400

    map_key = data["map_key"]
    username = data["username"]
    item_ids = data.get("item_ids")

    query = PlayerItemLedger.query.filter_by(map_key=map_key, minecraft_username=username)
    if item_ids:
        query = query.filter(PlayerItemLedger.item_id.in_(item_ids))
    rows = query.all()
    chest = _registered_chest_position(map_key)
    for row in rows:
        if chest is not None:
            inventory.set_held(map_key, chest, username, row.item_id, 0)
        else:
            row.held_count = 0
    db.session.commit()
    _refresh_dashboard(map_key)

    _dev_log("held-items-reset", username, f"보유 장부 {len(rows)}종 리셋됨", data)

    return jsonify({"status": "ok", "reset": [r.item_id for r in rows]}), 200


@public_bp.get("/feature-toggles")
def get_feature_toggles():
    """맵별 기능 on/off 상태. 아직 한 번도 저장된 적 없으면 전부 켜짐(기존 동작)으로 응답한다."""
    map_key = _normalize_map_key(request.args.get("map", ""))
    if not map_key:
        return jsonify({"error": "map is required"}), 400

    toggle = FeatureToggle.query.filter_by(map_key=map_key).first()
    if toggle is None:
        return jsonify({
            "label_overlay": True, "public_tag": True,
            "passthrough_tracking": True, "chest_log": True,
        })
    return jsonify(toggle.to_dict())


@public_bp.post("/feature-toggles/toggle")
def toggle_feature():
    """개발자 빌드 전용(/dev_toggle): 이 맵의 기능 on/off를 서버에 저장해 모든 유저에게 적용한다."""
    if not _require_admin():
        return jsonify({"error": "unauthorized"}), 401

    data = _parse_encrypted_body()
    if data is None or "map_key" not in data or "key" not in data:
        return jsonify({"error": "invalid payload"}), 400

    if data["key"] not in VALID_TOGGLE_KEYS:
        return jsonify({"error": "invalid key"}), 400

    map_key = data["map_key"]
    toggle = FeatureToggle.query.filter_by(map_key=map_key).first()
    if toggle is None:
        # Column(default=True)는 flush 전까지 적용 안 돼서, 바로 아래 getattr가 None을 볼 수 있다
        # (not None == True라서 "뒤집기"가 항상 True로만 가버리는 버그가 됨) - 그래서 명시적으로 채운다.
        toggle = FeatureToggle(
            map_key=map_key, label_overlay=True, public_tag=True,
            passthrough_tracking=True, chest_log=True,
        )
        db.session.add(toggle)

    setattr(toggle, data["key"], not getattr(toggle, data["key"]))
    db.session.commit()

    _dev_log("feature-toggle", data.get("username"),
              f"기능 토글: {data['key']} -> {'ON' if getattr(toggle, data['key']) else 'OFF'}", data)

    return jsonify(toggle.to_dict()), 200


@public_bp.get("/chests/inventory")
def chest_inventory():
    map_key = _normalize_map_key(request.args.get("map", ""))
    dimension = request.args.get("dimension", "")
    try:
        x = int(request.args.get("x"))
        y = int(request.args.get("y"))
        z = int(request.args.get("z"))
    except (TypeError, ValueError):
        return jsonify({"error": "x, y, z가 필요합니다"}), 400

    if not map_key or not dimension:
        return jsonify({"error": "map, dimension이 필요합니다"}), 400

    rows = ChestInventoryItem.query.filter_by(
        map_key=map_key, dimension=dimension, x=x, y=y, z=z,
    ).all()
    return jsonify([r.to_dict() for r in rows])
