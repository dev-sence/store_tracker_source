from datetime import datetime, timezone

from flask import Blueprint, jsonify, request

from models import AlbaHire, AlbaWageRate, Member, db
from routes_public import _dev_log, _normalize_map_key, _parse_encrypted_body

alba_bp = Blueprint("alba", __name__, url_prefix="/api/alba")

# 아직 맵에 단가를 설정한 적 없는 작업에 쓰는 기본값 - 사용자가 처음 요청한 기준가. 대시보드에서
# 언제든 덮어쓸 수 있고(AlbaWageRate 행이 생기면 이 기본값 대신 그걸 씀), 기록 시점에 건별로도
# 덮어쓸 수 있다.
DEFAULT_WAGE_RATES = {"WATERMELON": 38, "PUMPKIN": 37}


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


@alba_bp.get("/wage-rates")
def wage_rates():
    """작업별 상자당 기본 단가 - 인게임에서 "몇 상자?" 입력창을 열 때 기본값을 미리 채우는 데 쓴다."""
    map_key = _normalize_map_key(request.args.get("map", ""))
    if not map_key:
        return jsonify({"error": "map is required"}), 400
    rates = AlbaWageRate.query.filter_by(map_key=map_key).all()
    merged = dict(DEFAULT_WAGE_RATES)
    merged.update({r.job: r.unit_price for r in rates})
    return jsonify(merged)


@alba_bp.get("/pending-records")
def pending_records():
    """해고는 됐는데 아직 "몇 상자 채웠는지"를 안 적은 기록 - 알바 수정 화면에 "기록 대기 중"으로
    보여주기 위함. 본인이 고용주인 것만 돌려준다(장난/실수로 남이 적는 걸 막기 위해 /record에서도
    employer 일치를 다시 검사하지만, 목록 단계에서부터 안 보이게)."""
    map_key = _normalize_map_key(request.args.get("map", ""))
    employer = request.args.get("employer", "")
    if not map_key or not employer:
        return jsonify({"error": "map and employer are required"}), 400
    hires = AlbaHire.query.filter(
        AlbaHire.map_key == map_key,
        db.func.lower(AlbaHire.employer) == employer.lower(),
        AlbaHire.fired_at.isnot(None),
        AlbaHire.box_count.is_(None),
    ).order_by(AlbaHire.fired_at.desc()).limit(20).all()
    return jsonify({"hires": [h.to_dict() for h in hires]})


@alba_bp.post("/record")
def record():
    """해고된 알바가 몇 상자를 채웠는지(+ 그때 쓸 단가) 기록한다 - 인게임 "알바 수정" 화면에서만
    호출된다(그 화면을 열어야만 적을 수 있게 하기 위한 의도적 제약). 단가를 안 주면 그 작업의
    맵 기본 단가를 그대로 스냅샷으로 저장한다."""
    data = _parse_encrypted_body()
    if data is None:
        return jsonify({"error": "invalid payload"}), 400

    required = ("username", "map_key", "hire_id", "box_count")
    if not all(field in data for field in required):
        return jsonify({"error": "missing fields"}), 400

    username = data["username"]
    member = Member.query.filter_by(minecraft_username=username).first()
    if member is None:
        return jsonify({"status": "not_found"}), 404

    try:
        box_count = int(data["box_count"])
    except (TypeError, ValueError):
        return jsonify({"error": "box_count는 숫자여야 합니다"}), 400
    if box_count < 0:
        return jsonify({"error": "box_count는 0 이상이어야 합니다"}), 400

    map_key = _normalize_map_key(data["map_key"])
    hire = AlbaHire.query.filter_by(id=data["hire_id"], map_key=map_key).first()
    if hire is None:
        return jsonify({"error": "no such hire"}), 404
    if hire.employer.lower() != username.lower():
        _dev_log("alba-record", username, "거부: 본인이 고용주가 아닌 기록 입력", data)
        return jsonify({"error": "employer mismatch"}), 403

    unit_price = data.get("unit_price")
    if unit_price is None:
        rate = AlbaWageRate.query.filter_by(map_key=map_key, job=hire.job).first()
        unit_price = rate.unit_price if rate else DEFAULT_WAGE_RATES.get(hire.job, 0)
    else:
        try:
            unit_price = int(unit_price)
        except (TypeError, ValueError):
            return jsonify({"error": "unit_price는 숫자여야 합니다"}), 400

    hire.box_count = box_count
    hire.unit_price = unit_price
    hire.recorded_by = username
    hire.recorded_at = datetime.now(timezone.utc)
    db.session.commit()
    _dev_log("alba-record", username,
             f"기록됨: {hire.employer} -> {hire.employee} = {box_count}상자 x {unit_price}", data)
    return jsonify({"status": "ok", "hire": hire.to_dict()}), 202
