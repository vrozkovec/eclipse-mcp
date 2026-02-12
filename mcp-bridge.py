#!/usr/bin/env python3
"""
Stdio-to-TCP bridge for Eclipse MCP server.
Forwards stdin/stdout to a TCP connection on localhost:8099.
Used by Claude Code to communicate with the Eclipse MCP plugin.
"""

import socket
import sys
import threading

HOST = "localhost"
PORT = 8099


def tcp_to_stdout(sock):
    """Read lines from TCP socket and write to stdout."""
    try:
        f = sock.makefile("r", encoding="utf-8")
        for line in f:
            sys.stdout.write(line)
            sys.stdout.flush()
    except (OSError, ConnectionError):
        pass
    finally:
        sys.exit(0)


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else HOST
    port = int(sys.argv[2]) if len(sys.argv) > 2 else PORT

    try:
        sock = socket.create_connection((host, port))
    except ConnectionRefusedError:
        print(f"Cannot connect to Eclipse MCP server at {host}:{port}", file=sys.stderr)
        print("Make sure Eclipse is running with the MCP plugin.", file=sys.stderr)
        sys.exit(1)

    reader = threading.Thread(target=tcp_to_stdout, args=(sock,), daemon=True)
    reader.start()

    try:
        for line in sys.stdin:
            sock.sendall(line.encode("utf-8"))
    except (OSError, ConnectionError, BrokenPipeError):
        pass
    finally:
        sock.close()


if __name__ == "__main__":
    main()
