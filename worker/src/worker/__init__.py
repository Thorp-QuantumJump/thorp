"""Thorp 백테스트 워커. 버전은 여기 한 곳이 원본이다 — pyproject 는 이 값을 읽고(dynamic), 결과
``versions.worker`` 도 이 값을 싣는다. 운영 이미지는 패키지를 설치하지 않고 src 를 복사하므로
importlib.metadata 로는 버전을 알 수 없다.
"""

__version__ = "0.1.1"
