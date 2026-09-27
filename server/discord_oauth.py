import logging
import re

import requests
from flask import current_app

import discord_backoff

logger = logging.getLogger(__name__)

# 브라우저가 직접 여는 주소라 여기는 프록시를 거치지 않는다 (Render 아웃바운드 IP 문제와 무관 -
# 이건 유저 본인 브라우저에서 discord.com으로 바로 가는 것뿐).
_DISCORD_BROWSER_BASE = "https://discord.com/api/v10"


def _api_base() -> str:
    # 서버->서버 호출(토큰 교환, 유저/길드 조회)만 Render의 공유 IP 문제를 우회하려고
    # Cloudflare Worker 프록시를 거친다. 설정 안 돼 있으면 예전처럼 직접 호출한다.
    proxy_url = current_app.config.get("DISCORD_PROXY_URL")
    if proxy_url:
        return f"{proxy_url}/api/v10"
    return _DISCORD_BROWSER_BASE


def _extra_headers() -> dict:
    proxy_secret = current_app.config.get("DISCORD_PROXY_SECRET")
    return {"X-Proxy-Secret": proxy_secret} if proxy_secret else {}


# 서버 닉네임이 보통 "표시이름 [마크닉네임]"(가끔 "표시이름(마크닉네임)") 형태라, 맨 끝 괄호 안 내용을 뽑아온다.
_NICK_PATTERN = re.compile(r"[\[(]\s*([^\[\]()]+?)\s*[\])]\s*$")


def build_authorize_url(client_id: str, redirect_uri: str, state: str) -> str:
    from urllib.parse import urlencode
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": "identify",
        "state": state,
    }
    return f"{_DISCORD_BROWSER_BASE}/oauth2/authorize?{urlencode(params)}"


def exchange_code(client_id: str, client_secret: str, redirect_uri: str, code: str) -> dict | None:
    """인가 코드를 access token으로 교환한다. 실패하면 None."""
    if discord_backoff.in_backoff():
        logger.info("디스코드 429 쿨다운 중이라 토큰 교환을 건너뜁니다.")
        return None
    try:
        resp = requests.post(
            f"{_api_base()}/oauth2/token",
            data={
                "client_id": client_id,
                "client_secret": client_secret,
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded", **_extra_headers()},
            timeout=10,
        )
        if resp.status_code == 429:
            discord_backoff.apply_backoff(resp)
            return None
        if resp.status_code != 200:
            logger.warning("토큰 교환 실패 (status=%s): %s", resp.status_code, resp.text[:300])
            return None
        return resp.json()
    except requests.RequestException:
        logger.exception("토큰 교환 중 오류")
        return None


def fetch_oauth_user(access_token: str) -> dict | None:
    """방금 로그인한 사람 자신의 디스코드 계정 정보(@me)를 가져온다."""
    if discord_backoff.in_backoff():
        logger.info("디스코드 429 쿨다운 중이라 유저 정보 조회를 건너뜁니다.")
        return None
    try:
        resp = requests.get(
            f"{_api_base()}/users/@me",
            headers={"Authorization": f"Bearer {access_token}", **_extra_headers()},
            timeout=10,
        )
        if resp.status_code == 429:
            discord_backoff.apply_backoff(resp)
            return None
        if resp.status_code != 200:
            logger.warning("유저 정보 조회 실패 (status=%s): %s", resp.status_code, resp.text[:300])
            return None
        return resp.json()
    except requests.RequestException:
        logger.exception("유저 정보 조회 중 오류")
        return None


def avatar_url(user: dict) -> str | None:
    avatar_hash = user.get("avatar")
    if not avatar_hash:
        return None
    return f"https://cdn.discordapp.com/avatars/{user['id']}/{avatar_hash}.png?size=64"


def guess_minecraft_username(bot_token: str, guild_id: str, discord_user_id: str) -> str | None:
    """이 사람의 (봇이 이미 들어가 있는) 서버 별명에서 마크 닉네임으로 추정되는 값을 뽑아온다.
    별명이 없거나 대괄호 패턴이 아니면 None (관리자/본인이 직접 입력해야 함)."""
    if not bot_token or not guild_id:
        return None
    if discord_backoff.in_backoff():
        logger.info("디스코드 429 쿨다운 중이라 서버 별명 조회를 건너뜁니다.")
        return None
    try:
        resp = requests.get(
            f"{_api_base()}/guilds/{guild_id}/members/{discord_user_id}",
            headers={"Authorization": f"Bot {bot_token}", **_extra_headers()},
            timeout=10,
        )
    except requests.RequestException:
        return None
    if resp.status_code == 429:
        discord_backoff.apply_backoff(resp)
        return None
    if resp.status_code != 200:
        return None

    data = resp.json()
    display_name = data.get("nick") or (data.get("user") or {}).get("global_name") \
        or (data.get("user") or {}).get("username")
    if not display_name:
        return None

    match = _NICK_PATTERN.search(display_name)
    return match.group(1).strip() if match else None
