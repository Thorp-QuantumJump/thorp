"""Thorp 백테스트 워커. 버전은 여기 한 곳이 원본이다 — pyproject 는 이 값을 읽고(dynamic), 결과
``versions.worker`` 도 이 값을 싣는다. 운영 이미지는 패키지를 설치하지 않고 src 를 복사하므로
importlib.metadata 로는 버전을 알 수 없다.
"""

# 0.2.0(T029): 평가 시작일 산식이 바뀌어 같은 입력이 다른 숫자를 낸다. 결과 ``versions.worker``
# 로 옛 규칙 결과와 새 규칙 결과를 갈라 볼 수 있어야 한다. **동일 버전 키에 들어가는 것은 이 값이
# 아니라** 백엔드 ``thorp.backtest.engine-version`` 이다(그쪽도 함께 올렸다).
__version__ = "0.2.0"
