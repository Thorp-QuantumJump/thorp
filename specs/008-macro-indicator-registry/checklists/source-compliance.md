# Source Eligibility & Use-Conditions Checklist: 경제지표 원천 검증

**Purpose**: Spec 008의 17개 계열을 실제 원천으로 확정하기 전에, 원천 선택·이용 권리·출처 전달·미확정 상태에 관한 요구사항의 완전성·명료성·일관성을 검토한다.
**Created**: 2026-09-17
**Feature**: [spec.md](../spec.md) · [plan.md](../plan.md) · [research.md](../research.md)

**Note**: 이 점검표는 원천 접속이나 구현 동작 시험이 아니라 요구사항 문장의 품질을 검토한다.
**Review Ownership**: 작성자와 자료 이용 조건을 검토할 담당자가 구현·완료 판정 전에 검토한다. 새 항목은 모두 미체크 상태다.
**Marker Semantics**: `[x]`는 요구사항 품질이 검토·충족되었다는 뜻이며 실제 원천 검증이나 구현 완료를 뜻하지 않는다.

## Evidence Requirements

- [ ] CHK001 17개 계열마다 PD-11의 실제 응답·항목 식별자·조회 조건·확인 일시·이용 조건 근거를 어떤 기록에 남길지 요구사항에 명시되어 있는가? [Completeness, Spec §FR-002, §FR-022, §PD-11]
- [ ] CHK002 동일 통계의 작성기관, API 제공기관, 권리자를 서로 다르게 판정해야 할 때 어느 주체의 조건을 우선 확인할지 정의되어 있는가? [Gap, Spec §FR-016, §PD-11]
- [ ] CHK003 지표별 최초·최종 관측월과 변환에 필요한 원값의 실제 가용 범위를 확인할 증거 기준이 정의되어 있는가? [Completeness, Spec §FR-006, §FR-022, §PD-11]
- [ ] CHK004 API 문서상의 일반 허용 조건과 실제 선택한 계열의 예외 조건을 구별해 PD-11에 기록하도록 요구하는가? [Clarity, Spec §FR-016, §FR-019, §PD-11]

## Source Decision Clarity

- [ ] CHK005 ECOS·KOSIS 양쪽에 후보 계열이 있을 때 주 원천의 선택 근거와 대체 시 새 등록표 버전이 필요한 경계를 명확히 정의하는가? [Clarity, Spec §FR-002, §FR-005]
- [ ] CHK006 관세청 무역수지가 별도 공식 값인지 파생값인지에 따라 출처·계산 근거·검증 기준을 어떻게 달리 기록할지 정해져 있는가? [Gap, Spec §FR-006, §PD-11, Research §1]
- [ ] CHK007 OECD G20 계열의 공식 원천 확인과 제3자 권리 확인을 서로 독립된 완료 조건으로 구별하는가? [Gap, Spec §FR-019, §PD-11, Research §1]
- [ ] CHK008 DATA_GO_KR 보완 자료가 실제 운영 주 원천으로 승격될 수 있는지, 가능하다면 어떤 별도 결정이 필요한지 경계가 분명한가? [Clarity, Spec §FR-019]

## Rights & Distribution Boundaries

- [ ] CHK009 ECOS의 한국은행 작성 통계와 타 기관 작성 통계에 서로 다른 이용 조건을 적용하는 판정 기준이 명확한가? [Clarity, Spec §FR-016]
- [ ] CHK010 KOSIS 국제통계·북한통계 예외에 해당하는지를 각 선택 계열별로 판정하고, 제한이 있으면 어떤 제공 형태를 보류할지 요구하는가? [Coverage, Spec §FR-016, §PD-11]
- [ ] CHK011 화면 표시, 008/009 API 응답, 010 계산 입력, 006 내보내기, 원자료 재배포의 권리 범위를 혼동하지 않도록 각각의 이용 경계를 정의하는가? [Gap, Spec §FR-016, §FR-018, §FR-023]
- [ ] CHK012 원값을 변환하거나 무역수지를 파생해도 작성기관·원천·가공 사실을 어떤 소비 경로까지 전달해야 하는지 명시되어 있는가? [Completeness, Spec §FR-006, §FR-016, §FR-023]
- [ ] CHK013 원천 이용 조건이 바뀌거나 권한이 철회되었을 때 이미 발행한 스냅샷·API 제공·내보내기의 유지 또는 중단 범위를 정의하는가? [Gap, Spec §FR-011, §FR-016, §FR-018]

## Restricted & Unavailable Sources

- [ ] CHK014 인증키 미발급, 승인 대기, 라이선스 미확인, 실제 조회 실패를 서로 다른 미확정 상태로 구분하고 각각의 발행 보류 기준을 명시하는가? [Coverage, Spec §FR-011, §FR-022, §PD-11]
- [ ] CHK015 원천별 호출 한도·요청 크기·캐시 조건이 확인되지 않았을 때 해당 원천의 수집 가능 여부와 완료 판정이 어떻게 달라지는지 정의하는가? [Gap, Spec §FR-017, §FR-019, §PD-11]
- [ ] CHK016 예시 자료가 실제 통계와 섞이거나 운영의 결손을 메우는 것으로 해석되지 않도록 환경·표시·버전의 분리 조건을 요구하는가? [Consistency, Spec §FR-011, §FR-022, §FR-023]
- [ ] CHK017 KDI 자료를 해석 근거로 인용하는 경우에도 수치 대조·검색 입력·KDI 공식 모델로의 오인을 금지하는 범위가 일관되게 명시되어 있는가? [Consistency, Spec §FR-020]

## Acceptance & Ownership

- [ ] CHK018 PD-11을 ‘17개 후보를 등록했다’가 아니라 ‘17개 실제 응답과 계열별 이용 조건을 확인했다’로 판정할 수 있는 완료 기준이 있는가? [Measurability, Spec §FR-022, §PD-11]
- [ ] CHK019 출처와 작성기관이 필요한 모든 소비 화면으로 전달되었는지 판정할 대상 목록과 책임 경계가 명시되어 있는가? [Measurability, Spec §FR-016, §FR-023, §SC-006]
- [ ] CHK020 이용 조건의 재확인 시점·담당자·근거 갱신 책임과 조건 변경 시 재판정해야 할 자료 버전의 범위가 정해져 있는가? [Gap, Spec §FR-002, §FR-016, §PD-11]

## Notes

- 충족하지 않은 항목은 수정할 요구사항 문서와 확인 근거를 적고 미체크로 둔다.
- 이 목록은 앞서 만든 [macro-data.md](macro-data.md)의 자료·버전·소비 계약 전반 점검을 대체하지 않는다.
- `$speckit-implement`는 체크 상태를 읽지만 이 표식을 수정하지 않는다.
- `checklists/requirements.md`는 `$speckit-specify`·`$speckit-clarify`가 관리하는 별도 기본 점검표다.
