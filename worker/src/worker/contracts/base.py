"""계약 모델 공통 설정."""

from datetime import UTC, datetime
from typing import Annotated

from pydantic import AfterValidator, AwareDatetime, BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

# 실행 시각(README §5): 시간대 정보가 없는 값은 거부하고, 있으면 UTC로 정규화해 "…Z"로 직렬화한다.
# 백엔드 Instant는 오프셋 없는 문자열을 역직렬화하지 못하므로 워커에서 먼저 막는다.
# 거래일(date)은 대상이 아니다.
UtcDatetime = Annotated[AwareDatetime, AfterValidator(lambda d: d.astimezone(UTC))]


def utc_now() -> datetime:
    """이벤트 시각 생성용. naive ``datetime.now()`` 대신 쓴다."""
    return datetime.now(UTC)


class ContractModel(BaseModel):
    """JSON camelCase ↔ 내부 snake_case.

    알 수 없는 필드는 무시한다 (백엔드 FAIL_ON_UNKNOWN_PROPERTIES=false와 동일).
    null 규칙(README §5): 생략 가능한 필드는 ``T | None = None`` 으로 선언하고, 덤프는
    ``model_dump_json(by_alias=True, exclude_none=True)`` 로 키를 생략한다.
    Decimal은 pydantic v2 JSON 모드가 기본으로 문자열로 내므로 별도 serializer가 없다.
    """

    model_config = ConfigDict(populate_by_name=True, alias_generator=to_camel, extra="ignore")

    def to_json(self) -> str:
        """계약 규칙대로 직렬화한다 (camelCase, null 키 생략)."""
        return self.model_dump_json(by_alias=True, exclude_none=True)
