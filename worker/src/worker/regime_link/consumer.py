"""사례 연결 작업 소비자.

헤더 ``schemaVersion`` 확인 → 작업 파싱 → ``STARTED`` 발행 → ``slicer`` 호출 → ``FINISHED`` 발행 →
브로커 확인 뒤 ACK. 브로커 기술을 모르고 ``messaging``의 Protocol로만 말한다.

오류는 세 갈래다(백테스트 소비자와 같은 규칙, ``worker/CLAUDE.md``). ① 계약 오류는 requeue 없이
reject해 DLQ로 보내고 ② 계산 실패는 ``FINISHED(FAILED)``를 발행한 뒤 ACK하며 ③ 발행 실패와 시장
자료 적재 중 일시 장애는 ACK하지 않고 재전달 경로로 넘긴다.

백테스트 소비자(``worker.consumer``)는 건드리지 않는다.

내용은 T016(묶음 M3)에서 채운다.
"""
