package com.ssafy.thorp.assistant.application.result;

/** 모델 호출 한 번의 결과: 도구 요청이거나 최종 초안이다. 둘 다이거나 둘 다 아닌 경우는 어댑터가 장애로 처리한다. */
public sealed interface ModelTurn permits ModelDraft, ToolRequests {}
