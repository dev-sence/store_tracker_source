import logging
import time

import requests

logger = logging.getLogger(__name__)

# discord.com 앞단 Cloudflare가 429(Error 1015 등)를 주면, 같은 IP로 계속 두드릴수록 차단이
# 더 길어질 수 있다. 봇 메시지 API든 OAuth 토큰/유저 API든 전부 discord.com 하나를 공유하니,
# 이 모듈 하나로 모든 discord.com 호출에 공통 쿨다운을 적용한다.
_backoff_until = 0.0
_DEFAULT_BACKOFF_SECONDS = 30.0
_MAX_BACKOFF_SECONDS = 300.0


def in_backoff() -> bool:
    return time.monotonic() < _backoff_until


def apply_backoff(resp: requests.Response) -> None:
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
    logger.warning("디스코드 429 - %.0f초간 discord.com 호출을 건너뜁니다.", retry_after)
