package com.thorp.api.backtest;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백테스트 Job API (스캐폴딩 초안).
 *
 * <p>POST /backtests → Job 생성 후 Kafka로 전달, 즉시 { backtestId, status: QUEUED } 반환 GET /backtests/{id}
 * → 상태/결과 조회 (QUEUED → RUNNING → COMPLETED | FAILED)
 */
@RestController
@RequestMapping("/backtests")
public class BacktestController {

    // TODO: BacktestService 주입 후 실제 구현으로 교체

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody BacktestJobRequest request) {
        // TODO: Job 저장(QUEUED) → Kafka publish → { backtestId, status } 반환
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{backtestId}")
    public ResponseEntity<Void> get(@PathVariable Long backtestId) {
        // TODO: 상태/결과 조회
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
