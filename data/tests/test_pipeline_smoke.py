"""수집 환경 스모크 테스트.

T001에서 정비한 패키지 경로와 저장 디렉터리가 갖춰졌는지만 확인한다.
실제 KRX OPEN API 호출은 인증키가 필요하므로 여기서 하지 않는다.
"""

from pathlib import Path

DATA_ROOT = Path(__file__).resolve().parents[1]


def test_data_pipeline_package_is_importable() -> None:
    import data_pipeline

    assert data_pipeline.__name__ == "data_pipeline"


def test_storage_directories_exist() -> None:
    assert (DATA_ROOT / "raw").is_dir()
    assert (DATA_ROOT / "scripts").is_dir()
