"""
Admin console login. ADMIN_USER / ADMIN_PASSWORD come from the environment (compose file);
a successful login sets a signed, HttpOnly cookie that every admin page and API call checks.
"""
import hashlib
import hmac
import threading
import time
from typing import Dict, Optional

from fastapi import Request

from .config import ADMIN_USER, ADMIN_PASSWORD, ADMIN_SESSION_HOURS, CODE_MAX_ATTEMPTS, CODE_LOCKOUT_MINUTES
from .database import get_session_secret

COOKIE_NAME = "tvd_admin"


def admin_configured() -> bool:
    return bool(ADMIN_PASSWORD)


def check_credentials(username: str, password: str) -> bool:
    if not admin_configured():
        return False
    user_ok = hmac.compare_digest(username.strip().encode(), ADMIN_USER.encode())
    pass_ok = hmac.compare_digest(password.encode(), ADMIN_PASSWORD.encode())
    return user_ok and pass_ok


def _sign(expires: int) -> str:
    # Covers the password too: changing ADMIN_PASSWORD in the compose file signs everyone out
    key = (get_session_secret() + ":admin").encode()
    msg = f"{ADMIN_USER}:{ADMIN_PASSWORD}:{expires}".encode()
    return hmac.new(key, msg, hashlib.sha256).hexdigest()


def make_cookie() -> str:
    expires = int(time.time()) + ADMIN_SESSION_HOURS * 3600
    return f"{expires}.{_sign(expires)}"


def is_admin(request: Request) -> bool:
    if not admin_configured():
        return False
    value = request.cookies.get(COOKIE_NAME, "")
    expires_str, _, sig = value.partition(".")
    try:
        expires = int(expires_str)
    except ValueError:
        return False
    return expires > time.time() and hmac.compare_digest(sig, _sign(expires))


class LoginLimiter:
    """Per-IP lockout after repeated wrong passwords: 5 free tries, then 1 min, doubling to 15 min."""
    FREE_ATTEMPTS = 5
    BASE_LOCK_SECONDS = 60
    MAX_LOCK_SECONDS = 15 * 60

    def __init__(self):
        self._failures: Dict[str, int] = {}
        self._locked_until: Dict[str, float] = {}
        self._lock = threading.Lock()

    def seconds_locked(self, ip: str) -> int:
        with self._lock:
            return max(0, int(self._locked_until.get(ip, 0) - time.time() + 0.999))

    def failed(self, ip: str) -> None:
        with self._lock:
            n = self._failures.get(ip, 0) + 1
            self._failures[ip] = n
            if n >= self.FREE_ATTEMPTS:
                lock = min(self.MAX_LOCK_SECONDS, self.BASE_LOCK_SECONDS * 2 ** (n - self.FREE_ATTEMPTS))
                self._locked_until[ip] = time.time() + lock

    def succeeded(self, ip: str) -> None:
        with self._lock:
            self._failures.pop(ip, None)
            self._locked_until.pop(ip, None)


login_limiter = LoginLimiter()


class CodeLimiter:
    """
    TV code guessing: after `max_attempts` wrong codes in a row a TV (IP address) is locked out;
    once the lockout ends it gets `max_attempts` new tries, and each further lockout doubles.
    A correct code, or an unlock from the admin console, clears it.
    """
    MAX_LOCK_SECONDS = 24 * 3600

    def __init__(self, max_attempts: int, lock_minutes: int):
        self.max_attempts = max(1, max_attempts)
        self.base_lock = max(1, lock_minutes) * 60
        self._failures: Dict[str, int] = {}
        self._lockouts: Dict[str, int] = {}
        self._locked_until: Dict[str, float] = {}
        self._lock = threading.Lock()

    def seconds_locked(self, ip: str) -> int:
        with self._lock:
            return max(0, int(self._locked_until.get(ip, 0) - time.time() + 0.999))

    def failed(self, ip: str) -> int:
        """Records a wrong code; returns the lockout in seconds it caused (0 if tries remain)."""
        with self._lock:
            n = self._failures.get(ip, 0) + 1
            if n < self.max_attempts:
                self._failures[ip] = n
                return 0
            self._failures[ip] = 0
            lockouts = self._lockouts.get(ip, 0)
            self._lockouts[ip] = lockouts + 1
            seconds = min(self.MAX_LOCK_SECONDS, self.base_lock * 2 ** lockouts)
            self._locked_until[ip] = time.time() + seconds
            return seconds

    def attempts_left(self, ip: str) -> int:
        with self._lock:
            return self.max_attempts - self._failures.get(ip, 0)

    def clear(self, ip: str) -> None:
        with self._lock:
            self._failures.pop(ip, None)
            self._lockouts.pop(ip, None)
            self._locked_until.pop(ip, None)

    def active_lockouts(self) -> Dict[str, int]:
        now = time.time()
        with self._lock:
            return {ip: int(until - now + 0.999) for ip, until in self._locked_until.items() if until > now}


code_limiter = CodeLimiter(CODE_MAX_ATTEMPTS, CODE_LOCKOUT_MINUTES)


def client_ip(request: Request) -> str:
    return request.client.host if request.client else ""


def safe_next(target: Optional[str]) -> str:
    """Only same-site paths after login (no open redirects)."""
    if target and target.startswith("/") and not target.startswith("//"):
        return target
    return "/admin"
