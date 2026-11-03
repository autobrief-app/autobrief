# CI 안내

`.github/workflows/emulator-e2e.yml`은 깃허브 Actions(KVM이 되는 리눅스 러너)에서 Pleos Connect 에뮬레이터를 띄워
`demo/setup_emulator.py`와 `demo/director.py --verify`로 앱을 실제 이미지에서 시험하는 작업입니다.

2026-10-26부터 켜져 있습니다. main으로 가는 PR에 `app/`, `demo/`, 작업 파일이 바뀌어 올라올 때마다 돌고, 합친 뒤 main에서
한 번 더 돕니다. Actions 화면에서 직접 실행할 수도 있습니다. 같은 PR에 새 커밋이 오면 앞선 PR 실행은 취소되고, main 실행은
취소하지 않아 main의 커밋마다 결과가 남습니다.
결과(화면 녹화 `e2e.mp4`, logcat, 관리자 화면 상태)는 실행 화면의 `e2e-out` 묶음으로 받습니다.

KVM이 없으면 작업이 바로 멈추게 했습니다. 하드웨어 가속 없이 소프트웨어로만 돌리면 이 이미지는 화면 켜기 단계에서 차량 서비스가
올라오지 않아 앱을 열 수 없습니다(2026-10-25 가속 없는 서버에서 확인).

`.github/workflows/demo-rehearsal.yml`은 손으로 실행할 때만 도는 7분 시연 전체 리허설입니다(`demo/rehearsal.py`, 장면별 PASS/FAIL,
화면 녹화와 관리자 화면 캡처, `rehearsal-out` 묶음). push로는 돌지 않습니다. 작업 브랜치에서 돌릴 때는
`gh workflow run demo-rehearsal.yml --ref <브랜치>`로 실행합니다.

작업 순서(브랜치, PR, 합치기)와 실패한 실행 기록을 지우지 않는 규칙은 [CONTRIBUTING.md](../CONTRIBUTING.md)에 있습니다.
