package com.ssafy.thorp.sector.application.service;

import com.ssafy.thorp.sector.application.exception.InstrumentProfileNotReadyException;
import com.ssafy.thorp.sector.application.exception.PriceSeriesNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorMapNotReadyException;
import com.ssafy.thorp.sector.application.exception.SectorNotFoundException;
import com.ssafy.thorp.sector.application.repository.InstrumentProfileRepository;
import com.ssafy.thorp.sector.application.repository.PriceSeriesRepository;
import com.ssafy.thorp.sector.application.repository.SectorRepository;
import com.ssafy.thorp.sector.application.result.InstrumentProfileResult;
import com.ssafy.thorp.sector.application.result.PriceSeriesResult;
import com.ssafy.thorp.sector.application.result.SectorListResult;
import com.ssafy.thorp.sector.domain.model.Sector;
import org.springframework.stereotype.Service;

/**
 * 섹터 목록·대표 상품 정보 조회 유스케이스 (User Story 1·3).
 *
 * <p>대응표를 고치는 메서드가 없다. 저장소가 읽기 전용이고 원본은 파이프라인이 소유한다(research.md 결정 7).
 */
@Service
public class SectorService {

    private final SectorRepository sectorRepository;
    private final InstrumentProfileRepository instrumentProfileRepository;
    private final PriceSeriesRepository priceSeriesRepository;

    public SectorService(
            SectorRepository sectorRepository,
            InstrumentProfileRepository instrumentProfileRepository,
            PriceSeriesRepository priceSeriesRepository) {
        this.sectorRepository = sectorRepository;
        this.instrumentProfileRepository = instrumentProfileRepository;
        this.priceSeriesRepository = priceSeriesRepository;
    }

    /**
     * 현재 열린 대응표의 섹터 전체.
     *
     * @throws SectorMapNotReadyException 대응표가 아직 발행되지 않았을 때. 빈 목록으로 돌려주지 않는다
     */
    public SectorListResult getSectors() {
        return sectorRepository.findOpenSectorMap().orElseThrow(SectorMapNotReadyException::new);
    }

    /**
     * 한 섹터의 대표 상품 정보 (User Story 3).
     *
     * <p>세 실패를 구분한다. 대응표 미발행은 409 {@code SECTOR_MAP_NOT_READY}, 그 대응표에 없는 섹터는 404 {@code
     * SECTOR_NOT_FOUND}, 섹터는 있는데 대표 상품 정보가 아직 없으면 409 {@code INSTRUMENT_PROFILE_NOT_READY}다. 셋을 빈
     * 값으로 뭉개면 화면이 "잠시 후 다시"와 "없는 섹터"를 가릴 수 없다(원칙 VI).
     *
     * <p><b>열린 대응표에서 섹터를 찾은 다음 그 대표 ETF로 조회한다.</b> 목록과 같은 판을 보게 하려는 것이다. 섹터 코드로 곧장 대표 상품 정보를 찾으면
     * 물러난 대응표가 가리키던 ETF의 값이 현재 목록 옆에 나올 수 있다. 목록은 섹터 수십 행이라 한 번 더 읽는 값이 싸다.
     *
     * <p>경로 변수를 {@code SectorCode}로 만들지 않는 이유는 {@link SectorNotFoundException} 문서에 적었다.
     *
     * @throws SectorMapNotReadyException 대응표가 아직 발행되지 않았을 때
     * @throws SectorNotFoundException 열린 대응표에 그 코드가 없을 때(형식이 틀린 코드도 여기다)
     * @throws InstrumentProfileNotReadyException 대표 상품 정보가 아직 만들어지지 않았을 때
     */
    public InstrumentProfileResult getInstrumentProfile(String sectorCode) {
        SectorListResult map = getSectors();
        Sector sector =
                map.sectors().stream()
                        .filter(candidate -> candidate.code().value().equals(sectorCode))
                        .findFirst()
                        .orElseThrow(() -> new SectorNotFoundException(sectorCode));
        return instrumentProfileRepository
                .findCurrentProfile(map.mapVersion(), sector.representativeEtf().instrumentId())
                .orElseThrow(() -> new InstrumentProfileNotReadyException(sectorCode));
    }

    /**
     * 한 섹터 대표 ETF의 일별 가격 흐름.
     *
     * <p>실패를 가르는 방식은 대표 상품 정보와 같다. 대응표 미발행은 409 {@code SECTOR_MAP_NOT_READY}, 그 대응표에 없는 섹터는 404
     * {@code SECTOR_NOT_FOUND}, 섹터는 있는데 가격이 아직 적재되지 않았으면 409 {@code PRICE_SERIES_NOT_READY}다.
     *
     * <p><b>열린 대응표에서 섹터를 찾은 다음 그 대표 ETF로 조회한다.</b> 목록·대표 상품 정보와 같은 판을 보게 하려는 것이다. 물러난 대응표가 가리키던
     * ETF의 가격이 지금 목록 옆에 그려지면, 사용자는 다른 상품의 흐름을 보고 고르게 된다.
     *
     * @throws SectorMapNotReadyException 대응표가 아직 발행되지 않았을 때
     * @throws SectorNotFoundException 열린 대응표에 그 코드가 없을 때(형식이 틀린 코드도 여기다)
     * @throws PriceSeriesNotReadyException 그 종목의 가격이 아직 적재되지 않았을 때
     */
    public PriceSeriesResult getPriceSeries(String sectorCode) {
        SectorListResult map = getSectors();
        Sector sector =
                map.sectors().stream()
                        .filter(candidate -> candidate.code().value().equals(sectorCode))
                        .findFirst()
                        .orElseThrow(() -> new SectorNotFoundException(sectorCode));
        return priceSeriesRepository
                .findCurrentSeries(map.mapVersion(), sector.representativeEtf().instrumentId())
                .orElseThrow(() -> new PriceSeriesNotReadyException(sectorCode));
    }
}
