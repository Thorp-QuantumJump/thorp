package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsPublisherRepository;
import com.ssafy.thorp.news.domain.model.NewsPublisher;
import com.ssafy.thorp.news.domain.vo.PermissionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** {@link NewsPublisherRepository} 구현. */
@Repository
public class NewsPublisherRepositoryImpl implements NewsPublisherRepository {

    private final PublisherJpaRepository jpaRepository;

    public NewsPublisherRepositoryImpl(PublisherJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public NewsPublisher save(NewsPublisher publisher) {
        NewsPublisherJpaEntity saved =
                jpaRepository.save(NewsPersistenceMapper.toEntity(publisher));
        return NewsPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<NewsPublisher> findById(Long id) {
        return jpaRepository.findById(id).map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public Optional<NewsPublisher> findByPublisherKey(String publisherKey) {
        return jpaRepository.findByPublisherKey(publisherKey).map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public Optional<NewsPublisher> findByRegisteredDomain(String registeredDomain) {
        if (registeredDomain == null || registeredDomain.isBlank()) {
            return Optional.empty();
        }
        return jpaRepository
                .findByRegisteredDomain(registeredDomain.toLowerCase(java.util.Locale.ROOT))
                .map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public List<NewsPublisher> findEnabled() {
        return jpaRepository.findByEnabledTrue().stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<NewsPublisher> findPubliclyVisible() {
        return jpaRepository.findByPermissionStatus(PermissionStatus.ALLOWED).stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public boolean existsPubliclyVisible() {
        return jpaRepository.existsByPermissionStatus(PermissionStatus.ALLOWED);
    }
}
