"""사례 연결 브로커 계약 상수와 경계 Protocol.

exchange·queue·routing key·``SCHEMA_VERSION``·prefetch를 둔다. 이 값들은 설정이 아니라 계약이라
``worker.config``(환경변수)로 빼지 않고, 백엔드 ``RegimeLinkRabbitConfig``의 ``public static final
String``과 같은 값을 유지한다. **pika를 import하지 않는다.**

발행과 ACK의 경계는 ``EventPublisher``·``JobAcknowledger`` Protocol이며 구현은 ``rabbitmq``에 있다.

내용은 T007(묶음 M1b)에서 채운다.
"""
