package com.ssafy.thorp.assistant.application.result;

import java.util.List;

/** 모델이 도구 사용을 요청한 턴. {@code parallel_tool_calls:false}라 보통 하나지만 목록으로 받는다. */
public record ToolRequests(List<ToolCall> calls) implements ModelTurn {}
