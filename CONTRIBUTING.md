# 작업 순서

main은 제출과 시연에 쓰는 브랜치입니다. main에는 시험을 통과한 커밋만, 시험을 통과한 그 모습 그대로 들어갑니다.

## 1. 브랜치에서 작업합니다

- 최신 main에서 브랜치를 만듭니다. 이름은 고치는 일이면 `fix/<주제>`, 새 기능이면 `feat/<주제>`,
  될지 안 될지 시험해 보는 일이면 `exp/<주제>`입니다.
- 커밋 작성자는 팀 이름(`AutoBrief Team`, `autobrief-team@users.noreply.github.com`)으로 둡니다.
- 시험해 보는 변경도 main이 아니라 브랜치에 올립니다.

```bash
git config user.name "AutoBrief Team"
git config user.email "autobrief-team@users.noreply.github.com"
git fetch origin
git switch -c fix/<주제> origin/main
# 고치고 커밋
git push -u origin fix/<주제>
gh pr create --base main --draft
```

## 2. PR에서 시험합니다

- PR은 초안(draft)으로 열고, 초안에서는 시험이 돌지 않습니다. 변경을 마치면 `gh pr ready <PR 번호>`로 준비
  상태로 바꾸고, 그때 `app/`, `demo/`, 시험 작업 파일이 바뀌었으면 `emulator-e2e`가 자동으로 돕니다.
  준비 상태에서 새 커밋을 올리면 앞선 실행은 취소되고 새 커밋으로 다시 돕니다.
- `demo/`나 앱 화면(`app/src/main/java/app/autobrief/car/ui/`, `app/src/main/res/`)이 바뀌면 7분 시연 리허설도
  같은 브랜치에서 돌립니다. 리허설은 화면의 글자를 찾아 누르기 때문입니다.
- 시험은 무료 Actions 시간 안에서만 돌립니다. 초안에서 단위 테스트(`./gradlew testDebugUnitTest`)로 먼저 확인합니다.

```bash
gh workflow run demo-rehearsal.yml --ref fix/<주제>
```

- 실패하면 브랜치에서 원인을 고쳐 다시 올립니다. 에뮬레이터가 중간에 죽는 것처럼 코드와 상관없는 실패는
  `gh run rerun <실행 번호>`로 다시 돌리고, 원인을 PR에 적습니다.

## 3. 실패한 실행 기록은 지우지 않습니다

- Actions의 실행 기록은 성공이든 실패든 지우지 않습니다. 실패 기록은 무엇을 시도했고 왜 안 됐는지 남기는 근거입니다.
- main이 초록색인 이유는 기록을 지워서가 아니라 통과한 커밋만 들이기 때문이어야 합니다.
- 실행 결과 묶음(artifact)은 보관 기간이 지나면 저절로 사라지므로 따로 지우지 않아도 됩니다.

## 4. 통과한 커밋으로 main을 앞으로 옮겨 합칩니다

다음이 모두 맞을 때 합칩니다.

- 브랜치가 최신 main 위에 있습니다. main이 그사이 바뀌었으면 `git rebase origin/main`으로 옮긴 뒤 다시 올리고
  다시 시험합니다.
- PR의 마지막 커밋에서 `emulator-e2e`가 통과했습니다. `demo/`나 앱 화면이 바뀌었다면 같은 커밋에서
  `demo-rehearsal`도 통과했습니다. 문서만 바꾼 PR은 시험 없이 합칠 수 있습니다.

합치기는 시험을 통과한 PR의 마지막 커밋으로 main을 앞으로 옮기는(fast-forward) 방식입니다. GitHub는 그 커밋이
main에 들어온 것을 보고 PR을 '합쳐짐'으로 바꾸고, 작업 브랜치도 저절로 지웁니다(지운 브랜치의 실행 기록은 남습니다).

```bash
gh pr ready <PR 번호>
git push origin fix/<주제>:main
```

- GitHub 화면의 합치기 버튼(`gh pr merge`, 합치기 API 포함)은 쓰지 않습니다. 그 방식은 커밋을 새로 만들면서
  작성자를 누른 사람의 계정으로 바꾸고, 시험한 커밋과 main의 커밋이 달라집니다.
- 이 방식에서는 main의 커밋이 시험을 통과한 바로 그 커밋이므로 main에서 같은 시험을 다시 돌리지 않습니다.
- 로컬 저장소의 push 전 검사(pre-push hook)가 위 조건(앞으로 옮기기, 팀 이름 작성자, 열린 PR의 마지막 커밋,
  필요한 시험 통과)을 확인하고, 하나라도 어긋나면 push를 막습니다.

## 5. 릴리스 APK

`apk/`의 서명된 APK는 앱 코드가 합쳐진 main 커밋으로 다시 빌드하고, 바꾼 APK도 같은 순서(브랜치, PR, 합치기)로 올립니다.
