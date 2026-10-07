package com.ssafy.thorp.learning.application.service;

import com.ssafy.thorp.learning.application.exception.ArticleUnavailableException;
import com.ssafy.thorp.learning.application.exception.CompletionRequiredException;
import com.ssafy.thorp.learning.application.exception.NotRecordableException;
import com.ssafy.thorp.learning.application.exception.QuestionNotFoundException;
import com.ssafy.thorp.learning.application.exception.RetryNotAvailableException;
import com.ssafy.thorp.learning.application.repository.BadgeAwardRepository;
import com.ssafy.thorp.learning.application.repository.ExpLedgerRepository;
import com.ssafy.thorp.learning.application.repository.LearnerAnswerRepository;
import com.ssafy.thorp.learning.application.repository.LearnerAnswerRepository.Stored;
import com.ssafy.thorp.learning.application.repository.LearnerProgressRepository;
import com.ssafy.thorp.learning.application.repository.LearningArticleRepository;
import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.AnswerResult;
import com.ssafy.thorp.learning.application.result.CompletionResult;
import com.ssafy.thorp.learning.application.result.CurrentArticleEntry;
import com.ssafy.thorp.learning.application.result.LearnerArticleResult;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.ArticleRecord;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.BadgeView;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.Counts;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.LevelView;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.MasteredTerm;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.Milestone;
import com.ssafy.thorp.learning.application.result.LearnerOverviewResult.StageRecord;
import com.ssafy.thorp.learning.application.result.LearnerParts.ContinueReading;
import com.ssafy.thorp.learning.application.result.LearnerParts.Gained;
import com.ssafy.thorp.learning.application.result.LearnerParts.GainedBadge;
import com.ssafy.thorp.learning.application.result.LearnerParts.GainedExp;
import com.ssafy.thorp.learning.application.result.LearnerParts.QuizSummary;
import com.ssafy.thorp.learning.application.result.LearnerParts.StageProgress;
import com.ssafy.thorp.learning.application.result.PublishedArticleView;
import com.ssafy.thorp.learning.application.result.PublishedTermEntry;
import com.ssafy.thorp.learning.domain.model.BadgeCatalog;
import com.ssafy.thorp.learning.domain.model.BadgeDefinition;
import com.ssafy.thorp.learning.domain.model.BadgeStatus;
import com.ssafy.thorp.learning.domain.model.ContentScope;
import com.ssafy.thorp.learning.domain.model.ContentScope.StageScope;
import com.ssafy.thorp.learning.domain.model.Edition;
import com.ssafy.thorp.learning.domain.model.ExpEntry;
import com.ssafy.thorp.learning.domain.model.ExpLedger;
import com.ssafy.thorp.learning.domain.model.FirstAnswer;
import com.ssafy.thorp.learning.domain.model.LearnerFacts;
import com.ssafy.thorp.learning.domain.model.LearnerProgress;
import com.ssafy.thorp.learning.domain.model.LearnerProgress.MasteredVia;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.model.Level;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.Settlement;
import com.ssafy.thorp.learning.domain.model.Settlement.Outcome;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 나의 학습 유스케이스(learning-me-api §1~§7, data-model §11·§12).
 *
 * <p><b>로그인 주체의 기록만 다룬다.</b> 호출자가 넘긴 사용자 식별자 밖의 행을 읽거나 쓰지 않는다. 관리 API와 집계 조회는 이 기록을 읽지 않는다
 * (FR-020).
 *
 * <p><b>기록하는 글은 공개된 글({@code CURRENT} 판이 게시 중인 글)뿐이다.</b> 그 밖의 게시된 글(이전 판·첫 단계 공개 전 글)은 409 {@code
 * LEARNING_NOT_RECORDABLE}, 게시되지 않았거나 없는 글은 404 {@code LEARNING_ARTICLE_UNAVAILABLE}이다.
 *
 * <p><b>정산</b>은 학습 행동(완료·첫 답·다시 풀기)과 {@code GET /learning/me}가 같은 순서로 한다(data-model §12-5). 새 줄은 모두
 * {@code ON CONFLICT DO NOTHING}으로 넣고, 이번 요청이 실제로 넣은 것만 {@code gained}로 돌려준다. 같은 요청을 다시 보내도 경험치와
 * 배지가 두 번 생기지 않는다(SC-015). 기록을 바꾸지 않은 학습 행동(이미 완료한 글의 완료, 이미 답한 문제, 틀린 다시 풀기)은 정산하지 않는다. 운영자 변경으로
 * 채워진 기준은 다음 {@code GET /learning/me}가 정산한다.
 *
 * <p><b>나의 학습을 쓰는 요청은 사용자 단위로 줄을 선다.</b> 정산하는 조회·위치·완료·첫 답·다시 풀기·학습 초기화가 모두 첫 문장에서 {@link
 * LearnerProgressRepository#lockLearner}를 잡는다. 행 잠금만으로는 학습 초기화가 지우는 사이 다른 요청이 넣은 답·장부·배지 행이 초기화 뒤에
 * 남고(SC-015), 같은 사용자의 정산 둘이 서로의 새 줄을 보지 못한다. 나의 학습이 잡는 잠금은 이것 하나다. 관리 경로(단계 행 → 글)는 이 잠금을 잡지 않으므로
 * 서로 기다리지 않는다. 같은 사용자의 사실은 이 서비스만 바꾸므로, 요청 안에서 저장한 변경은 다시 읽지 않고 메모리의 사실에 더해 쓴다.
 *
 * <p>격리 수준은 기본(READ COMMITTED)이다. {@code REPEATABLE_READ}에서는 다른 요청이 먼저 넣은 행과 충돌하는 {@code ON
 * CONFLICT DO NOTHING}이 직렬화 오류로 실패한다.
 */
@Service
@Transactional
public class LearnerService {

    /** 레벨·배지에 '새' 표시를 붙이는 기간(FR-030). */
    static final Duration NEW_PERIOD = Duration.ofDays(7);

    private final LearningStageRepository stageRepository;
    private final LearningArticleRepository articleRepository;
    private final PublishedTermRepository publishedTermRepository;
    private final LearnerProgressRepository progressRepository;
    private final LearnerAnswerRepository answerRepository;
    private final ExpLedgerRepository ledgerRepository;
    private final BadgeAwardRepository badgeRepository;
    private final Clock clock;

    public LearnerService(
            LearningStageRepository stageRepository,
            LearningArticleRepository articleRepository,
            PublishedTermRepository publishedTermRepository,
            LearnerProgressRepository progressRepository,
            LearnerAnswerRepository answerRepository,
            ExpLedgerRepository ledgerRepository,
            BadgeAwardRepository badgeRepository,
            Clock clock) {
        this.stageRepository = stageRepository;
        this.articleRepository = articleRepository;
        this.publishedTermRepository = publishedTermRepository;
        this.progressRepository = progressRepository;
        this.answerRepository = answerRepository;
        this.ledgerRepository = ledgerRepository;
        this.badgeRepository = badgeRepository;
        this.clock = clock;
    }

    /**
     * 나의 학습 전체(§1). 요청마다 정산한다. 운영자의 게시 취소나 단계 이동으로 채워진 단계 완주·배지가 이때 생기고, 그때가 획득 날짜다(FR-024).
     *
     * @throws NotRecordableException 첫 단계를 공개하기 전. 나의 학습은 첫 단계를 공개할 때 켠다(FR-031)
     */
    public LearnerOverviewResult overview(long userId) {
        progressRepository.lockLearner(userId);
        if (!stageRepository.anyReleased()) {
            throw new NotRecordableException();
        }
        Instant now = LearningTime.now(clock);
        Content content = content();
        LearnerFacts before = facts(userId);
        Gains gains = new Gains(Level.levelFor(before.ledger().total()));
        LearnerFacts facts = settle(userId, content, before, now, gains);
        ContentScope scope = content.scope();
        Level level = Settlement.level(scope, facts);

        List<ArticleRecord> articles = new ArrayList<>();
        for (ArticleId article : scope.articles()) {
            LearnerProgress progress = facts.progressOf(article);
            int termCount = scope.termsOf(article).size();
            articles.add(
                    new ArticleRecord(
                            article.value(),
                            LearnerProgress.stateOf(progress).name(),
                            LearnerProgress.displayPercent(progress),
                            progress == null ? null : progress.completedAt(),
                            termCount,
                            facts.mastered(article) ? termCount : 0));
        }
        List<StageRecord> stages =
                scope.stages().stream().map(stage -> stage(stage, facts)).toList();
        List<MasteredTerm> masteredTerms = new ArrayList<>();
        for (ArticleId article : scope.articles()) {
            if (!facts.mastered(article)) {
                continue;
            }
            String number = scope.numberOf(article).orElseThrow();
            for (PublishedTermEntry term : content.termsOf(article)) {
                masteredTerms.add(
                        new MasteredTerm(
                                term.termId().value(), term.label(), article.value(), number));
            }
        }
        BadgeCatalog catalog = BadgeCatalog.of(scope.stages());
        List<BadgeView> badges =
                catalog.definitions().stream()
                        .map(
                                definition ->
                                        badge(
                                                Settlement.evaluate(
                                                        definition, scope, facts, level),
                                                now))
                        .toList();
        return new LearnerOverviewResult(
                levelView(level, facts.ledger(), now),
                new Counts(
                        facts.readCount(scope),
                        facts.masteredTermCount(scope),
                        facts.badges().size(),
                        scope.articleCount(),
                        scope.termCount()),
                continueReading(content, facts),
                articles,
                stages,
                masteredTerms,
                badges,
                gains.result(level.current(), catalog));
    }

    /** 글 하나의 기록(§2). 정산하지 않는다. */
    @Transactional(readOnly = true)
    public LearnerArticleResult article(long userId, String rawArticleId) {
        Content content = content();
        ArticleId articleId = requireRecordableIn(content, rawArticleId);
        LearnerFacts facts = facts(userId);
        LearnerProgress progress = facts.progressOf(articleId);
        List<QuestionId> questions = content.scope().questionsOf(articleId);
        Map<QuestionId, FirstAnswer> answers = facts.answersFor(articleId, questions);
        List<LearnerArticleResult.Answer> ordered = new ArrayList<>();
        for (QuestionId question : questions) {
            FirstAnswer answer = answers.get(question);
            if (answer != null) {
                ordered.add(
                        new LearnerArticleResult.Answer(
                                question.value(),
                                answer.choiceId().value(),
                                answer.correct(),
                                answer.answeredAt()));
            }
        }
        return new LearnerArticleResult(
                articleId.value(),
                LearnerProgress.stateOf(progress).name(),
                LearnerProgress.displayPercent(progress),
                progress == null ? null : progress.completedAt(),
                ordered,
                quiz(progress, answers, questions.size()),
                stageProgress(content.scope(), facts, articleId),
                continueReading(content, facts));
    }

    /**
     * 읽은 위치(§3). 더 큰 비율만 남기고 마지막으로 연 시각은 늘 바꾼다. 행이 없는 글은 '읽는 중'이 된다.
     *
     * @param percent 0~100. 범위는 요청 검증이 본다
     */
    public void recordPosition(long userId, String rawArticleId, int percent) {
        progressRepository.lockLearner(userId);
        ArticleId articleId = requireRecordable(rawArticleId);
        progressRepository.recordPosition(userId, articleId, percent, LearningTime.now(clock));
    }

    /**
     * 완료(§4). 완료와 함께 {@code ARTICLE_COMPLETED} 20과 정산을 한 트랜잭션에서 한다. 이미 완료한 글이면 그때의 시각과 빈 {@code
     * gained}다. 완료를 되돌리는 경로는 없다(FR-021).
     */
    public CompletionResult complete(long userId, String rawArticleId) {
        progressRepository.lockLearner(userId);
        Content content = content();
        ArticleId articleId = requireRecordableIn(content, rawArticleId);
        Instant now = LearningTime.now(clock);
        LearnerFacts before = facts(userId);
        int levelBefore = Level.levelFor(before.ledger().total());
        // 사용자 잠금 안에서 읽었으므로 그사이 다른 요청이 이 글을 완료할 수 없다.
        boolean alreadyCompleted = before.completed(articleId);
        Instant completedAt = progressRepository.recordCompletion(userId, articleId, now);
        if (alreadyCompleted) {
            // 바뀐 것은 마지막으로 연 시각뿐이다. 완료한 글은 '이어서 읽기'에 들지 않으므로 읽어 둔 사실 그대로 답한다.
            return new CompletionResult(
                    completedAt,
                    true,
                    stageProgress(content.scope(), before, articleId),
                    continueReading(content, before),
                    Gained.none(levelBefore));
        }
        Gains gains = new Gains(levelBefore);
        gains.add(ledgerRepository, userId, ExpEntry.articleCompleted(articleId, now));
        LearnerFacts facts = settle(userId, content, facts(userId), now, gains);
        return new CompletionResult(
                completedAt,
                false,
                stageProgress(content.scope(), facts, articleId),
                continueReading(content, facts),
                gains.result(
                        Level.levelFor(facts.ledger().total()),
                        BadgeCatalog.of(content.scope().stages())));
    }

    /**
     * 첫 답(§5). 공개 판으로 판정하고 저장한 뒤 돌려준다. 이미 답한 문제면 저장된 답을 바꾸지 않고 돌려준다.
     *
     * <p>사용자 잠금 안에서 처리하므로 같은 글의 두 답이 동시에 와도 정답 몫 상한과 '처음 모든 문제에 답한 순간'의 판정이 겹치지 않는다(R29). '모두 답함'과
     * '모두 맞힘'은 정산이 판정한다. 운영자가 남은 문제를 지워 모두 답한 상태가 된 경우와 같은 규칙이다.
     */
    public AnswerResult answer(
            long userId, String rawArticleId, String rawQuestionId, String rawChoiceId) {
        progressRepository.lockLearner(userId);
        ArticleId articleId = parseArticleId(rawArticleId);
        List<QuizQuestion> quiz = currentQuiz(articleId);
        LearnerProgress progress =
                progressRepository
                        .find(userId, articleId)
                        .filter(LearnerProgress::completed)
                        .orElseThrow(CompletionRequiredException::new);
        QuizQuestion question = question(quiz, rawQuestionId);
        ChoiceId choice = choice(question, rawChoiceId);
        List<QuestionId> questionIds = quiz.stream().map(QuizQuestion::questionId).toList();

        Instant now = LearningTime.now(clock);
        ExpLedger ledger = ledgerRepository.find(userId);
        int levelBefore = Level.levelFor(ledger.total());
        boolean correct = question.correctChoiceIds().contains(choice);
        Stored stored =
                answerRepository.insertIfAbsent(
                        userId,
                        new FirstAnswer(articleId, question.questionId(), choice, correct, now));
        if (!stored.inserted()) {
            return answerResult(
                    stored.answer(),
                    question,
                    true,
                    quizSummary(userId, articleId, questionIds),
                    Gained.none(levelBefore));
        }

        Gains gains = new Gains(levelBefore);
        if (correct) {
            int share = ledger.correctShare(articleId, quiz.size(), progress.allAnswered());
            if (share > 0) {
                gains.add(
                        ledgerRepository,
                        userId,
                        ExpEntry.answerCorrect(articleId, question.questionId(), share, now));
            }
            if (!progress.mastered()) {
                progressRepository.markMastered(userId, articleId, MasteredVia.FIRST_ANSWER, now);
            }
        }
        Content content = content();
        LearnerFacts facts = settle(userId, content, facts(userId), now, gains);
        return answerResult(
                stored.answer(),
                question,
                false,
                quiz(
                        facts.progressOf(articleId),
                        facts.answersFor(articleId, questionIds),
                        quiz.size()),
                gains.result(
                        Level.levelFor(facts.ledger().total()),
                        BadgeCatalog.of(content.scope().stages())));
    }

    /**
     * 다시 풀기(§6). 공개 판의 문제에 모두 답했고 첫 답이 하나도 맞지 않았으며 아직 익힘이 아닌 글만 받는다. 답을 저장하지 않고 경험치를 주지 않는다. 맞히면 그
     * 글이 {@code RETRY}로 익힘이 된다(FR-022, FR-028).
     */
    public AnswerResult retry(
            long userId, String rawArticleId, String rawQuestionId, String rawChoiceId) {
        progressRepository.lockLearner(userId);
        ArticleId articleId = parseArticleId(rawArticleId);
        List<QuizQuestion> quiz = currentQuiz(articleId);
        List<QuestionId> questionIds = quiz.stream().map(QuizQuestion::questionId).toList();
        LearnerFacts before = facts(userId);
        LearnerProgress progress = before.progressOf(articleId);
        if (progress == null) {
            throw new RetryNotAvailableException();
        }
        Map<QuestionId, FirstAnswer> answers = before.answersFor(articleId, questionIds);
        if (!quiz(progress, answers, quiz.size()).retryAvailable()) {
            throw new RetryNotAvailableException();
        }
        QuizQuestion question = question(quiz, rawQuestionId);
        ChoiceId choice = choice(question, rawChoiceId);
        boolean correct = question.correctChoiceIds().contains(choice);
        int levelBefore = Level.levelFor(before.ledger().total());
        if (!correct) {
            // 틀린 다시 풀기는 아무것도 저장하지 않는다.
            return retryResult(
                    question,
                    choice,
                    false,
                    quiz(progress, answers, quiz.size()),
                    Gained.none(levelBefore));
        }

        Instant now = LearningTime.now(clock);
        progressRepository.markMastered(userId, articleId, MasteredVia.RETRY, now);
        Content content = content();
        Gains gains = new Gains(levelBefore);
        LearnerFacts facts =
                settle(
                        userId,
                        content,
                        before.withProgress(progress.withMastered(MasteredVia.RETRY, now)),
                        now,
                        gains);
        return retryResult(
                question,
                choice,
                true,
                quiz(facts.progressOf(articleId), answers, quiz.size()),
                gains.result(
                        Level.levelFor(facts.ledger().total()),
                        BadgeCatalog.of(content.scope().stages())));
    }

    private static AnswerResult retryResult(
            QuizQuestion question,
            ChoiceId choice,
            boolean correct,
            QuizSummary quiz,
            Gained gained) {
        return new AnswerResult(
                question.questionId().value(),
                choice.value(),
                correct,
                onlyCorrectChoice(question).value(),
                question.explanation(),
                null,
                quiz,
                gained);
    }

    /** 학습 초기화(§7). 네 테이블의 그 사용자 행을 한 트랜잭션에서 지운다. 기록이 없어도 성공이다(FR-025). */
    public void reset(long userId) {
        progressRepository.lockLearner(userId);
        badgeRepository.deleteAllForUser(userId);
        ledgerRepository.deleteAllForUser(userId);
        answerRepository.deleteAllForUser(userId);
        progressRepository.deleteAllForUser(userId);
    }

    // ── 정산 ────────────────────────────────────────────────────────────────

    /**
     * 정산 결과를 넣고, 이번 요청이 실제로 넣은 것만 {@code gains}에 담는다.
     *
     * @return 정산을 반영한 사실. 사용자 잠금 안이라 다시 읽은 것과 같다
     */
    private LearnerFacts settle(
            long userId, Content content, LearnerFacts facts, Instant now, Gains gains) {
        BadgeCatalog catalog = BadgeCatalog.of(content.scope().stages());
        Outcome outcome = Settlement.settle(content.scope(), facts, catalog, now);
        LearnerFacts settled = facts;
        for (ArticleId article : outcome.allAnswered()) {
            progressRepository.markAllAnswered(userId, article, now);
            settled = settled.withProgress(settled.progressOf(article).withAllAnswered(now));
        }
        for (ExpEntry entry : outcome.entries()) {
            gains.add(ledgerRepository, userId, entry);
        }
        for (BadgeKey badge : outcome.badges()) {
            if (badgeRepository.award(userId, badge, now)) {
                gains.badges.add(badge);
            }
        }
        return settled.withLedger(settled.ledger().plus(outcome.entries()))
                .withBadges(outcome.badges(), now);
    }

    private LearnerFacts facts(long userId) {
        Map<ArticleId, LearnerProgress> progress = new HashMap<>();
        progressRepository.findAll(userId).forEach(row -> progress.put(row.articleId(), row));
        return new LearnerFacts(
                progress,
                answerRepository.findAll(userId),
                ledgerRepository.find(userId),
                badgeRepository.findAll(userId));
    }

    /**
     * 지금 공개된 콘텐츠. 단계, 공개된 글의 읽는 순서, 공개 판의 문제 식별자, 공개 용어를 읽는다. 판 본문은 읽지 않는다. 판 하나의 본문이 깨져도 나의 학습이
     * 나가야 한다.
     */
    private Content content() {
        List<LearningStage> stages = stageRepository.findAllOrdered();
        List<CurrentArticleEntry> order = articleRepository.findCurrentInReadingOrder();
        Map<String, List<ArticleId>> byStage = new HashMap<>();
        Map<ArticleId, String> titles = new HashMap<>();
        for (CurrentArticleEntry entry : order) {
            ArticleId article = new ArticleId(entry.articleId());
            byStage.computeIfAbsent(entry.stageKey(), key -> new ArrayList<>()).add(article);
            titles.put(article, entry.title());
        }
        List<StageScope> stageScopes = new ArrayList<>();
        for (LearningStage stage : stages) {
            stageScopes.add(
                    new StageScope(
                            stage.stageKey(),
                            stage.number(),
                            stage.released(),
                            byStage.getOrDefault(stage.stageKey().value(), List.of())));
        }
        Map<ArticleId, List<PublishedTermEntry>> terms = new LinkedHashMap<>();
        for (PublishedTermEntry term : publishedTermRepository.findPublishedEntries()) {
            if (titles.containsKey(term.articleId())) {
                terms.computeIfAbsent(term.articleId(), key -> new ArrayList<>()).add(term);
            }
        }
        Map<ArticleId, List<QuestionId>> questions = new HashMap<>();
        articleRepository
                .findCurrentQuestionIds()
                .forEach(
                        (article, ids) -> {
                            if (titles.containsKey(article)) {
                                questions.put(article, ids);
                            }
                        });
        Map<ArticleId, List<TermId>> termIds = new HashMap<>();
        terms.forEach(
                (article, entries) ->
                        termIds.put(
                                article,
                                entries.stream().map(PublishedTermEntry::termId).toList()));
        return new Content(new ContentScope(stageScopes, questions, termIds), titles, terms);
    }

    // ── 응답 조각 ──────────────────────────────────────────────────────────

    /**
     * '이어서 읽기'(data-model §12-2). 마지막으로 연 '읽는 중' 글을 기록된 위치에서, 없으면 읽는 순서상 첫 '안 읽음' 글을 맨 위에서, 모두
     * 완료했으면 {@code ALL_DONE}이다.
     */
    private static ContinueReading continueReading(Content content, LearnerFacts facts) {
        ContentScope scope = content.scope();
        boolean morePreparing = !scope.allStagesReleased();
        List<ArticleId> order = scope.articles();
        Optional<ArticleId> resume =
                order.stream()
                        .filter(
                                article ->
                                        LearnerProgress.stateOf(facts.progressOf(article))
                                                == LearnerProgress.ArticleState.READING)
                        .max(
                                Comparator.comparing(
                                                (ArticleId article) ->
                                                        facts.progressOf(article).lastOpenedAt())
                                        .thenComparing(article -> -order.indexOf(article)));
        if (resume.isPresent()) {
            ArticleId article = resume.get();
            return new ContinueReading(
                    "RESUME",
                    article.value(),
                    scope.numberOf(article).orElseThrow(),
                    content.titles().get(article),
                    LearnerProgress.displayPercent(facts.progressOf(article)),
                    morePreparing);
        }
        Optional<ArticleId> start =
                order.stream().filter(article -> facts.progressOf(article) == null).findFirst();
        if (start.isPresent()) {
            ArticleId article = start.get();
            return new ContinueReading(
                    "START",
                    article.value(),
                    scope.numberOf(article).orElseThrow(),
                    content.titles().get(article),
                    null,
                    morePreparing);
        }
        return new ContinueReading("ALL_DONE", null, null, null, null, morePreparing);
    }

    private static StageProgress stageProgress(
            ContentScope scope, LearnerFacts facts, ArticleId articleId) {
        StageScope stage =
                scope.stageOf(articleId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recordable article has no published stage: "
                                                        + articleId));
        return new StageProgress(
                stage.number(),
                facts.completedIn(stage),
                stage.articles().size(),
                facts.stageCompleted(stage));
    }

    private static StageRecord stage(StageScope stage, LearnerFacts facts) {
        if (!stage.released()) {
            return new StageRecord(
                    stage.stageKey().value(), stage.number(), "PREPARING", null, null, false);
        }
        if (stage.articles().isEmpty()) {
            return new StageRecord(
                    stage.stageKey().value(), stage.number(), "REPREPARING", 0, 0, false);
        }
        return new StageRecord(
                stage.stageKey().value(),
                stage.number(),
                "OPEN",
                facts.completedIn(stage),
                stage.articles().size(),
                facts.stageCompleted(stage));
    }

    /** 확인 문제 요약. 공개 판의 문제와 그 첫 답만 센다. 다시 풀기는 모든 문제에 답했고 첫 답 정답이 0개이며 익힘이 아닐 때만 열린다. */
    private static QuizSummary quiz(
            LearnerProgress progress, Map<QuestionId, FirstAnswer> answers, int total) {
        int answered = answers.size();
        int correct = (int) answers.values().stream().filter(FirstAnswer::correct).count();
        boolean allAnswered = total > 0 && answered == total;
        boolean mastered = progress != null && progress.mastered();
        return new QuizSummary(
                total,
                answered,
                correct,
                allAnswered,
                mastered,
                mastered ? progress.masteredVia().name() : null,
                allAnswered && correct == 0 && !mastered);
    }

    private QuizSummary quizSummary(long userId, ArticleId articleId, List<QuestionId> questions) {
        return quiz(
                progressRepository.find(userId, articleId).orElse(null),
                byQuestion(answerRepository.findByArticle(userId, articleId), questions),
                questions.size());
    }

    private static AnswerResult answerResult(
            FirstAnswer stored,
            QuizQuestion question,
            boolean alreadyAnswered,
            QuizSummary quiz,
            Gained gained) {
        return new AnswerResult(
                question.questionId().value(),
                stored.choiceId().value(),
                stored.correct(),
                onlyCorrectChoice(question).value(),
                question.explanation(),
                alreadyAnswered,
                quiz,
                gained);
    }

    private static LevelView levelView(Level level, ExpLedger ledger, Instant now) {
        Instant reachedAt = ledger.reachedAt(level.currentThreshold());
        return new LevelView(
                level.current(),
                level.exp(),
                level.currentThreshold(),
                level.nextThreshold(),
                level.remainingToNext(),
                reachedAt,
                isNew(reachedAt, now),
                level.visibleMax(),
                level.unreachableFrom(),
                level.unreachableReason().name(),
                level.milestones().stream()
                        .map(milestone -> new Milestone(milestone.level(), milestone.threshold()))
                        .toList());
    }

    private static BadgeView badge(BadgeStatus status, Instant now) {
        BadgeDefinition definition = status.definition();
        return new BadgeView(
                definition.key().value(),
                definition.family().name(),
                definition.name(),
                definition.criterion(),
                status.state().name(),
                status.earnedAt(),
                isNew(status.earnedAt(), now),
                status.progress() == null
                        ? null
                        : new LearnerOverviewResult.Progress(
                                status.progress().current(), status.progress().target()),
                status.reason() == null ? null : status.reason().name(),
                definition.stageNumber());
    }

    private static boolean isNew(Instant at, Instant now) {
        return at != null && !at.isBefore(now.minus(NEW_PERIOD));
    }

    // ── 확인과 해석 ────────────────────────────────────────────────────────

    /**
     * 기록할 수 있는 글인지 본다. 판 본문을 읽지 않는다.
     *
     * @throws ArticleUnavailableException 없거나 게시되지 않은 글, 형식이 틀린 식별자
     * @throws NotRecordableException 이전 판·첫 단계 공개 전 글
     */
    private ArticleId requireRecordable(String rawArticleId) {
        ArticleId articleId = parseArticleId(rawArticleId);
        Edition edition =
                articleRepository
                        .findPublishedEdition(articleId)
                        .orElseThrow(ArticleUnavailableException::new);
        if (edition != Edition.CURRENT) {
            throw new NotRecordableException();
        }
        return articleId;
    }

    /**
     * 공개 범위 하나로 기록할 수 있는 글인지 정한다. 범위에 있으면 기록할 수 있다. 없으면 왜 없는지만 따로 읽어 이전 판·첫 단계 공개 전 글은 409, 그 밖은
     * 404로 답한다. 같은 사실을 두 번 읽어 판정하면 그사이 게시 취소가 커밋될 때 두 판정이 어긋난다(learning-me-api §0).
     *
     * @throws ArticleUnavailableException 없거나 게시되지 않은 글, 형식이 틀린 식별자. 범위를 읽은 뒤에 게시된 글도 이번에는 없는 글로 본다
     * @throws NotRecordableException 이전 판·첫 단계 공개 전 글
     */
    private ArticleId requireRecordableIn(Content content, String rawArticleId) {
        ArticleId articleId = parseArticleId(rawArticleId);
        if (content.scope().isPublished(articleId)) {
            return articleId;
        }
        if (articleRepository
                .findPublishedEdition(articleId)
                .filter(edition -> edition != Edition.CURRENT)
                .isPresent()) {
            throw new NotRecordableException();
        }
        throw new ArticleUnavailableException();
    }

    /** 기록할 수 있는 글의 공개 판 문제. 답을 판정하려면 정답과 풀이가 있는 판을 읽어야 한다. */
    private List<QuizQuestion> currentQuiz(ArticleId articleId) {
        PublishedArticleView view =
                articleRepository
                        .findPublished(articleId)
                        .orElseThrow(ArticleUnavailableException::new);
        if (view.revision().edition() != Edition.CURRENT) {
            throw new NotRecordableException();
        }
        return view.revision().snapshot().quiz();
    }

    private static QuizQuestion question(List<QuizQuestion> quiz, String rawQuestionId) {
        QuestionId questionId;
        try {
            questionId = new QuestionId(rawQuestionId);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new QuestionNotFoundException();
        }
        return quiz.stream()
                .filter(question -> questionId.equals(question.questionId()))
                .findFirst()
                .orElseThrow(QuestionNotFoundException::new);
    }

    private static ChoiceId choice(QuizQuestion question, String rawChoiceId) {
        ChoiceId choiceId;
        try {
            choiceId = new ChoiceId(rawChoiceId);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new QuestionNotFoundException();
        }
        if (!question.choiceIds().contains(choiceId)) {
            throw new QuestionNotFoundException();
        }
        return choiceId;
    }

    /** 게시된 문제의 정답 보기. 게시 전 검사가 하나임을 보장한다. 아니면 판이 깨진 것이라 실패시킨다. */
    private static ChoiceId onlyCorrectChoice(QuizQuestion question) {
        if (question.correctChoiceIds().size() != 1) {
            throw new IllegalStateException(
                    "a published question must have exactly one correct choice: "
                            + question.questionId());
        }
        return question.correctChoiceIds().iterator().next();
    }

    private static Map<QuestionId, FirstAnswer> byQuestion(
            List<FirstAnswer> answers, List<QuestionId> questions) {
        return answers.stream()
                .filter(answer -> questions.contains(answer.questionId()))
                .collect(Collectors.toMap(FirstAnswer::questionId, Function.identity()));
    }

    private static ArticleId parseArticleId(String raw) {
        return ArticleId.parse(raw).orElseThrow(ArticleUnavailableException::new);
    }

    /**
     * 지금 공개된 콘텐츠와 응답에 쓸 글 제목·공개 용어.
     *
     * @param titles 공개된 글의 공개 판 제목
     * @param terms 공개된 글마다 공개 용어, {@code termId} 순서
     */
    private record Content(
            ContentScope scope,
            Map<ArticleId, String> titles,
            Map<ArticleId, List<PublishedTermEntry>> terms) {

        List<PublishedTermEntry> termsOf(ArticleId articleId) {
            return terms.getOrDefault(articleId, List.of());
        }
    }

    /** 이번 요청이 실제로 넣은 장부 줄과 배지. */
    private static final class Gains {

        private final int levelBefore;
        private final List<ExpEntry> exp = new ArrayList<>();
        private final List<BadgeKey> badges = new ArrayList<>();

        Gains(int levelBefore) {
            this.levelBefore = levelBefore;
        }

        void add(ExpLedgerRepository repository, long userId, ExpEntry entry) {
            if (repository.add(userId, entry)) {
                exp.add(entry);
            }
        }

        Gained result(int levelAfter, BadgeCatalog catalog) {
            Map<BadgeKey, BadgeDefinition> definitions = new HashMap<>();
            catalog.definitions()
                    .forEach(definition -> definitions.put(definition.key(), definition));
            Map<StageKey, Integer> stageNumbers = new HashMap<>();
            catalog.definitions().stream()
                    .filter(definition -> definition.stageKey() != null)
                    .forEach(
                            definition ->
                                    stageNumbers.put(
                                            definition.stageKey(), definition.stageNumber()));
            return new Gained(
                    exp.stream()
                            .map(
                                    entry ->
                                            new GainedExp(
                                                    entry.kind().name(),
                                                    entry.points(),
                                                    entry.stageKey() == null
                                                            ? null
                                                            : stageNumbers.get(entry.stageKey())))
                            .toList(),
                    exp.stream().mapToInt(ExpEntry::points).sum(),
                    badges.stream()
                            .map(definitions::get)
                            .map(
                                    definition ->
                                            new GainedBadge(
                                                    definition.key().value(),
                                                    definition.family().name(),
                                                    definition.name()))
                            .toList(),
                    levelBefore,
                    levelAfter);
        }
    }
}
