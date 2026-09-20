"""사례 연결(spec 011) 구간 계산 워커.

010이 찾은 경제환경 사례의 직후 63거래일(보조 21거래일)에서, 백엔드가 메시지에 담아 보낸 004
실행 사본을 잘라 요약한다. 백테스트 워커(``worker.consumer``·``worker.main``)와 큐·엔트리포인트를
공유하지 않는다. 작업은 ``regime-link.jobs``로 오고 실행은
``python -m worker.regime_link_main``이다.

백테스트 워커와 같은 3층 분리를 따른다(``worker/CLAUDE.md`` "브로커 연동").

* ``contracts`` 백엔드와 공유하는 메시지 모델
* ``messaging`` 계약 상수와 경계 Protocol (pika 없음)
* ``slicer`` 구간 잘라내기·요약 계산
* ``consumer`` 수신·검증·계산 호출·발행 순서·ACK 판단
* ``rabbitmq`` pika 구현체

``contracts``·``messaging``은 M1b에서 채웠다. 계산(``slicer``)과 소비·발행
(``consumer``·``rabbitmq``)은 M2·M3에서 채운다.
"""
