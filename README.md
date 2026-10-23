# 오토브리프 (AutoBrief)

Pleos Connect 차량에서 시동을 켜고 끌 때마다 계기판 누적거리로 업무용승용차 운행기록부를 자동으로 쓰고,
잠든 동승자와 대리운전 귀가까지 차량 데이터로 챙기는 차량 전용 앱입니다.

## 1. 앱 이름과 한 줄 소개

- 앱 이름: 오토브리프 (AutoBrief), 패키지 `app.autobrief.car`, 버전 1.6.0
- 한 줄 소개: 휴대폰 없이, 시동을 끄는 순간 계기판 거리로 운행기록부를 남기는 Pleos Connect 앱

| 모드 | 지금 동작하는 것 | 쓰는 Pleos/AAOS API |
|---|---|---|
| 1. 운행기록부 | 기어 P를 벗어날 때와 P로 돌아올 때 계기판 누적거리를 기록, 앱이 못 본 주행은 `미기록 구간(비업무)`으로 따로 표시, 목적은 화면 또는 음성(Gleo)으로 지정(지정 전에는 업무로 계산하지 않음), 월/반기/사업연도별 국세청 서식 항목 엑셀 생성, 차주가 등록한 HTTPS 보관 주소로 전송 | Vehicle `Odometer`, `Gear`, `CarInfo`(VIN), NaviHelper 목적지와 예상 통행료, Gleo Interface |
| 2. 수면 케어 | 동승석 착석, 안전벨트, 등받이 기울기, 독서등으로 60초 이상 잠든 동승자를 감지, 운전자에게 조용한 안내(2열이면 순정 '후석 취침 모드' 안내), 음성비서 응답을 효과음으로 전환, 차량이 허용하면 실내 조명 낮춤 | Vehicle `Seat`, `Light` |
| 3. 대리운전 | 기사님 화면 동의 후 시작, 주행 거리, 최고 속도, 과속(제한속도 +20km/h), 급가속, 급감속(사건 단위), 중간 정차, 도착(목적지 300m 안 P 또는 내비 도착 신호), 문 잠김을 기록, 차주가 등록한 주소로 출발과 도착 알림, 차주 PIN으로만 도중 종료(이때는 '도착 전 종료'로 알림) | Vehicle `Gear`, `Odometer`, `Door`, AAOS `PERF_VEHICLE_SPEED`, NaviHelper 목적지, 도착, 제한속도 |

외부 앱에 열려 있지 않아 하지 않는 것: 좌석별 스피커 끄기나 음위치 조절(AAOS 오디오 설정 권한은 시스템 전용),
순정 내비게이션의 최근 목적지나 통화 목록 가리기, 차량에서 직접 문자 보내기, 하이패스 실제 결제 금액 읽기.
이 앱은 자기 화면에서만 동, 호수를 가리고, 통행료는 내비가 계산한 경로 예상값만 씁니다.

## 2. 실행 방법

1. Android Studio(Gradle 8.7, JDK 17)에서 이 저장소를 엽니다. Pleos SDK는 `settings.gradle.kts`의
   공개 저장소 `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다.
2. Pleos Connect 에뮬레이터(API 34, Pleos Connect v2.1.0)를 SDK Manager 업데이트 사이트로 설치하고 콜드 부팅합니다.
3. 설치: `./gradlew installDebug` 또는 `adb install apk/<배포 APK>`
4. 앱을 열고 차량 속도 권한을 허용합니다. 설정 탭에서 법인명, 차종, 등록번호, 사용자, 차주 PIN, 보내기 주소를 넣습니다.
5. 가상 콘솔: `adb forward tcp:8088 tcp:8088` 후 브라우저에서 `http://127.0.0.1:8088/`

시연 순서는 `docs/DEMO.md`에 있습니다.

## 3. 테스트 환경과 설정

- 단위 테스트: `./gradlew testDebugUnitTest` (JVM 24개 + Robolectric으로 첫 실행 화면을 띄우는 시험 1개)
- 가상 예시 데이터: `SampleScenarioTest`가 실제 기록 엔진에 기어, 계기판, 내비 신호를 재생해 `app/build/sample/`에
  운행기록부 엑셀과 요약을 만듭니다. 실제 차량, 법인, 인물이 아닙니다.
- 가상 콘솔에는 차량 속도, 등받이, 좌석별 안전벨트 항목이 없습니다. 그래서 수면 케어와 급감속은 앱 안의
  '시연용 가상 신호' 버튼으로만 시연하고, 화면에 가상 신호라고 표시합니다. 실제 차량에서는 SDK 값을 씁니다.
- 실내 조명 낮추기는 `Light.setAmbientLightBrightness`를 쓰며, 차량과 앱 마켓 승인 범위가 허용할 때만 동작합니다.
- 급가속과 급감속 기준은 한국교통안전공단 운행기록분석시스템의 택시(승용) 기준을 따르고 설정으로 바꿀 수 있습니다.
- 릴리스 서명 키와 비밀번호는 저장소에 없습니다. 빌드할 때 `AUTOBRIEF_KEYSTORE_PATH`, `AUTOBRIEF_KEYSTORE_PASSWORD`
  환경 변수로 넘깁니다.

## 4. APK 위치

- `apk/` 폴더의 릴리스 APK (공모전 규칙 `팀명_앱이름_release.apk`)

## 권한

`pleos.car.permission.CAR_DRIVE`, `CAR_SEATS`, `CAR_LIGHTS`, `CAR_DOORS`, `CAR_INFO`, `NAVI_ROUTE`,
`android.car.permission.CAR_SPEED`(실행 중 동의), 인터넷(보내기 주소), 포그라운드 서비스(시동마다 기록), 부팅 완료 수신.

## 개인정보와 위치정보

- 차주 PIN이 없으면 대리운전 모드를 시작하지 않고, PIN을 5번 틀리면 잠시 막은 뒤 차주 연락처에 알립니다.
- 음성 명령은 방금 끝난 운행(30분 이내)의 목적만 정할 수 있고, 그렇게 정한 줄에는 ⑩비고에 `음성 지정`이 남습니다.

- 대리운전 모드는 기사님이 화면에서 동의해야 시작하며, 동의하지 않으면 기록하지 않습니다.
- 위치가 담긴 알림은 차주가 직접 등록한 주소로만 보냅니다. 서비스로 운영할 때는 위치정보법에 따른
  위치기반서비스사업 신고와 이용약관, 개인정보 처리방침을 갖춘 뒤 엽니다.
- 기록은 차량 안 앱 저장소에만 남고, 보내기 주소가 없으면 밖으로 나가지 않습니다.

## 오픈소스

AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization (Apache License 2.0),
Robolectric (MIT, 테스트 전용). Pleos Connect SDK는 Pleos Playground 이용 조건을 따릅니다.
