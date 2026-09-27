import re

import requests

DISCORD_API_BASE = "https://discord.com/api/v10"

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
    return f"{DISCORD_API_BASE}/oauth2/authorize?{urlencode(params)}"


def exchange_code(client_id: str, client_secret: str, redirect_uri: str, code: str) -> dict | None:
    """인가 코드를 access token으로 교환한다. 실패하면 None."""
    try:
        resp = requests.post(
            f"{DISCORD_API_BASE}/oauth2/token",
            data={
                "client_id": client_id,
                "client_secret": client_secret,
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
            },
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=10,
        )
        if resp.status_code != 200:
            return None
        return resp.json()
    except requests.RequestException:
        return None


def fetch_oauth_user(access_token: str) -> dict | None:
    """방금 로그인한 사람 자신의 디스코드 계정 정보(@me)를 가져온다."""
    try:
        resp = requests.get(
            f"{DISCORD_API_BASE}/users/@me",
            headers={"Authorization": f"Bearer {access_token}"},
            timeout=10,
        )
        if resp.status_code != 200:
            return None
        return resp.json()
    except requests.RequestException:
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
    try:
        resp = requests.get(
            f"{DISCORD_API_BASE}/guilds/{guild_id}/members/{discord_user_id}",
            headers={"Authorization": f"Bot {bot_token}"},
            timeout=10,
        )
    except requests.RequestException:
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
