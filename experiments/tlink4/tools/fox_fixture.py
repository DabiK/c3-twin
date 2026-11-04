#!/usr/bin/env python3
"""Simule les canaux Fox de ZLink pour tester C3 Twin sans autoradio.

Le téléphone n'est pas nécessaire : on sert une mire H.264, un ton PCM et on affiche
les messages tactiles et les touches reçus. Les ports par défaut sont ceux du Mac ;
`adb reverse` fait le pont vers les ports réels de ZLink (51818, 51838, 51848, 51858, 51888).

Options de test :
  --system-burst N   la voix système parle N s puis se tait N s (test de baisse de la musique)
  --audio-switch N   après N s, l'audio principal passe en 44,1 kHz mono (changement de format)
  --video-stop N     après N s, la vidéo coupe la connexion (test de reprise)
"""
import argparse
import math
import socket
import subprocess
import threading
import time
from array import array
from pathlib import Path

VIDEO_HEADER = "VIDEO#TYPE:H264#WIDTH:{w}#HEIGHT:{h}#LEN:{n}#LM:8#TM:4#RM:8#BM:4"
AUDIO_HEADER = "AUDIO#TYPE:PCM#SAMPLE:{s}#CHANNELS:{c}#BITS:16#LEN:{n}#"
AUDIO_HEADER_TYPE = "AUDIO#TYPE:PCM#SAMPLE:{s}#CHANNELS:{c}#BITS:16#LEN:{n}#AUDIOTYPE:{t}#"


def make_frame(path: Path, width: int, height: int) -> bytes:
    if not path.exists():
        subprocess.run(
            ["ffmpeg", "-v", "error", "-f", "lavfi",
             "-i", f"testsrc2=size={width}x{height}",
             "-frames:v", "1", "-c:v", "libx264", "-preset", "ultrafast",
             "-tune", "zerolatency", "-f", "h264", "-y", str(path)],
            check=True,
        )
    return path.read_bytes()


def make_tone(sample: int, channels: int, frames: int, hz: int) -> bytes:
    samples = array("h")
    for i in range(frames):
        value = int(12000 * math.sin(2 * math.pi * hz * i / sample))
        for _ in range(channels):
            samples.append(value)
    return samples.tobytes()


def serve(port: int, handler) -> None:
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", port))
        server.listen()
        print(f"écoute {port}", flush=True)
        while True:
            client, _ = server.accept()
            try:
                with client:
                    handler(client)
            except OSError:
                pass


VIDEO_CONNECTIONS = [0]


def video(client: socket.socket, frame: bytes, width: int, height: int, stop: float = 0) -> None:
    request = client.recv(128)
    VIDEO_CONNECTIONS[0] += 1
    print("vidéo: requête", request.rstrip(b"\0"), "connexion", VIDEO_CONNECTIONS[0], flush=True)
    header = VIDEO_HEADER.format(w=width, h=height, n=len(frame)).encode().ljust(128, b"\0")
    packet = header + frame
    start = time.time()
    while True:
        # only the first connection is cut, so that the reconnection can be seen to hold
        if stop and VIDEO_CONNECTIONS[0] == 1 and time.time() - start > stop:
            print("vidéo: coupure volontaire", flush=True)
            return
        client.sendall(packet)
        time.sleep(0.1)


def audio(client: socket.socket, pcm: bytes, header: str, burst: float = 0,
          switch: float = 0) -> None:
    request = client.recv(128)
    print("audio: requête", request.rstrip(b"\0"), flush=True)
    packet = header.encode().ljust(128, b"\0") + pcm
    silent = header.encode().ljust(128, b"\0") + bytes(len(pcm))
    start = time.time()
    switched = False
    while True:
        elapsed = time.time() - start
        if switch and elapsed > switch and not switched:
            switched = True
            mono = make_tone(44100, 1, 882, 330)
            packet = AUDIO_HEADER.format(s=44100, c=1, n=len(mono)).encode().ljust(128, b"\0") + mono
            print("audio: passage en 44100 Hz mono", flush=True)
        speaking = not burst or int(elapsed / burst) % 2 == 0
        client.sendall(packet if speaking else silent)
        time.sleep(0.02)


def touch(client: socket.socket) -> None:
    print("tactile: client connecté", flush=True)
    with client.makefile("rb") as stream:
        while message := stream.read(128):
            print("tactile:", message.rstrip(b"\0").decode("ascii", "replace"), flush=True)


def keys(client: socket.socket) -> None:
    print("touches: client connecté", flush=True)
    with client.makefile("rb") as stream:
        while message := stream.read(128):
            print("touches:", message.rstrip(b"\0").decode("ascii", "replace"), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video-port", type=int, default=52818)
    parser.add_argument("--touch-port", type=int, default=52848)
    parser.add_argument("--audio-port", type=int, default=52858)
    parser.add_argument("--system-port", type=int, default=52888)
    parser.add_argument("--key-port", type=int, default=52838)
    parser.add_argument("--system-burst", type=float, default=0)
    parser.add_argument("--audio-switch", type=float, default=0)
    parser.add_argument("--video-stop", type=float, default=0)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=360)
    parser.add_argument("--frame", type=Path, default=Path("/tmp/c3-grid-test.h264"))
    args = parser.parse_args()

    frame = make_frame(args.frame, args.width, args.height)
    sample, channels, frames = 48000, 2, 960  # 20 ms
    pcm = make_tone(sample, channels, frames, 440)
    main_header = AUDIO_HEADER.format(s=sample, c=channels, n=len(pcm))
    system_header = AUDIO_HEADER_TYPE.format(s=sample, c=channels, n=len(pcm), t=2)

    threading.Thread(
        target=serve, args=(args.video_port, lambda c: video(c, frame, args.width, args.height, args.video_stop)),
        daemon=True,
    ).start()
    threading.Thread(
        target=serve, args=(args.audio_port, lambda c: audio(c, pcm, main_header, switch=args.audio_switch)),
        daemon=True,
    ).start()
    threading.Thread(
        target=serve, args=(args.system_port, lambda c: audio(c, pcm, system_header, burst=args.system_burst)),
        daemon=True,
    ).start()
    threading.Thread(target=serve, args=(args.key_port, keys), daemon=True).start()
    serve(args.touch_port, touch)


if __name__ == "__main__":
    main()
