"""Retired M2.1 experiment. Do not load into mitmproxy.

TLS interception, CA installation, and redirects of WZM traffic are outside the current
read-only M3.2/M4.1 scope. This addon intentionally refuses to start.
"""


def load(loader):  # pragma: no cover - mitmproxy compatibility guard
    del loader
    raise RuntimeError(
        "mitm-redirect was retired: TLS interception/CA installation is prohibited in the current scope"
    )
