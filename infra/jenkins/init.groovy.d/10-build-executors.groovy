import jenkins.model.Jenkins

// 4 vCPU 호스트를 서비스와 공유한다. CI와 CD 모두 built-in 노드에서
// agent any로 실행하므로 executor 하나로 두 Job의 무거운 작업을 직렬화한다.
// 별도 에이전트나 Pipeline parallel을 추가하면 이 정책도 함께 재검토한다.
def controller = Jenkins.get()
controller.setNumExecutors(1)
controller.save()
println('Thorp: built-in executors=1; CI and CD share one build slot.')
