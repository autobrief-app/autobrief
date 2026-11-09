# 시연 도구 (demo kit)

발표 노트북 한 대에서 "Pleos Connect v2.1.0" 에뮬레이터를 한 번에 켜고, 우리 앱을 설치하고,
운행 시나리오(출퇴근, 고객사 방문, 개인 이동)를 가상 콘솔로 똑같이 재생하는 도구입니다.
파이썬 3.9 이상의 기본 기능만 쓰므로 `pip install`이 필요 없습니다(Mac 기본 `python3`도 됩니다).

| 파일 | 하는 일 |
|---|---|
| `setup_emulator.py` | 이미지 내려받기(이어받기, 체크섬 확인), 설치, AVD 만들기, 콜드 부팅, APK 설치, 가상 콘솔 연결 |
| `director.py` | 가상 콘솔 HTTP로 기어, 계기판, 속도, 좌석을 바꾸며 시나리오 재생. `--verify`로 앱 로그까지 확인 |
| `rehearsal.py` | `docs/DEMO.md` 7분 대본 전체(장면 1~7)를 무인으로 리허설: 실제 탭(화면 글자로 찾음), 장면별 PASS/FAIL, 영상용 `scenes.json` |
| `test_demo_kit.py` | 장치 없이 도는 단위 시험 (`python3 -m unittest discover -s demo -v`) |
| `../.github/workflows/emulator-e2e.yml` | 같은 흐름을 GitHub 서버(KVM)에서 화면 없이 자동 시험하고 화면을 녹화 |
| `../.github/workflows/demo-rehearsal.yml` | `rehearsal.py`로 7분 대본 전체를 GitHub 서버(KVM)에서 리허설하고 녹화 (손으로 실행할 때만) |

## 1. 준비물

- 컴퓨터 사양: 그래픽 카드가 꼭 필요합니다(공식 안내 "그래픽 카드 필수").
  최소 기준은 RTX 3060 Laptop 6GB 또는 Radeon RX 6600M 급입니다. Apple 칩 Mac은 arm64 이미지를 씁니다.
- 디스크 여유 공간: Mac/Linux 약 10 GB, Windows 약 25 GB (Windows는 빈 공간을 줄여 저장하는 기능을 못 써서 더 듭니다).
- Android Studio 설치 후 `SDK Manager > SDK Tools` 탭에서 `Android Emulator`와 `Android SDK Platform-Tools`를 체크하고 Apply.
  SDK가 기본 위치(Mac `~/Library/Android/sdk`, Windows `%LOCALAPPDATA%\Android\Sdk`, Linux `~/Android/Sdk`)가 아니면
  환경 변수 `ANDROID_HOME`에 SDK 폴더를 지정합니다.
- 파이썬 3.9 이상 (`python3 --version` 또는 Windows `py -3 --version`).
- 시연용 **디버그 APK**: `./gradlew assembleDebug` (Windows `gradlew.bat assembleDebug`, JDK 17)
  결과물 `app/build/outputs/apk/debug/app-debug.apk`.
  가상 샘플 불러오기(`--demo-sample`)는 디버그 APK에만 들어 있습니다. 배포용 릴리스 APK에는 없습니다.
- 인터넷: 처음 한 번 `nexus-playground.pleos.ai`에서 이미지(약 4~5 GB)를 받습니다. 한 번 받으면 다시 받지 않습니다.

이미지는 컴퓨터 종류를 보고 자동으로 고릅니다: Apple 칩 Mac은 arm64, Intel Mac, Windows, Linux는 x86_64.
이미지는 42dot의 Pleos Connect SDK 라이선스 대상입니다. 약관 전문은 내려받기 전에 출력되는 XML 주소의 `license` 항목에 있습니다.

## 2. Mac에서 처음 켜기

1. 터미널을 열고 저장소 폴더로 이동합니다.
2. 디버그 APK를 만듭니다: `./gradlew assembleDebug`
3. 한 줄로 켭니다 (처음에는 이미지 내려받기와 압축 풀기 때문에 시간이 걸립니다).

   ```
   python3 demo/setup_emulator.py --apk app/build/outputs/apk/debug/app-debug.apk --demo-sample
   ```

4. 진행 줄이 `[1/9] ... [9/9]`까지 나오고 "준비가 끝났습니다"가 뜨면, 브라우저에 가상 콘솔(`http://127.0.0.1:8088/`)이 열립니다.
5. 시나리오 재생: `python3 demo/director.py --scenario all --mode step`
6. 끝나면 에뮬레이터 끄기: `adb emu kill` (`adb`는 `<SDK>/platform-tools` 안에 있습니다)

## 3. Windows에서 처음 켜기

1. 한 번만: 그래픽 드라이버를 최신으로 올리고, `Windows 기능 켜기/끄기`에서 **Windows 하이퍼바이저 플랫폼**을 켠 뒤 재부팅합니다.
   BIOS에서 가상화(Intel VT-x, AMD SVM)가 꺼져 있으면 켭니다.
2. PowerShell을 열고 저장소 폴더로 이동합니다.
3. 디버그 APK: `.\gradlew.bat assembleDebug`
4. 켜기:

   ```
   py -3 demo\setup_emulator.py --apk app\build\outputs\apk\debug\app-debug.apk --demo-sample
   ```

5. 시나리오: `py -3 demo\director.py --scenario all --mode step`
6. 끄기: `adb emu kill`

한글이 깨지면 Windows Terminal을 쓰거나 `chcp 65001`을 먼저 입력하세요.

## 4. 발표 당일 순서 (권장)

1. 발표 전날 한 번 전체를 돌려 이미지를 받아 둡니다. 당일 현장에서는 다시 받지 않아도 됩니다.
2. 당일 현장 인터넷이 불안하면 `--skip-image`를 붙입니다. 이미 설치된 이미지를 그대로 쓰며 내려받기를 건너뜁니다.
3. 에뮬레이터를 켠다: `setup_emulator.py --apk ... --demo-sample` (이미 켜져 있으면 새로 켜지 않고 앱만 다시 설치합니다).
4. 발표: `director.py --scenario all --mode step`. 장면마다 안내 문장이 나오고 **Enter**를 누르면 그 장면이 실행됩니다.
5. 리허설이나 점검용: `--fast`를 붙이면 몇 배 빠르게 돕니다. `--verify`를 붙이면 장면이 끝날 때마다 앱 로그를 확인해 PASS/FAIL을 알려 줍니다.

### 시나리오

| 이름 | 이동 | 계기판 | 앱이 정해야 하는 목적 |
|---|---|---|---|
| `commute` | 집 -> 회사 | 45,200 -> 45,218 km (18 km) | 출퇴근 (COMMUTE) |
| `client` | 회사 -> 일양상사 | 45,218 -> 45,229 km (11 km) | 업무 (BUSINESS) |
| `personal` | 일양상사 -> 미등록 장소 | 45,229 -> 45,236 km (7 km) | 미분류 (UNCLASSIFIED), 앱이 운전자에게 물음 |

선택 장면(`all`에 들어가지 않음, 디버그 APK, `docs/DEMO.md`의 8번 비업무·가족 운행 점검):

| 이름 | 이동 | 계기판 | 앱이 정해야 하는 목적 |
|---|---|---|---|
| `family` | 집 -> 미등록 장소, 출발 전에 가족 운행을 켬(Gleo 요청을 디버그 신호로 재현) | 45,236 -> 45,248 km (12 km) | 비업무 (PERSONAL, `family=true`), 위치를 보내지도 기다리지도 않음 |

`all`은 위 세 개를 순서대로 돌며 계기판이 이어집니다. 장소는 모두 가상입니다. 집, 회사, 일양상사는 `--demo-sample`이 앱에 등록하므로,
샘플 없이 돌리면 목적 분류가 달라질 수 있습니다.

장면은 다섯 개입니다: 준비(주차, 운전석 착석, 계기판 맞춤) -> 출발(기어 D, 속도 0에서 70 km/h) -> 이동(1 km마다 계기판과 위치 갱신) -> 도착(감속) -> 주차(기어 P, 운행기록 닫힘).

앱은 마지막으로 받은 위치로 목적을 정합니다. 내비게이션 위치(주차 중 60초, 주행 중 6초마다 물음)를 먼저 쓰고,
그것이 30초보다 오래됐거나 차량 GPS와 다른 장소를 가리키면 차량 GPS 위치(5초마다, 설치 때 `install -g`로 허용)를 씁니다.
그래서 준비 장면은 출발 전에, 도착 장면은 주차 전에 앱이 그 장소를 확인할 때까지 차를 그 자리에 둡니다
(디버그 APK가 남기는 `POSITION place=집` 같은 줄을 기다림, 차량 GPS로 알았으면 뒤에 `source=gps`, 최대 100초/30초, 넘으면 경고 후 진행).
위치는 `geo fix`와 함께 올바른 체크섬의 NMEA 문장(`geo nmea`)으로도 보냅니다. 에뮬레이터의 `geo fix` 문장은 체크섬이
고정값(`*47`)이라 Pleos 내비게이션이 버리기 때문입니다.
Pleos에서 앱이 받는 위치(NaviHelper)는 내비게이션 앱이 화면에 있을 때만 GPS를 따라갑니다(홈의 운전석 칸 하나를 우리 앱과 나눠 씀).
Pleos Connect 에뮬레이터 이미지의 내비게이션은 넣은 GPS를 아예 따라가지 않으므로(CI 실측), 그때는 앱이 차량 GPS로 장소를 알아봅니다.
앱이 차량 GPS로 위치를 받고 있으면(로그에 `source=gps`, 에뮬레이터가 그렇습니다) 지도를 띄우지 않고 처음부터 끝까지 앱 화면에서
진행합니다. 앱이 15초 안에 그 장소를 보지 못할 때만 지도를 띄웁니다. 지도와 앱을 오갈 때마다 앱에 화면 전환(포커스)이 가고,
Android Automotive는 5초 안에 응답하지 못한 앱을 묻지 않고 바로 닫기 때문입니다(느린 에뮬레이터에서 실제로 일어남).
내비가 GPS를 따라가는 차에서는: 준비(앱이 아직 출발지를 모르면 잠깐 지도) -> 앱 화면에서 출발 -> 이동과 도착은 지도 -> 주차하면 앱 화면.

### 반복 시연 (처음 상태로 되돌리기)

시나리오는 항상 같은 계기판 값에서 시작합니다. 앱에 쌓인 데모 기록을 지우고 다시 시작하려면:

```
adb shell am broadcast -a app.autobrief.car.debug.DEMO -p app.autobrief.car --es cmd reset
python3 demo/setup_emulator.py --demo-sample --no-browser
```

## 5. 옵션

`setup_emulator.py`

| 옵션 | 뜻 |
|---|---|
| `--apk PATH` | 설치할 APK (없으면 에뮬레이터만 준비) |
| `--headless` | 창 없이 소프트웨어 렌더링 (CI용, 부팅 최대 900초. 화면 켜기는 최대 420초) |
| `--no-browser` | 가상 콘솔을 브라우저로 열지 않음 |
| `--cache DIR` | 이미지 보관 폴더 (기본 `~/.cache/autobrief-pleos`, Windows `%LOCALAPPDATA%\autobrief-pleos`) |
| `--out DIR` | 에뮬레이터 로그(`emulator.log`)를 둘 폴더 (기본 `<cache>/out`) |
| `--skip-image` | 이미지 내려받기와 설치를 건너뜀 (오프라인 시연) |
| `--demo-sample` | 디버그 APK의 데모 훅으로 가상 1년치 샘플을 불러옴. 앱 로그 `DEMO_READY`를 확인 |
| `--webhook URL` | `--demo-sample`과 함께: 샘플이 알림을 보낼 주소 (에뮬레이터에서 내 컴퓨터는 `10.0.2.2`) |

`director.py`

| 옵션 | 뜻 |
|---|---|
| `--scenario commute\|client\|personal\|all` | 재생할 시나리오 (필수) |
| `--mode auto\|step` | `auto` 시간표대로, `step` 장면마다 Enter 대기 (발표용) |
| `--km-interval 1.2` | 계기판 1 km 올릴 때마다 쉬는 초 |
| `--fast` | 간격을 0.3초로 (리허설, 자동 시험) |
| `--out DIR` | 장면마다 화면을 `01_commute_ready.png` 같은 이름으로 저장 |
| `--verify` | 시나리오가 끝날 때마다 앱 로그의 `TRIP_CLOSED` 확인 후 PASS/FAIL, 하나라도 FAIL이면 종료 코드 1 |
| `--serial emulator-5554` | adb 대상 |
| `--console http://127.0.0.1:8088` | 가상 콘솔 주소 |

### 가상 콘솔 세션과 준비 대기

가상 콘솔은 브라우저 세션이 살아 있지 않으면 모든 쓰기를 `503 emulator_inactive`로 거절합니다.
`director.py`는 시작할 때 `POST /browser/session?client=autobrief-director`를 1초마다 스스로 보내고,
끝날 때(오류나 Ctrl+C 포함) `closing=1` 신호를 보냅니다. 그래서 브라우저 콘솔 창을 열어 두지 않아도, 화면 없는 서버에서도 동작합니다.
부팅 직후에는 차량 데이터 연결(`gear.bridge_ready`)이 아직 꺼져 있어서, 첫 시나리오 전에 켜질 때까지 최대 300초 기다립니다.
그 사이 쓰기가 503이면 1초 간격으로 최대 5번 다시 시도합니다.

`setup_emulator.py`는 `sys.boot_completed` 뒤에도 기다립니다. Pleos Connect는 시스템 사용자(0)가 먼저 부팅되고
운전자 사용자(10)는 잠금 화면 뒤에서 수십 초 늦게 올라오기 때문입니다. 운전자 사용자가 잠금 해제되고 실제 홈 화면이 뜨고
(임시 홈 `FallbackHome`이나 설정 마법사가 아님) 부팅 직후 방송이 끝나면(`am wait-for-broadcast-idle`), 앱을 그 사용자로 켜고
화면 앞에 5초 이상 머무는지, `VEHICLE_CONNECTED`가 찍히는지 확인한 뒤 샘플을 그 사용자에게만 보냅니다.
`director.py`는 첫 시나리오 전에 기기 상태(현재 사용자, 앞 화면, 콘솔 상태)를 `<--out>/preflight.txt`에 남기고,
시나리오마다 앱이 화면 앞에 없으면 다시 띄웁니다.

## 6. 종료 코드

| 코드 | `setup_emulator.py` | `director.py` |
|---|---|---|
| 0 | 성공 | 성공 |
| 1 | 기타 실패 (APK 설치 실패, DEMO_READY 없음, 다른 에뮬레이터가 5554 사용 중) | `--verify` 하나 이상 FAIL |
| 2 | SDK/도구 없음, 사용법 오류 | adb/SDK 없음, 사용법 오류 |
| 3 | 내려받기 또는 체크섬 실패 | 가상 콘솔 연결 실패 또는 요청 거절 |
| 4 | 부팅 시간 초과 (또는 부팅 중 에뮬레이터 종료, 운전자 사용자가 준비되지 않음) | 앱을 화면 앞에 띄우지 못함 |
| 5 | 가상 콘솔 연결 실패 (180초) | |

## 7. 문제 해결

| 증상 | 원인과 해결 |
|---|---|
| `Android SDK 또는 필요한 도구를 찾지 못했습니다` (2) | Android Studio의 SDK Manager에서 Android Emulator와 Platform-Tools를 설치. SDK가 다른 곳이면 `ANDROID_HOME` 지정 |
| 내려받기가 끊김 | 같은 명령을 다시 실행하면 끊긴 곳부터 이어받습니다. 한 번 실행하는 동안에도 최대 5번까지 스스로 이어받기를 시도합니다 |
| `인증서 확인 실패` | macOS에 python.org 파이썬을 쓰면 `/Applications/Python 3.x/Install Certificates.command`를 한 번 실행. 회사 프록시면 프록시 인증서 확인 |
| 체크섬이 맞지 않음 (3) | 파일을 지우고 한 번 더 받습니다. 두 번 연속 틀리면 네트워크나 프록시 문제입니다. 캐시 폴더의 `.zip`을 지우고 다시 실행 |
| 디스크가 모자람 | `--cache D:\pleos` 처럼 다른 드라이브를 지정. SDK 위치 쪽에도 여유가 필요합니다 |
| 부팅 시간 초과 / 에뮬레이터가 바로 꺼짐 (4) | `<out>/emulator.log` 끝부분이 오류에 함께 출력됩니다. Windows는 하이퍼바이저 플랫폼과 BIOS 가상화, Linux는 `/dev/kvm` 권한, 공통으로 그래픽 드라이버를 확인 |
| `emulator-5554 포트를 다른 기기가 쓰고 있습니다` | 다른 에뮬레이터를 끄세요: `adb -s emulator-5554 emu kill`. 같은 AVD가 이미 켜져 있으면 그대로 씁니다 |
| 가상 콘솔이 응답하지 않음 (5) | 부팅 직후 1~2분은 정상일 수 있습니다. `adb forward --list`로 8088 연결을 확인. 8088을 쓰는 다른 프로그램이 있으면 종료 |
| `503 emulator_inactive`, `bridge` 대기 시간 초과 | 부팅 직후이거나 세션이 끊긴 상태입니다. `director.py`가 알아서 기다리고 다시 시도합니다. 300초가 지나도 안 되면 에뮬레이터를 다시 켜세요(`adb emu kill` 후 `setup_emulator.py`) |
| `APK 설치 실패 ... UPDATE_INCOMPATIBLE` | 서명이 다른 앱(릴리스/디버그)이 이미 있습니다: `adb uninstall app.autobrief.car` 후 다시 실행 |
| `DEMO_READY` 로그가 없음 | 디버그 APK가 아니면 데모 훅이 없습니다. `./gradlew assembleDebug` 결과물을 쓰세요 |
| `앱이 응답 없음(ANR)으로 닫혔습니다` 경고 | 에뮬레이터가 바쁠 때(부팅 직후, 소프트웨어 렌더링) 앱이 5초 안에 화면 전환에 응답하지 못하면 Android Automotive가 바로 닫습니다. 설정 스크립트가 앱을 새로 띄워 한 번 더 합니다. 두 번째도 닫히면 실패로 끝나니, 잠시 뒤 다시 실행하세요 (`adb shell cat /proc/loadavg`로 부하 확인) |
| `--verify`가 FAIL | 출력의 "로그에 있던 TRIP_CLOSED" 줄을 보세요. 직접 확인: `adb logcat -d -s AutoBriefLog:I`. 기억할 점: 앱 권한(차량 속도, 위치)을 허용했는지, `--demo-sample`로 장소가 등록됐는지 |
| `위치 이동을 건너뜁니다` / `내비게이션 위치(NMEA) 전송을 건너뜁니다` 경고 | 에뮬레이터가 GPS 명령을 거절했습니다. 시연은 계속되지만 장소 규칙(출퇴근, 업무)이 맞지 않을 수 있습니다(경고는 한 번만) |
| `앱이 위치(...)를 확인하지 못했습니다` 경고 | 릴리스 APK는 장소 이름을 로그에 남기지 않으므로 디버그 APK를 쓰세요. 한 번 시간이 다 되면 그 실행의 나머지 위치 확인은 건너뜁니다. 에뮬레이터의 내비게이션은 넣은 GPS를 따라가지 않으므로 앱은 차량 GPS에 기댑니다: 위치 권한이 없으면(`-g` 없이 설치하고 첫 실행 질문에 '나중에'를 고름) 장소를 알 수 없으니 `setup_emulator.py`로 다시 설치하거나 시스템 설정에서 위치 권한을 허용하세요 |
| `차량 SDK에 연결되지 않았습니다` (1) | 출력에 `AutoBriefVehicle` 로그가 붙습니다. 에뮬레이터를 다시 켜 보세요 |
| `스크린샷을 건너뜁니다` 경고 | 화면 저장이 안 됩니다. 시연은 계속됩니다. `adb exec-out screencap -p`가 동작하는지 확인 |
| 시연 중 멈춤 | Ctrl+C로 끄고 다시 실행하세요. 시작할 때 먼저 주차(P)로 맞춥니다 |

## 8. 자동 시험 (GitHub Actions)

`.github/workflows/emulator-e2e.yml`은 `main`에 `app/**`, `demo/**` 또는 워크플로 파일이 바뀌어 올라갈 때와, Actions 탭의 `Run workflow`로 돕니다.
리눅스 서버에서 KVM으로 에뮬레이터를 화면 없이 켜고 디버그 APK를 설치한 뒤 `director.py --scenario all --fast --verify`까지 확인합니다.
결과물(`e2e-out`): `e2e.mp4`(화면 녹화, 180초씩 이어 찍은 조각을 합친 것), `logcat-all.txt`·`logcat-autobrief.txt`(부팅 전부터 끝까지 이어 받은 로그),
`logcat.txt`(끝에 남은 로그), `preflight.txt`, `device-state-end.txt`, `fleet_state.json`, `emulator.log`, `fleet-console.log`, 장면별 스크린샷. 14일 보관됩니다.
이미지(약 4 GB)는 캐시에 보관되어 두 번째부터 다시 받지 않습니다.
참고: 워크플로 파일을 올리려면 GitHub 토큰에 `workflow` 권한(scope)이 있어야 합니다.

`.github/workflows/demo-rehearsal.yml`은 Actions 탭의 `Run workflow`(또는 workflow_dispatch API)로만 돕니다(push로는 돌지 않음).
같은 준비 뒤 `rehearsal.py`가 대본 7장면을 실제 탭으로 재생합니다: 출근, 거래처, 처음 가는 곳('비업무' 누름), 정정(운행 목록, 이유 칩, 저장),
위변조(`forge_demo.py`), 운행기록부('지금 보내기' 뒤 관리자 화면의 운행기록부 보관함에서 엑셀을 내려받아 ⑪⑫⑬과 검증 코드 확인),
Gleo 요청(디버그 신호로 재현, 음성 아님). 사용자 10의 앱 저장소는 adb와 `run-as` 모두 읽지 못해(Permission denied) 엑셀은 관리자 화면으로 받습니다.
시작 전에 관리자 화면이 앱의 원장 전체를 받을 때까지(검증 코드가 같아질 때까지) 기다립니다. 장면마다 화면 상태를 기다리고,
나타나지 않으면 그 장면을 실패로 남기며 스크린샷과 화면 구조(`FAIL_*.png`, `FAIL_*.xml`)를 저장한 뒤 다음 장면으로 갑니다.
녹화는 처음부터 끝까지 멈추지 않습니다. 앱은 엑셀을 화면 스레드 밖에서 만들고 그동안 '만드는 중…'을 보여 줍니다.
앱이 응답 없음(ANR)으로 닫히면, Android가 그 ANR을 선언한 시각 직전 20초 안의 마지막 입력(누름, 앱 실행, 신호, 위치, 차량 콘솔 값)이 있던
장면에 표시합니다. ANR 보고 줄은 선언보다 15~20초 늦게 써져 다음 장면 중에 나오기도 합니다.
결과물(`rehearsal-out`): `scenes.json`(장면 시각, 자막, 판정, 기기 시계, 앱 입력 시각, ANR 기록), `rehearsal-*.mp4`(180초 조각), `console_*.png`(관리자 화면),
관리자 화면에서 받은 엑셀, `gleo_reply.txt`, 로그. 노트북에서 직접 돌릴 때: `python3 demo/rehearsal.py --out rehearsal-out --fleet-data ./fleet-data`.

## 9. 장치 없이 시험하기

```
python3 -m unittest discover -s demo -v
```

내려받기 이어받기, 압축 풀기(0으로 찬 구간은 빈 공간으로 유지), 이미지 목록 해석, AVD 설정, 시나리오 계획, 로그 판정,
세션 유지와 503 재시도를 에뮬레이터 없이 시험합니다. 가짜 SDK와 가짜 가상 콘솔로 `setup_emulator.py`와 `director.py` 전체 흐름도 돌려 봅니다.
