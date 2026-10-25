# 오토브리프 (AutoBrief)

시동을 걸고 끌 때마다 계기판 누적거리로 국세청 서식 업무용승용차 운행기록부를 스스로 쓰는 Pleos Connect 앱입니다.
휴대폰도, GPS 이동 경로 저장도 없이 차가 직접 기록하고, 한 번 쓴 기록은 고친 내역까지 남는 증빙으로 보관합니다.

## 1. 앱 이름과 한 줄 소개

- 앱 이름: 오토브리프 (AutoBrief), 패키지 `app.autobrief.car`, 버전 2.0.0
- 한 줄 소개: 시동을 끄는 순간 계기판 거리로 운행기록부 한 줄이 생기고, 출퇴근과 거래처 방문은 목적까지 자동으로 적히는 차량 전용 운행기록부

| 기능 | 하는 일 | 쓰는 Pleos/AAOS API |
|---|---|---|
| 시동 단위 자동 기록 | 기어가 P를 벗어날 때와 P로 돌아올 때 계기판 누적거리(⑤⑥)를 읽어 한 줄을 씁니다. 앱이 못 본 사이 계기판이 움직였으면 `미기록 구간(비업무)`으로 따로 남깁니다. | Vehicle `Odometer`, `Gear`, `CarInfo`(차대번호) |
| 장소로 목적 자동 분류 | 집→회사, 회사→집은 출퇴근(⑧), 등록한 거래처 도착은 일반 업무(⑨)로 적습니다. 그 밖의 운행은 정차 후 화면이나 음성으로 묻습니다. 위치는 장소를 알아보는 데만 쓰고 기록에는 장소 이름만 남깁니다. | NaviHelper 즐겨찾기(HOME/OFFICE), 현재 위치, 목적지와 예상 통행료 |
| 고친 내역이 남는 기록 원장 | 운행 한 줄과 목적 지정, 수정이 모두 해시로 이어진 원장에 쌓입니다. 목적을 고치려면 이유가 필요하고, 엑셀의 '검증 기록' 시트와 회사 서버 사본으로 대조할 수 있습니다. | (앱 내부) SHA-256 해시 체인 |
| 음성 (Gleo) | "방금 운행 업무로 기록해", "이번 달 업무 비율 알려줘", "지난달 운행기록부 보내줘" | Gleo Interface 함수 3개 |
| 국세청 서식 엑셀 | 월, 반기, 사업연도별 「업무용승용차 운행기록 방법에 관한 고시」 별지 서식 항목(③~⑬) + 검증 기록 시트. 매달 첫 운행 때 지난달 기록부를 자동으로 만들어 보냅니다. | (앱 내부) OOXML 작성 |
| 절세 계기판 | 법인세법 시행령 §50의2 기준으로 운행기록부가 있을 때와 없을 때 비용 인정액 차이와 세금 효과를 보여 줍니다. | (앱 내부) |
| 회사 관리자 화면 | 기록이 생길 때마다 원장 한 줄과 해시가 회사 서버로 가고, 관리자 화면이 차량별 업무 비율과 위변조 여부를 보여 줍니다. | 웹훅(HTTPS) + `tools/fleet-console` |
| 주행 중 화면 | 주행 중에는 큰 글씨 상태만 보이고 입력 화면을 띄우지 않습니다. | AAOS `CarUxRestrictions` |

외부 앱이 할 수 없어서 하지 않는 것: 하이패스 실제 결제 금액 읽기(통행료는 내비가 계산한 경로 예상값만 비고에 적음), 순정 화면 변경, 문자 발송.

## 2. 실행 방법

1. Android Studio(Gradle 8.7, JDK 17)에서 이 저장소를 엽니다. Pleos SDK는 `settings.gradle.kts`의 공개 저장소
   `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다.
2. 에뮬레이터 준비는 한 줄로 됩니다(Mac Apple 칩, Windows, Linux 공통, 파이썬 3.9 이상):
   `python3 demo/setup_emulator.py --apk apk/<배포 APK>` (Pleos Connect v2.1.0 이미지 내려받기, 가상 기기 생성, 콜드 부팅, 설치, 가상 콘솔 열기)
   - 공식 안내대로 외장 그래픽카드(노트북 RTX 3060급 이상) 또는 Apple 칩 Mac이 필요합니다.
3. 앱을 열고 '가상 예시로 둘러보기'를 누르면 실제 기록과 분리된 시연 공간에 가상 회사의 1년 기록이 들어옵니다.
4. 차를 움직이는 방법: 가상 콘솔(`http://127.0.0.1:8088/`)에서 기어와 계기판을 바꾸거나, 시연 대본을 돌립니다:
   `python3 demo/director.py --scenario all --mode step`
5. 회사 관리자 화면: `python3 tools/fleet-console/fleet_console.py` 후 브라우저에서 `http://127.0.0.1:8787/`,
   앱 설정의 보내기 주소에 `http://10.0.2.2:8787/hook` (에뮬레이터에서 노트북을 가리키는 주소)

시연 순서와 확인할 화면은 `docs/DEMO.md`에 있습니다.

## 3. 테스트 환경과 설정

- 단위 테스트: `./gradlew testDebugUnitTest` (51개: 기록 엔진, 해시 원장 위변조 감지, 장소 자동 분류, 절세 계산,
  엑셀 형식, Gleo 스키마, 시연 공간 분리, 월말 자동 마감, Robolectric 실행 시험, 화면 캡처 11장)
- 화면 캡처: `app/build/outputs/roborazzi/*.png` (2560x1440 차량 화면과 세로형 화면, 밤과 낮 테마)
- 가상 예시 데이터: `SampleScenarioTest`가 앱의 기록 엔진으로 만든 가상 회사 1년치를 `app/build/sample/`에
  엑셀과 요약으로 씁니다(`docs/samples`에 복사본). 실제 차량, 법인, 인물이 아닙니다.
- 에뮬레이터 종단 시험: `demo/director.py --verify`가 가상 콘솔로 기어와 계기판을 움직이고 앱 로그(`AutoBriefLog`)에서
  운행 기록과 자동 분류 결과를 확인합니다.
- 릴리스 서명 키와 비밀번호는 저장소에 없습니다. 빌드할 때 `AUTOBRIEF_KEYSTORE_PATH`, `AUTOBRIEF_KEYSTORE_PASSWORD`
  환경 변수로 넘깁니다.

## 4. APK 위치

- `apk/` 폴더의 릴리스 APK (공모전 규칙 `팀명_앱이름_release.apk`)

## 권한

`pleos.car.permission.CAR_DRIVE`(계기판, 기어), `CAR_INFO`(차대번호), `NAVI_ROUTE`(즐겨찾기, 현재 위치, 목적지),
인터넷(보내기 주소), 포그라운드 서비스(시동마다 기록), 부팅 완료 수신. 실행 중 동의가 필요한 권한은 없습니다.

## 개인정보와 위치정보

- 이동 경로를 저장하지 않습니다. 위치는 출발과 도착 순간에만 등록 장소와 비교하고 버리며, 기록에는 "집", "회사",
  거래처 이름만 남습니다.
- 기록은 차량 안 앱 저장소에 있고, 보내기 주소를 넣었을 때만 그 주소(회사 서버)로 원장과 엑셀이 갑니다.
- 보내기 주소와 관리자 PIN은 현재 PIN이 있어야 바뀝니다. 목적을 고치면 이유와 이전 목적이 원장에 남습니다.
- 시연 모드는 실제 기록과 다른 저장 공간을 쓰고, 시연 중 움직인 거리는 실제 기록부에 미기록 구간(비업무)으로 남습니다.

## 오픈소스

AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization (Apache License 2.0),
Pretendard 글꼴 (SIL Open Font License 1.1, 원본 그대로 포함, `app/src/main/assets/licenses`),
Robolectric (MIT), Roborazzi (Apache 2.0) — 테스트 전용. Pleos Connect SDK는 Pleos Playground 이용 조건을 따릅니다.
