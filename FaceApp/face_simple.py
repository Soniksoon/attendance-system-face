import argparse
import asyncio
from datetime import datetime
import aiohttp
import cv2
from insightface.app import FaceAnalysis

# НАСТРОЙКИ
JAVA_BACKEND_URL = "http://localhost:8080/api/face/recognize"

# НАСТРОЙКИ КАМЕРЫ TAPO
TAPO_USERNAME = "TapoCamera"
TAPO_PASSWORD = "TapoCamera11"
TAPO_IP = "172.20.10.2"
TAPO_RTSP_URL = f"rtsp://{TAPO_USERNAME}:{TAPO_PASSWORD}@{TAPO_IP}:554/stream2"


async def send_to_java(embedding: list, location: str, timestamp: str):
    """Отправляет данные на Java бэкенд"""
    try:
        async with aiohttp.ClientSession() as session:
            payload = {
                "vector": embedding,
                "room": location,
                "timestamp": timestamp
            }
            async with session.post(JAVA_BACKEND_URL, json=payload) as response:
                if response.status == 200:
                    result = await response.json()
                    print(f"Java ответ: {result}")
                    return result
                else:
                    print(f"Java ошибка: {response.status}")
                    return None
    except Exception as e:
        print(f"Connection error: {e}")
        return None


async def recognize_face(location: str):
    print(f"Loading face recognition model...")
    face_app = FaceAnalysis(name='buffalo_l')
    face_app.prepare(ctx_id=0, det_size=(640, 640))

    print(f"Connecting to Tapo camera at: {TAPO_IP}")
    video = cv2.VideoCapture(TAPO_RTSP_URL, cv2.CAP_FFMPEG)

    if not video.isOpened():
        print("Cannot connect to Tapo camera!")
        return

    print(f"Connected! Face recognition started in room: {location}")
    print(f"Sending to Java: {JAVA_BACKEND_URL}")
    print("-" * 50)

    frame_count = 0
    while True:
        ret, image = video.read()
        if not ret:
            print("Failed to read frame, reconnecting...")
            video.release()
            video = cv2.VideoCapture(TAPO_RTSP_URL, cv2.CAP_FFMPEG)
            await asyncio.sleep(1)
            continue

        frame_count += 1
        if frame_count % 2 == 0:
            faces = face_app.get(image)

            now = datetime.now()

            for face in faces:
                embedding = face.embedding.astype(float).tolist()
                print(f"Face detected! Sending to Java...")

                # Отправляем на Java и получаем ответ
                response = await send_to_java(embedding, location, now.isoformat())

                if response:
                    print(f" Ответ Java: {response}")

        await asyncio.sleep(0.01)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Face recognition service with Tapo camera")
    parser.add_argument('-l', '--location', type=str, default='1-206', help="Room number")
    parser.add_argument('-j', '--java-url', type=str, help="Java backend URL")
    parser.add_argument('--tapo-ip', type=str, help="Tapo camera IP address")
    parser.add_argument('--tapo-user', type=str, help="Tapo camera username")
    parser.add_argument('--tapo-pass', type=str, help="Tapo camera password")

    args = parser.parse_args()

    if args.tapo_ip:
        TAPO_IP = args.tapo_ip
        TAPO_RTSP_URL = f"rtsp://{TAPO_USERNAME}:{TAPO_PASSWORD}@{TAPO_IP}:554/stream1"
    if args.tapo_user:
        TAPO_USERNAME = args.tapo_user
        TAPO_RTSP_URL = f"rtsp://{TAPO_USERNAME}:{TAPO_PASSWORD}@{TAPO_IP}:554/stream1"
    if args.tapo_pass:
        TAPO_PASSWORD = args.tapo_pass
        TAPO_RTSP_URL = f"rtsp://{TAPO_USERNAME}:{TAPO_PASSWORD}@{TAPO_IP}:554/stream1"
    if args.java_url:
        JAVA_BACKEND_URL = args.java_url

    asyncio.run(recognize_face(args.location))