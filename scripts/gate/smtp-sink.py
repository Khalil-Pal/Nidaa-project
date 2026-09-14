"""
Minimal SMTP sink for gate runs and local development.

Accepts every message (including AUTH PLAIN / AUTH LOGIN with any credentials),
never relays anything, and prints the sender, recipients and subject of each
message to stdout. Run it, then start the application with

    MAIL_HOST=127.0.0.1 MAIL_PORT=1025 MAIL_SSL=false MAIL_AUTH=false

Usage: python scripts/gate/smtp-sink.py [port]        (default 1025)
"""
import socket
import sys
import threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 1025


def handle(conn):
    def send(line):
        conn.sendall((line + "\r\n").encode())

    try:
        send("220 nidaa-gate-sink ESMTP")
        buf = b""
        in_data = False
        data = []
        while True:
            chunk = conn.recv(4096)
            if not chunk:
                return
            buf += chunk
            while b"\r\n" in buf:
                line, buf = buf.split(b"\r\n", 1)
                text = line.decode(errors="replace")
                if in_data:
                    if text == ".":
                        in_data = False
                        headers = [h for h in data if h.lower().startswith(("from:", "to:", "subject:"))]
                        print("MESSAGE  " + " | ".join(headers), flush=True)
                        data = []
                        send("250 2.0.0 Ok: queued")
                    else:
                        data.append(text)
                    continue
                verb = text.split(" ", 1)[0].upper()
                if verb == "EHLO":
                    send("250-nidaa-gate-sink")
                    send("250-AUTH PLAIN LOGIN")
                    send("250 8BITMIME")
                elif verb == "HELO":
                    send("250 nidaa-gate-sink")
                elif verb == "AUTH":
                    if text.upper().startswith("AUTH LOGIN") and len(text.split()) == 2:
                        send("334 VXNlcm5hbWU6")      # "Username:"
                        state = "user"
                        # read username then password lines
                        while True:
                            while b"\r\n" not in buf:
                                more = conn.recv(4096)
                                if not more:
                                    return
                                buf += more
                            _, buf = buf.split(b"\r\n", 1)
                            if state == "user":
                                send("334 UGFzc3dvcmQ6")  # "Password:"
                                state = "pass"
                            else:
                                break
                    send("235 2.7.0 Authentication successful")
                elif verb in ("MAIL", "RCPT", "NOOP", "RSET"):
                    send("250 2.1.0 Ok")
                elif verb == "DATA":
                    in_data = True
                    send("354 End data with <CR><LF>.<CR><LF>")
                elif verb == "QUIT":
                    send("221 2.0.0 Bye")
                    return
                else:
                    send("502 5.5.2 Command not implemented")
    except (ConnectionError, OSError):
        pass
    finally:
        conn.close()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(16)
    print(f"smtp-sink listening on 127.0.0.1:{PORT}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == "__main__":
    main()
