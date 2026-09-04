"""전략 정의 계약 검증기.

배포되는 전략 정의와 계약 예시가 `docs/specs/strategy-definition/`의
JSON Schema를 만족하는지 확인한다. 스키마가 표현할 수 없는 규칙(D1~D18)은
Backend의 `StrategyDefinitionFormatValidator`가 담당하며 여기서 다루지 않는다.
다만 예시와 실제 정의가 어긋나는 것은 문서만 봐서는 알 수 없으므로 그 대조는 여기서 한다.

사용법:
    uv run --python 3.14 --with jsonschema python \
        docs/specs/strategy-definition/scripts/validate_definitions.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

SCRIPT_DIR = Path(__file__).resolve().parent
CONTRACT_DIR = SCRIPT_DIR.parent
REPO_ROOT = CONTRACT_DIR.parents[2]

RESOURCE_ROOT = REPO_ROOT / "backend" / "src" / "main" / "resources"
DEFINITION_DIR = RESOURCE_ROOT / "strategies"
EXAMPLE_DIR = CONTRACT_DIR / "examples"
VECTOR_DIR = CONTRACT_DIR / "test-vectors"

SCHEMA_FILES = (
    "strategy-definition.schema.json",
    "strategy-catalog-api.schema.json",
    "strategy-execution-binding.schema.json",
)

API = "strategy-catalog-api.schema.json"

# 예시 파일 → 그 파일 전체를 검증할 스키마 참조
EXAMPLE_SCHEMAS: dict[str, str] = {
    "get-strategies.response.json": f"{API}#/$defs/apiEnvelope",
    "get-strategy.response.json": f"{API}#/$defs/apiEnvelope",
    "get-strategy.not-found.response.json": f"{API}#/$defs/apiEnvelope",
    "parameter-validation.accepted.request.json": f"{API}#/$defs/parameterValidationRequest",
    "parameter-validation.accepted.response.json": f"{API}#/$defs/apiEnvelope",
    "parameter-validation.rejected.request.json": f"{API}#/$defs/parameterValidationRequest",
    "parameter-validation.rejected.response.json": f"{API}#/$defs/apiEnvelope",
    "execution-binding.json": "strategy-execution-binding.schema.json",
}

# 봉투 예시의 data 를 다시 검증할 스키마 참조
ENVELOPE_DATA_SCHEMAS: dict[str, str] = {
    "get-strategies.response.json": f"{API}#/$defs/getStrategiesResponseData",
    "get-strategy.response.json": f"{API}#/$defs/getStrategyResponseData",
    "parameter-validation.accepted.response.json": f"{API}#/$defs/parameterValidationResponseData",
    "parameter-validation.rejected.response.json": f"{API}#/$defs/parameterValidationResponseData",
}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def build_registry() -> Registry:
    """스키마끼리의 상대 경로 참조를 파일명으로 풀 수 있게 등록한다."""
    registry: Registry = Registry()
    for name in SCHEMA_FILES:
        resource = Resource.from_contents(read_json(CONTRACT_DIR / name))
        registry = resource @ registry
        registry = registry.with_resource(uri=name, resource=resource)
    return registry


def validator_for(reference: str, registry: Registry) -> Draft202012Validator:
    return Draft202012Validator({"$ref": reference}, registry=registry)


def report(label: str, instance: Any, validator: Draft202012Validator) -> int:
    errors = sorted(validator.iter_errors(instance), key=lambda error: list(error.path))
    if not errors:
        print(f"  OK   {label}")
        return 0
    print(f"  FAIL {label}")
    for error in errors[:5]:
        location = "/".join(str(part) for part in error.path) or "(root)"
        print(f"       {location}: {error.message}")
    return 1


def summarize(definition: dict[str, Any]) -> dict[str, Any]:
    """GET /strategies 의 목록 요약. 예시와 정의가 어긋나지 않는지 대조하는 데 쓴다."""
    return {
        "strategyId": definition["strategyId"],
        "definitionVersion": definition["definitionVersion"],
        "displayName": definition["displayName"],
        "description": definition["description"],
        "executionScopes": definition["executionScopes"],
        "parameterCount": len(definition["parameters"]),
        "deprecated": definition.get("deprecated", False),
    }


def validate_definitions(registry: Registry) -> int:
    print(f"[1/4] 배포 정의 — {DEFINITION_DIR.relative_to(REPO_ROOT)}")
    validator = validator_for("strategy-definition.schema.json", registry)
    paths = sorted(DEFINITION_DIR.glob("*.json"))
    if not paths:
        print("  FAIL 정의 파일이 하나도 없다")
        return 1

    failures = 0
    identifiers: list[str] = []
    for path in paths:
        definition = read_json(path)
        failures += report(path.name, definition, validator)
        identifiers.append(definition.get("strategyId", ""))
        if path.stem != definition.get("strategyId"):
            print(f"  FAIL 파일명과 strategyId 가 다르다: {path.name}")
            failures += 1

    duplicated = sorted({name for name in identifiers if identifiers.count(name) > 1})
    if duplicated:
        print(f"  FAIL strategyId 가 겹친다(D1): {duplicated}")
        failures += 1
    return failures


def validate_vectors(registry: Registry) -> int:
    print(f"[2/4] 테스트 벡터 — {VECTOR_DIR.relative_to(REPO_ROOT)}")
    validator = validator_for("strategy-definition.schema.json", registry)
    failures = 0
    for path in sorted(VECTOR_DIR.glob("*.json")):
        vector = read_json(path)
        for strategy_id, source in vector["definitionSources"].items():
            label = f"{path.name}:{strategy_id}"
            if "inline" in source:
                failures += report(label, source["inline"], validator)
                continue
            # ref 는 배포되는 정의 루트(backend/src/main/resources) 기준 상대 경로다.
            referenced = RESOURCE_ROOT / source["ref"]
            if referenced.exists():
                print(f"  OK   {label} -> {source['ref']}")
            else:
                print(f"  FAIL {label}: 참조한 정의가 없다 — {source['ref']}")
                failures += 1

        # 카탈로그에 없는 전략을 일부러 지정하는 케이스(UNKNOWN_STRATEGY)는 정상이다.
        # 그 외에 정의가 빠진 전략을 쓰는 케이스는 벡터가 깨진 것이다.
        for case in vector["cases"]:
            if case["strategyId"] in vector["definitionSources"]:
                continue
            codes = {violation["code"] for violation in case["expected"]["violations"]}
            if "UNKNOWN_STRATEGY" not in codes:
                print(
                    f"  FAIL {path.name}: 정의 없는 전략을 쓰는 케이스가 있다 — "
                    f"{case['strategyId']} ({case['name']})"
                )
                failures += 1
    return failures


def validate_examples(registry: Registry) -> int:
    print(f"[3/4] 계약 예시 — {EXAMPLE_DIR.relative_to(REPO_ROOT)}")
    failures = 0
    present = {path.name for path in EXAMPLE_DIR.glob("*.json")}

    missing = sorted(set(EXAMPLE_SCHEMAS) - present)
    if missing:
        print(f"  FAIL 예시 파일이 없다: {missing}")
        failures += 1
    unexpected = sorted(present - set(EXAMPLE_SCHEMAS))
    if unexpected:
        print(f"  FAIL 검증 스키마가 정해지지 않은 예시가 있다: {unexpected}")
        failures += 1

    for name in sorted(present & set(EXAMPLE_SCHEMAS)):
        instance = read_json(EXAMPLE_DIR / name)
        failures += report(name, instance, validator_for(EXAMPLE_SCHEMAS[name], registry))
        data_schema = ENVELOPE_DATA_SCHEMAS.get(name)
        if data_schema is not None:
            failures += report(
                f"{name}#/data", instance["data"], validator_for(data_schema, registry)
            )
    return failures


def validate_example_drift() -> int:
    """예시가 실제 정의와 어긋나면 문서를 읽은 사람이 틀린 계약을 믿게 된다."""
    print("[4/4] 예시와 배포 정의의 일치")
    failures = 0

    detail = read_json(EXAMPLE_DIR / "get-strategy.response.json")["data"]
    shipped = read_json(DEFINITION_DIR / f"{detail['strategyId']}.json")
    if detail == shipped:
        print("  OK   get-strategy.response.json -> 배포 정의와 동일")
    else:
        print(f"  FAIL get-strategy.response.json 의 data 가 {detail['strategyId']}.json 과 다르다")
        failures += 1

    summaries = read_json(EXAMPLE_DIR / "get-strategies.response.json")["data"]
    expected = [
        summarize(read_json(path))
        for path in sorted(DEFINITION_DIR.glob("*.json"))
        if "BACKTEST" in read_json(path)["executionScopes"]
    ]
    if sorted(summaries, key=lambda item: item["strategyId"]) == sorted(
        expected, key=lambda item: item["strategyId"]
    ):
        print("  OK   get-strategies.response.json -> 배포 정의와 동일")
    else:
        print("  FAIL get-strategies.response.json 의 목록 요약이 배포 정의와 다르다")
        failures += 1
    return failures


def main() -> int:
    # Windows 기본 콘솔 인코딩(cp949)은 이 파일이 쓰는 기호를 담지 못한다. 개발자 기계와 CI가
    # 같은 출력을 보게 맞춘다.
    sys.stdout.reconfigure(encoding="utf-8")

    registry = build_registry()
    failures = (
        validate_definitions(registry)
        + validate_vectors(registry)
        + validate_examples(registry)
        + validate_example_drift()
    )
    print()
    if failures:
        print(f"실패 {failures}건")
        return 1
    print("모두 통과")
    return 0


if __name__ == "__main__":
    sys.exit(main())
