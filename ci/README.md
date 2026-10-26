# CI 안내

`.github/workflows/emulator-e2e.yml`은 깃허브 Actions(KVM이 되는 리눅스 러너)에서 Pleos Connect 에뮬레이터를 띄워
`demo/setup_emulator.py`와 `demo/director.py --verify`로 앱을 실제 이미지에서 시험하는 작업입니다.

2026-10-26부터 켜져 있습니다. main에 `app/`, `demo/`, 작업 파일이 바뀌어 올라가거나 Actions 화면에서 직접 실행하면 돕니다.
결과(화면 녹화 `e2e.mp4`, logcat, 관리자 화면 상태)는 실행 화면의 `e2e-out` 묶음으로 받습니다.

KVM이 없으면 작업이 바로 멈추게 했습니다. 하드웨어 가속 없이 소프트웨어로만 돌리면 이 이미지는 화면 켜기 단계에서 차량 서비스가
올라오지 않아 앱을 열 수 없습니다(2026-10-25 가속 없는 서버에서 확인).
