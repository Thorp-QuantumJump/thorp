import jenkins.model.Jenkins

// 4 vCPU 호스트를 서비스와 공유한다. CI와 CD 모두 built-in 노드에서 agent any로 실행한다.
// 슬롯 하나로 두 Job을 줄 세웠더니 MR이 몰릴 때 대기가 길고, 한 빌드가 멈추면 전체가 멈췄다.
// 두 개까지만 동시에 돌리고, 동시 실행 중 메모리 최고값을 측정한 뒤 더 늘릴지 정한다
// (S15P21E205-319, infra/jenkins/README.md).
// 별도 에이전트나 Pipeline parallel을 추가하면 이 정책도 함께 재검토한다.
def controller = Jenkins.get()
controller.setNumExecutors(2)
controller.save()
println('Thorp: built-in executors=2; CI and CD share two build slots.')
