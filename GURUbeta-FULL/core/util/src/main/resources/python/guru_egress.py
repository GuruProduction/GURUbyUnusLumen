# GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
# Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
# Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
"""GURU egress guard for the bundled Python runtime.

WHY THIS EXISTS (issue 285). Environment variables only reach code that reads
them. urllib, http.client and requests honour a proxy; `socket.create_connection`
does not know proxies exist, so a script doing the obvious thing went straight out
of the device's real address while every other tool in the app rode the circuit.
The old answer was that confining a raw socket needs a network namespace, which a
stock app cannot install. That is true of the NETWORK, and it is not true of the
PROGRAM.

HOW IT WORKS. CPython imports `sitecustomize` at startup when it is importable, so
this file runs before the user's script does. It wraps `socket.create_connection`
and, when a proxy is configured, sends the connection through it with SOCKS5; when
no proxy is configured and the destination is public, it REFUSES rather than going
direct. A refusal that names the reason is the honest outcome, and it is the same
fail-closed rule the rest of the app follows.

WHAT IT DOES NOT DO. It cannot stop C code that calls connect(2) itself, because no
Python-level hook can. It does not touch a script that only addresses private
space, because a local connection must never be proxied: injecting a circuit for a
LAN address is what made a router read as a dead host in issue 215.
"""

import ipaddress
import socket
import struct

__all__ = ["installed", "proxy_configured", "refusal_reason"]


def _proxy_address():
    """The SOCKS proxy from the environment, or None.

    socks5h is the scheme that makes the PROXY resolve the hostname, so the DNS
    query does not leak the destination outside the circuit. A socks5 URL without
    the h is accepted but warned about, because it silently unprotects the lookup.
    """
    import os

    for name in ("ALL_PROXY", "all_proxy", "SOCKS_PROXY", "socks_proxy"):
        value = os.environ.get(name)
        if not value:
            continue
        scheme, _, rest = value.partition("://")
        if not rest:
            continue
        scheme = scheme.lower()
        if scheme not in ("socks5h", "socks5", "socks4a", "socks4"):
            continue
        host, _, port = rest.partition(":")
        port = port.rstrip("/") or "9050"
        try:
            return host or "127.0.0.1", int(port), scheme == "socks5h"
        except ValueError:
            return None
    return None


def _is_local(host):
    """Whether a destination is on this device or this network.

    A private address must never be proxied, and must never be refused: the point
    of the guard is public traffic only.
    """
    if not host:
        return True
    if host in ("localhost", "127.0.0.1", "::1", "0.0.0.0"):
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        # A NAME, not an address. Names cannot be classified without resolving
        # them, and resolving one here would be the leak this file exists to stop,
        # so a bare name is treated as public and goes through the proxy.
        return False
    return address.is_private or address.is_loopback or address.is_link_local


def _socks5_connect(proxy, host, port, timeout, source_address):
    """Open a TCP connection through a SOCKS5 proxy, doing the handshake ourselves.

    NO DEPENDENCY, DELIBERATELY. The bundled runtime ships no PySocks, and adding
    one would make this guard fail quietly on any install where the package was
    missing. The handshake is a dozen lines of the RFC and cannot be absent.
    """
    proxy_host, proxy_port, remote_dns = proxy
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    if timeout is not None:
        sock.settimeout(timeout)
    if source_address:
        sock.bind(source_address)
    try:
        sock.connect((proxy_host, proxy_port))

        # Greeting: version 5, one method, no authentication.
        sock.sendall(b"\x05\x01\x00")
        reply = sock.recv(2)
        if len(reply) != 2 or reply[0] != 5 or reply[1] != 0:
            raise OSError("SOCKS proxy refused the connection method")

        # CONNECT, with the name (0x03) or the address (0x01) inside the request.
        if remote_dns:
            encoded = host.encode("idna")
            request = b"\x05\x01\x00\x03" + bytes([len(encoded)]) + encoded
        else:
            try:
                request = b"\x05\x01\x00\x01" + socket.inet_aton(host)
            except OSError:
                encoded = host.encode("idna")
                request = b"\x05\x01\x00\x03" + bytes([len(encoded)]) + encoded
        request += struct.pack(">H", port)
        sock.sendall(request)

        head = sock.recv(4)
        if len(head) != 4 or head[0] != 5:
            raise OSError("SOCKS proxy returned a malformed reply")
        if head[1] != 0:
            raise OSError("SOCKS proxy refused CONNECT with code %d" % head[1])

        # Drain the bound address, whose length depends on the address type.
        if head[3] == 1:
            sock.recv(4 + 2)
        elif head[3] == 4:
            sock.recv(16 + 2)
        else:
            length = sock.recv(1)
            if length:
                sock.recv(length[0] + 2)

        return sock
    except Exception:
        try:
            sock.close()
        except Exception:
            pass
        raise


_refusal_reason = None


def refusal_reason():
    """Why a recent connection was refused, for a caller that wants to explain it."""
    return _refusal_reason


def proxy_configured():
    return _proxy_address() is not None


def install():
    """Wrap socket.create_connection so it obeys the egress rule."""
    global _refusal_reason
    if getattr(socket, "_guru_egress_installed", False):
        return
    original = socket.create_connection

    def create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT,
                          source_address=None, **kwargs):
        global _refusal_reason
        host, port = address[0], address[1]

        # A local destination is none of this guard's business.
        if _is_local(host):
            return original(address, timeout, source_address)

        proxy = _proxy_address()
        if proxy is not None:
            effective = None if timeout is socket._GLOBAL_DEFAULT_TIMEOUT else timeout
            return _socks5_connect(proxy, host, port, effective, source_address)

        # PUBLIC DESTINATION AND NO CIRCUIT: refuse rather than leak.
        _refusal_reason = (
            "GURU refused this connection: %s:%s is a public address and no Tor proxy "
            "is configured for this script, so sending it would leave the device's real "
            "IP visible. This script was run without a circuit, which happens when it "
            "is classified as local-only or when clearnet is on. Use urllib or requests "
            "for public traffic, or address this host by its private address." % (host, port)
        )
        raise OSError(_refusal_reason)

    create_connection.__doc__ = (
        original.__doc__ or ""
    ) + "\n\nWrapped by GURU: public destinations require a configured proxy (issue 285)."

    socket.create_connection = create_connection
    socket._guru_egress_installed = True


install()
installed = True
