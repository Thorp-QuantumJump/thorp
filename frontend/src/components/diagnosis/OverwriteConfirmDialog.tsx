import { AlertDialog } from 'radix-ui'

/**
 * 배우기 예시가 기존 입력을 덮어써도 되는지 묻는다(FR-012, SC-011).
 *
 * **이미 입력한 섹터나 비중이 있을 때만 나타난다.** 아무것도 입력하지 않은 상태에서는 묻지 않고
 * 바로 채운다. 그 판정은 이 컴포넌트가 아니라 `useLearningExampleEntry`가 하며, 여기서는 열려
 * 있는 동안 선택지 둘을 보이는 일만 한다.
 *
 * 화면 안 배너가 아니라 대화상자인 까닭은 FR-012가 "먼저 묻고" 답을 받으라고 요구하기 때문이다.
 * 배너는 무시하고 진행할 수 있어, 예시 값이 반영되지 않은 채로 남으면 배우기에서 넘어온 목적이
 * 사라진다(research.md 결정 7).
 *
 * `radix-ui`의 `AlertDialog`를 쓴다. 이미 의존성에 있고, 014의 인증 화면이 접근성 기준을 얻은
 * 것과 같은 방식으로 초점 가두기·`Esc` 닫기·제목과 설명 연결을 그대로 얻는다
 * (`checklists/ux.md` CHK033). **직접 만든 대화상자로 대신하지 않는다.** 초점 관리를 손으로
 * 다시 구현하면 키보드만 쓰는 사용자가 대화상자 밖으로 빠져나가는 경로가 생긴다.
 *
 * `AlertDialog`는 바깥을 눌러 닫히지 않는다. 답을 받아야 하는 물음이므로 실수로 닫혀 어느 쪽도
 * 고르지 않은 상태가 되면 안 된다. `Esc`로 닫는 것은 '덮어쓰지 않음'과 같게 다룬다 — 기존 값을
 * 지키는 쪽이 사용자 입력을 잃지 않는 방향이다(원칙 VI).
 *
 * 화면 문구는 이 작업이 확정하지 않는다. 문장이 놓일 자리와 나타나는 조건까지만 만들었고 문구는
 * UI 결정 항목이 정해질 때 채운다.
 */

type OverwriteConfirmDialogProps = {
  /** 열려 있는가. 묻는 중인 예시가 있을 때만 true다. */
  open: boolean
  /** 예시가 채울 섹터 개수다. 무엇이 바뀌는지 가늠할 수 있게 문장에 담는다. */
  exampleSectorCount: number
  /** 덮어쓴다. */
  onConfirm: () => void
  /** 덮어쓰지 않는다. 기존 값을 그대로 둔다. */
  onCancel: () => void
}

export function OverwriteConfirmDialog({
  open,
  exampleSectorCount,
  onConfirm,
  onCancel,
}: OverwriteConfirmDialogProps) {
  return (
    <AlertDialog.Root
      open={open}
      onOpenChange={(next) => {
        // 닫히는 모든 경로(`Esc` 포함)를 '덮어쓰지 않음'으로 받는다.
        if (!next) onCancel()
      }}
    >
      <AlertDialog.Portal>
        <AlertDialog.Overlay data-slot="overwrite-overlay" />
        <AlertDialog.Content data-slot="overwrite-dialog">
          <AlertDialog.Title>입력해 둔 값을 예시로 바꿀까요?</AlertDialog.Title>
          <AlertDialog.Description>
            배우기에서 가져온 예시는 섹터 {exampleSectorCount}개와 그 비중을 채웁니다. 지금 골라
            둔 섹터와 입력한 비중은 사라집니다.
          </AlertDialog.Description>

          <div data-slot="overwrite-actions">
            {/* 기존 값을 지키는 쪽을 먼저 둔다. */}
            <AlertDialog.Cancel data-slot="overwrite-cancel">그대로 두기</AlertDialog.Cancel>
            <AlertDialog.Action onClick={onConfirm} data-slot="overwrite-confirm">
              예시로 바꾸기
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
