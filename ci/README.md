# CI 파일 보관

`github-emulator-e2e.yml`은 깃허브 Actions(KVM이 되는 리눅스 러너)에서 Pleos Connect 에뮬레이터를 띄워
`demo/setup_emulator.py`와 `demo/director.py --verify`로 앱을 실제 이미지에서 시험하는 작업입니다.

깃허브는 `.github/workflows/` 아래 파일만 실행합니다. 이 저장소에 올리는 데 쓰는 토큰에 `workflow` 권한이 생기면
`git mv ci/github-emulator-e2e.yml .github/workflows/emulator-e2e.yml` 로 옮겨 켭니다.

KVM이 없으면 작업이 바로 멈추게 했습니다. 하드웨어 가속 없이 소프트웨어로만 돌리면 이 이미지는 화면 켜기 단계에서 차량 서비스가
올라오지 않아 앱을 열 수 없습니다(2026-10-25 가속 없는 서버에서 확인).
