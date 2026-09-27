from datetime import datetime, timezone
from functools import wraps

from flask import Blueprint, current_app, jsonify, redirect, render_template, request, session, url_for

from models import ChestInventoryItem, Event, Member, PlayerItemLedger, PublicItemType, TrackedChest, db

dashboard_bp = Blueprint("dashboard", __name__)


def login_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not session.get("admin"):
            return redirect(url_for("dashboard.login"))
        return fn(*args, **kwargs)

    return wrapper


@dashboard_bp.get("/login")
def login():
    if session.get("admin"):
        return redirect(url_for("dashboard.index"))
    return render_template("login.html")


@dashboard_bp.post("/login")
def login_submit():
    token = (request.form.get("token") or "").strip()
    if token and token == current_app.config["ADMIN_TOKEN"]:
        session["admin"] = True
        session.permanent = True
        return redirect(url_for("dashboard.index"))
    return redirect(url_for("dashboard.login", error="1"))


@dashboard_bp.get("/logout")
def logout():
    session.clear()
    return redirect(url_for("dashboard.login"))


@dashboard_bp.get("/")
@login_required
def index():
    map_keys = sorted({
        row[0] for row in db.session.query(TrackedChest.map_key).distinct()
        if row[0]
    })
    selected_map = request.args.get("map") or (map_keys[0] if map_keys else "")
    return render_template("dashboard.html", map_keys=map_keys, selected_map=selected_map)


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
