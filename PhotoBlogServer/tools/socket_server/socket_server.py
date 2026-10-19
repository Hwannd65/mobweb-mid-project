import os
import socket
from datetime import datetime

class SocketServer:
    def __init__(self):
        self.bufsize = 1024

        with open('./response.bin', 'rb') as file:
            self.RESPONSE = file.read()

        self.DIR_PATH = './request'
        self.createDir(self.DIR_PATH)

    def createDir(self, path):
        try:
            if not os.path.exists(path):
                os.makedirs(path)
        except OSError:
            print("Error: Failed to create the directory.")

    def run(self, ip, port):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind((ip, port))
        self.sock.listen(10)

        print("Start the socket server...")
        print("\"Ctrl+C\" for stopping the server!\r\n")

        try:
            while True:
                clnt_sock, req_addr = self.sock.accept()
                clnt_sock.settimeout(5.0)

                print("Request message...\r\n")

                response = b""

                # 여기에 실습 1, 2 코드 구현
                # HTTP Header를 먼저 받음
                request_data = b""

                while b"\r\n\r\n" not in request_data:
                    data = clnt_sock.recv(self.bufsize)

                    if not data:
                        break

                    request_data += data

                # Header와 Body 분리
                header_data, body = request_data.split(b"\r\n\r\n", 1)

                # Header에서 Content-Length 확인
                header_text = header_data.decode("iso-8859-1")
                content_length = 0

                for line in header_text.split("\r\n"):
                    if line.lower().startswith("content-length:"):
                        content_length = int(line.split(":", 1)[1].strip())

                # 아직 받지 못한 Body가 있으면 계속 받음
                while len(body) < content_length:
                    data = clnt_sock.recv(self.bufsize)

                    if not data:
                        break

                    body += data

                # HTTP Request 전체를 다시 합침
                request_data = header_data + b"\r\n\r\n" + body

                # 현재 시간을 파일명으로 사용
                now = datetime.now()
                filename = now.strftime("%Y-%m-%d-%H-%M-%S") + ".bin"

                filepath = os.path.join(self.DIR_PATH, filename)

                # 바이너리 파일로 저장
                with open(filepath, "wb") as file:
                    file.write(request_data)

                print("Saved:", filepath)

                # multipart/form-data인지 확인
                content_type = ""

                for line in header_text.split("\r\n"):
                    if line.lower().startswith("content-type:"):
                        content_type = line.split(":", 1)[1].strip()

                # multipart/form-data일 경우
                if "multipart/form-data" in content_type:
                    # boundary 값 추출
                    boundary = content_type.split("boundary=", 1)[1]
                    boundary_bytes = ("--" + boundary).encode()

                    # Body를 boundary 기준으로 분리
                    parts = body.split(boundary_bytes)

                    for part in parts:
                        # 이미지가 포함된 multipart만 찾음
                        if b'name="image"' in part and b"filename=" in part:

                            # multipart 내부 Header와 실제 파일 데이터 분리
                            part = part.lstrip(b"\r\n")

                            if b"\r\n\r\n" not in part:
                                continue

                            part_header, image_data = part.split(b"\r\n\r\n", 1)

                            # multipart 뒤에 붙는 CRLF 제거
                            image_data = image_data.rstrip(b"\r\n")

                            # filename 추출
                            part_header_text = part_header.decode("iso-8859-1")

                            filename = "received_image.jpg"

                            for header_line in part_header_text.split("\r\n"):
                                if "filename=" in header_line:
                                    filename = header_line.split("filename=", 1)[1]
                                    filename = filename.strip().strip('"')

                            # 디렉터리가 포함될 경우 파일명만 사용
                            filename = os.path.basename(filename)

                            # 이미지 저장
                            image_path = os.path.join(self.DIR_PATH, filename)

                            with open(image_path, "wb") as image_file:
                                image_file.write(image_data)

                            print("Image Saved:", image_path)

                clnt_sock.sendall(self.RESPONSE)
                clnt_sock.close()

        except KeyboardInterrupt:
            print("\r\nStop the server...")

        self.sock.close()

if __name__ == "__main__":
    server = SocketServer()
    server.run("127.0.0.1", 8000)
