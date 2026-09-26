import base64
import json
import os

from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# 자바 클라이언트(SecureChannel.java)와 반드시 동일해야 하는 파라미터.
SALT = b"wnsghdev"
ITERATIONS = 200_000
KEY_LENGTH = 32
IV_LENGTH = 12


def _derive_key(passphrase: str) -> bytes:
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=KEY_LENGTH,
        salt=SALT,
        iterations=ITERATIONS,
    )
    return kdf.derive(passphrase.encode("utf-8"))


class SecureChannel:
    """APP_SECRET으로부터 파생된 키로 AES-256-GCM 암복호화를 수행한다.

    와이어 포맷: base64(iv[12B] || ciphertext || tag[16B])
    """

    def __init__(self, passphrase: str):
        self._key = _derive_key(passphrase)
        self._aesgcm = AESGCM(self._key)

    def encrypt(self, plaintext: bytes) -> str:
        iv = os.urandom(IV_LENGTH)
        ciphertext = self._aesgcm.encrypt(iv, plaintext, None)
        return base64.b64encode(iv + ciphertext).decode("ascii")

    def decrypt(self, payload_b64: str) -> bytes:
        raw = base64.b64decode(payload_b64)
        iv, ciphertext = raw[:IV_LENGTH], raw[IV_LENGTH:]
        return self._aesgcm.decrypt(iv, ciphertext, None)

    def encrypt_json(self, obj) -> str:
        return self.encrypt(json.dumps(obj).encode("utf-8"))

    def decrypt_json(self, payload_b64: str):
        return json.loads(self.decrypt(payload_b64).decode("utf-8"))
