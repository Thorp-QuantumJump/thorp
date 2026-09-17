import hudson.ExtensionList
import hudson.XmlFile
import hudson.model.Saveable
import hudson.model.listeners.SaveableListener
import hudson.plugins.throttleconcurrents.ThrottleJobProperty
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty

// thorp-ci 를 동시에 최대 2개까지만 실행한다. 실행 슬롯은 3개이고(10-build-executors.groovy),
// 나머지 하나는 배포 몫이다. 배포 Job 은 자기 Jenkinsfile 의 disableConcurrentBuilds() 로 1개를 넘지 않는다.
//
// 이 제한을 Jenkinsfile 이 아니라 여기서 거는 이유는, thorp-ci 가 Jenkinsfile 을 MR 소스 브랜치에서
// 읽었기 때문이다. dev 를 반영하지 않은 브랜치가 빌드되면 그 브랜치의 옛 Jenkinsfile 이 Job 속성을
// 바꿨다. 실제로 disableConcurrentBuilds() 를 뺀 뒤에도 옛 브랜치 빌드가 그것을 다시 켜서 CI 가
// 하나씩만 돌았다(2026-09-17, S15P21E205-319). Declarative 는 자기가 선언하지 않은 속성은 건드리지
// 않으므로, 여기서 붙인 throttle 속성은 어느 브랜치가 빌드되어도 남는다.
//
// S15P21E205-332 부터는 Jenkinsfile 을 dev 에서 읽는다. 그래도 Job 설정의 브랜치를 되돌리면 같은 일이
// 다시 생기므로 이 리스너는 두고, 지울지는 옛 브랜치가 정리된 뒤 판단한다(infra/jenkins/README.md).
//
// throttle 은 대기열 단계에서 막는다. 그래서 2개를 넘는 빌드는 시작하지 않고 Pending 에 남으며,
// 대기열에 있으므로 GitLab 트리거의 "대기 중인 이전 빌드 취소"도 동작한다. 실행 슬롯만으로 막으면
// 빌드가 시작한 뒤 node 앞에서 기다려 대기열에 남지 않고, 그 취소가 걸리지 않는다.
class ThorpCiConcurrency extends SaveableListener {
    static final String JOB = 'thorp-ci'
    static final int MAX_CONCURRENT = 2

    // 옛 브랜치 빌드가 Job 설정을 저장할 때마다 불린다. 속성을 고치면 다시 저장되어 한 번 더
    // 불리지만, 그때는 고칠 것이 없어 멈춘다.
    @Override
    void onChange(Saveable o, XmlFile file) {
        if (o instanceof WorkflowJob && o.fullName == JOB) {
            enforce(o)
        }
    }

    static void enforce(WorkflowJob job) {
        if (job.getProperty(DisableConcurrentBuildsJobProperty) != null) {
            job.removeProperty(DisableConcurrentBuildsJobProperty)
            println("Thorp: removed disableConcurrentBuilds from ${JOB}")
        }

        ThrottleJobProperty throttle = job.getProperty(ThrottleJobProperty)
        boolean ok = throttle != null &&
                throttle.throttleEnabled &&
                throttle.throttleOption == 'project' &&
                throttle.maxConcurrentTotal == MAX_CONCURRENT
        if (!ok) {
            if (throttle != null) {
                job.removeProperty(ThrottleJobProperty)
            }
            // 인자 순서: 노드당 상한(0=제한 없음), 전체 상한, 카테고리, 사용 여부, 방식,
            // 같은 파라미터끼리만 제한, 그 파라미터 목록, matrix 옵션
            job.addProperty(new ThrottleJobProperty(0, MAX_CONCURRENT, [], true, 'project', false, '', null))
            println("Thorp: ${JOB} throttled to ${MAX_CONCURRENT} concurrent builds")
        }
    }

    // 같은 MR 에 새 커밋이 오면 아직 대기열에 있는 이전 빌드를 버린다. Jenkinsfile 의 triggers 로
    // 옮기지 않는 이유는 그 블록이 Job 에 저장된 웹훅 Secret token 까지 덮어쓰기 때문이다.
    // gitlab-plugin 클래스를 import 하지 않고 이름으로 찾는다. 플러그인이 없거나 이름이 바뀌어도
    // Jenkins 기동을 막지 않고 로그만 남긴다.
    static void enableCancelPendingOnUpdate(WorkflowJob job) {
        PipelineTriggersJobProperty triggers = job.getProperty(PipelineTriggersJobProperty)
        def gitlab = triggers?.triggers?.find { it.class.name == 'com.dabsquared.gitlabjenkins.GitLabPushTrigger' }
        if (gitlab == null) {
            println("Thorp: GitLab trigger not found on ${JOB}; cancel-pending not applied")
            return
        }
        if (!gitlab.cancelPendingBuildsOnUpdate) {
            gitlab.cancelPendingBuildsOnUpdate = true
            job.save()
            println("Thorp: ${JOB} cancels pending MR builds on update")
        }
    }
}

ExtensionList.lookup(SaveableListener).add(new ThorpCiConcurrency())

WorkflowJob ci = Jenkins.get().getItemByFullName(ThorpCiConcurrency.JOB, WorkflowJob)
if (ci == null) {
    println("Thorp: ${ThorpCiConcurrency.JOB} not found; concurrency policy will apply when it is saved")
} else {
    ThorpCiConcurrency.enforce(ci)
    ThorpCiConcurrency.enableCancelPendingOnUpdate(ci)
    println("Thorp: ${ThorpCiConcurrency.JOB} max ${ThorpCiConcurrency.MAX_CONCURRENT} concurrent, extra builds stay pending.")
}
