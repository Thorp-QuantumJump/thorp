package com.ssafy.thorp.paramsearch.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ParamSearchJpaRepository extends JpaRepository<ParamSearchJpaEntity, Long> {

    Optional<ParamSearchJpaEntity> findBySearchId(String searchId);

    /**
     * 묶음 행을 잠그고 읽는다. 동시에 도착한 결과 이벤트가 집계와 발행 창 계산에서 서로를 덮어쓰지 않게 한다(내부 계약 §4).
     *
     * <p>{@code PESSIMISTIC_WRITE}인 이유: 읽고 세고 쓰는 사이에 다른 이벤트가 같은 값을 읽으면 하나의 갱신이 사라진다. 낙관적 락으로 두면 충돌할
     * 때마다 이벤트를 다시 처리해야 하는데, 이 경로는 메시지 재전달이 아니라 인메모리 이벤트라 재시도 장치가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParamSearchJpaEntity s where s.id = :id")
    Optional<ParamSearchJpaEntity> findByIdForUpdate(@Param("id") Long id);
}
