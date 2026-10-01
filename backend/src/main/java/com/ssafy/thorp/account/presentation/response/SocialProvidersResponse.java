package com.ssafy.thorp.account.presentation.response;

import java.util.List;

/** 자격 증명이 설정된 제공자. 값은 {@code kakao}·{@code google}·{@code naver}다. */
public record SocialProvidersResponse(List<String> providers) {}
