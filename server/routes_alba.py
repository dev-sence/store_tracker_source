from datetime import datetime, timezone

from flask import Blueprint, jsonify, request

from models import AlbaHire, Member, db
from routes_public import _dev_log, _normalize_map_key, _parse_encrypted_body

alba_bp = Blueprint("alba", __name__, url_prefix="/api/alba")


def _active_hire(map_key, employer, employee):
    return AlbaHire.query.filter(
        AlbaHire.map_key == map_key,
        db.func.lower(AlbaHire.employer) == employer.lower(),
        db.func.lower(AlbaHire.employee) == employee.lower(),
        AlbaHire.fired_at.is_(None),
    ).first()


@alba_bp.post("/hire")
def hire():
    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "map_key", "employer", "employee")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    username = data["username"]
    employer = data["employer"]
    employee = data["employee"]
    if username.lower() != employer.lower():
        _dev_log("alba-hire", username, "거부: 본인이 고용주가 아닌 고용 보고", data)
        return jsonify({"error": "employer mismatch"}), 403

    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        return jsonify({"status": "not_found"}), 404

    map_key = _normalize_map_key(data["map_key"])

    # 모드를 쓰는 모두가 같은 채팅을 보고 똑같이 보고하므로, 이미 활성 고용이 있으면 새로 만들지 않는다
    # (최초 보고만 반영, 나머지는 조용히 무시 - 이게 사실상의 중복 제거다).
    existing = _active_hire(map_key, employer, employee)
    if existing is None:
        existing = AlbaHire(map_key=map_key, employer=employer, employee=employee)
        db.session.add(existing)
        db.session.commit()
        _dev_log("alba-hire", username, f"고용 기록됨: {employer} -> {employee}", data, event_id=None)
    else:
        _dev_log("alba-hire", username, f"이미 기록된 고용 (중복 무시): {employer} -> {employee}", data)

    return jsonify({"status": "ok", "hire": existing.to_dict()}), 202


@alba_bp.post("/fire")
def fire():
    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "map_key", "employer", "employee")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    username = data["username"]
    employer = data["employer"]
    employee = data["employee"]
    # 해고 메시지엔 누가 해고했는지 그대로 적혀 있어서, 그걸 목격한 등록 멤버라면 누구든(고용주 본인이
    # 아니어도, 고용주가 접속 중이 아니어도) 신고할 수 있다 - 그래서 고용과 달리 본인 확인을 안 한다.
    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        return jsonify({"status": "not_found"}), 404

    map_key = _normalize_map_key(data["map_key"])
    existing = _active_hire(map_key, employer, employee)
    if existing is None:
        _dev_log("alba-fire", username, f"이미 해고 처리됨 (무시): {employer} -> {employee}", data)
        return jsonify({"status": "ok", "already_fired": True}), 202

    existing.fired_at = datetime.now(timezone.utc)
    db.session.commit()
    _dev_log("alba-fire", username, f"해고 기록됨: {employer} -> {employee}", data)
    return jsonify({"status": "ok", "hire": existing.to_dict()}), 202


@alba_bp.post("/job")
def set_job():
    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "map_key", "employer", "employee", "job")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    username = data["username"]
    employer = data["employer"]
    employee = data["employee"]
    if username.lower() != employer.lower():
        _dev_log("alba-job", username, "거부: 본인이 고용주가 아닌 작업 설정", data)
        return jsonify({"error": "employer mismatch"}), 403

    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        return jsonify({"status": "not_found"}), 404

    map_key = _normalize_map_key(data["map_key"])
    existing = _active_hire(map_key, employer, employee)
    if existing is None:
        return jsonify({"error": "no active hire for this pair"}), 404

    existing.job = data["job"]
    existing.zone = data.get("zone")
    db.session.commit()
    _dev_log("alba-job", username,
             f"작업 설정: {employer} -> {employee} = {existing.job}" + (f" ({existing.zone})" if existing.zone else ""),
             data)
    return jsonify({"status": "ok", "hire": existing.to_dict()}), 202


@alba_bp.get("/status")
def status():
    map_key = _normalize_map_key(request.args.get("map", ""))
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    hires = AlbaHire.query.filter_by(map_key=map_key, fired_at=None).order_by(AlbaHire.hired_at.asc()).all()
    return jsonify({"hires": [h.to_dict() for h in hires]})
