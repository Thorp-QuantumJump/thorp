"""사례 연결 워커 엔트리포인트: ``python -m worker.regime_link_main``.

``regime-link.jobs``를 소비해 사례의 사후 구간을 잘라 요약하고 ``regime-link.events``로 ``STARTED``·
``FINISHED``를 발행한다. 백테스트 워커(``python -m worker.main``)와 **다른 프로세스**이며
같은 코드를 여러 개 띄워 수평 확장한다.

접속 정보는 환경변수 ``RABBITMQ_URL``(브로커)·``MARKET_DATA_URL``(시장 자료 DB, 읽기 전용)로만
주입하고 **기본값을 두지 않는다** — 값이 없으면 조용히 다른 DB를 읽는 대신 시작하지 않는다.

내용은 T018(묶음 M3)에서 채운다.
"""
