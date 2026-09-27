import logging
import time

import requests

logger = logging.getLogger(__name__)

DISCORD_API_BASE = "https://discord.com/api/v10"

TAKE_COLOR = 0xE74C3C  # 빨강 - 꺼냄
DEPOSIT_COLOR = 0x2ECC71  # 초록 - 넣음

# Discord(정확히는 그 앞단 Cloudflare)가 429를 주면, 같은 IP로 계속 두드릴수록 차단이 더 길어질 수 있다
# (Cloudflare Error 1015). 그래서 429를 한 번 받으면 이 시각까지는 아예 요청을 시도하지 않고 건너뛴다.
_backoff_until = 0.0
_DEFAULT_BACKOFF_SECONDS = 30.0
_MAX_BACKOFF_SECONDS = 300.0


def _in_backoff() -> bool:
    return time.monotonic() < _backoff_until


def _apply_backoff(resp: requests.Response) -> None:
    global _backoff_until
    retry_after = _DEFAULT_BACKOFF_SECONDS
    try:
        # 정상적인 디스코드 API의 429는 JSON 바디에 retry_after(초)를 담아준다.
        retry_after = float(resp.json().get("retry_after", retry_after))
    except (ValueError, requests.JSONDecodeError, AttributeError):
        # Cloudflare가 대신 막은 경우(HTML 응답, Error 1015 등)는 헤더를 대신 확인한다.
        header_value = resp.headers.get("Retry-After")
        if header_value:
            try:
                retry_after = float(header_value)
            except ValueError:
                pass
    retry_after = min(max(retry_after, _DEFAULT_BACKOFF_SECONDS), _MAX_BACKOFF_SECONDS)
    _backoff_until = time.monotonic() + retry_after
    logger.warning("디스코드 429 - %.0f초간 추가 요청을 건너뜁니다.", retry_after)


def post_event(bot_token: str, channel_id: str, event: dict) -> bool:
    """이벤트를 디스코드 채널에 임베드로 게시한다. 실패해도 예외를 올리지 않고 False를 반환한다."""
    if not bot_token or not channel_id:
        logger.warning("DISCORD_BOT_TOKEN/DISCORD_CHANNEL_ID가 설정되지 않아 디스코드 게시를 건너뜁니다.")
        return False

    is_take = event["action"] == "TAKE"
    embed = {
        "title": "공용 창고 " + ("출고" if is_take else "입고"),
        "color": TAKE_COLOR if is_take else DEPOSIT_COLOR,
        "fields": [
            {"name": "플레이어", "value": event["minecraft_username"], "inline": True},
            {"name": "아이템", "value": event["item_name"], "inline": True},
            {"name": "수량", "value": str(event["count"]), "inline": True},
        ],
        "timestamp": event["occurred_at"],
    }
    if event.get("chest_label"):
        embed["fields"].append({"name": "상자", "value": event["chest_label"], "inline": True})
    elif event.get("dimension"):
        embed["fields"].append({
            "name": "위치(경유 상자)",
            "value": f"{event['dimension']} ({event['pos_x']}, {event['pos_y']}, {event['pos_z']})",
            "inline": True,
        })

    return _send(bot_token, channel_id, embed)


def post_dev_log(bot_token: str, channel_id: str, *, endpoint: str, username: str | None,
                  ip: str | None, result: str, payload: dict | None = None) -> bool:
    """모든 API 요청 시도(성공/실패 무관)를 개발자 로그 채널에 기록한다."""
    if not bot_token or not channel_id:
        return False

    embed = {
        "title": f"[DEV] {endpoint}",
        "color": 0x3498DB,
        "fields": [
            {"name": "닉네임", "value": username or "(알 수 없음)", "inline": True},
            {"name": "아이피", "value": ip or "(알 수 없음)", "inline": True},
            {"name": "결과", "value": result, "inline": False},
        ],
    }
    if username:
        embed["thumbnail"] = {"url": f"https://mc-heads.net/avatar/{username}/100"}
    if payload is not None:
        body = str(payload)
        embed["fields"].append({"name": "요청 내용", "value": body[:1000], "inline": False})

    return _send(bot_token, channel_id, embed)


def _send(bot_token: str, channel_id: str, embed: dict) -> bool:
    if _in_backoff():
        logger.info("디스코드 429 쿨다운 중이라 메시지 전송을 건너뜁니다.")
        return False

    try:
        resp = requests.post(
            f"{DISCORD_API_BASE}/channels/{channel_id}/messages",
            headers={"Authorization": f"Bot {bot_token}"},
            json={"embeds": [embed]},
            timeout=10,
        )
        if resp.status_code == 429:
            _apply_backoff(resp)
            return False
        if resp.status_code >= 300:
            logger.error("디스코드 메시지 전송 실패 (%s): %s", resp.status_code, resp.text[:300])
            return False
        return True
    except requests.RequestException:
        logger.exception("디스코드 메시지 전송 중 오류")
        return False


def post_or_edit(bot_token: str, channel_id: str, message_id: str | None, embeds: list[dict]) -> str | None:
    """message_id가 있으면 그 메시지를 수정하고, 없거나 수정이 실패하면(메시지가 지워진 경우 등)
    새로 게시한다. 최종적으로 사용된(새로 만들어졌을 수도 있는) message_id를 반환한다."""
    if not bot_token or not channel_id:
        return None
    if _in_backoff():
        logger.info("디스코드 429 쿨다운 중이라 대시보드 갱신을 건너뜁니다.")
        return message_id

    headers = {"Authorization": f"Bot {bot_token}"}
    body = {"embeds": embeds}

    if message_id:
        try:
            resp = requests.patch(
                f"{DISCORD_API_BASE}/channels/{channel_id}/messages/{message_id}",
                headers=headers, json=body, timeout=10,
            )
            if resp.status_code < 300:
                return message_id
            if resp.status_code == 429:
                _apply_backoff(resp)
                return message_id
            logger.warning("대시보드 메시지 수정 실패 (%s), 새로 게시합니다: %s", resp.status_code, resp.text[:300])
        except requests.RequestException:
            logger.exception("대시보드 메시지 수정 중 오류, 새로 게시합니다")

    try:
        resp = requests.post(
            f"{DISCORD_API_BASE}/channels/{channel_id}/messages",
            headers=headers, json=body, timeout=10,
        )
        if resp.status_code == 429:
            _apply_backoff(resp)
            return None
        if resp.status_code >= 300:
            logger.error("대시보드 메시지 게시 실패 (%s): %s", resp.status_code, resp.text[:300])
            return None
        return resp.json().get("id")
    except requests.RequestException:
        logger.exception("대시보드 메시지 게시 중 오류")
        return None
