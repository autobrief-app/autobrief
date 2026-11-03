# 작업 순서

main은 제출과 시연에 쓰는 브랜치입니다. main에는 시험을 통과한 변경만 들어갑니다.

## 1. 브랜치에서 작업합니다

- 최신 main에서 브랜치를 만듭니다. 이름은 고치는 일이면 `fix/<주제>`, 새 기능이면 `feat/<주제>`,
  될지 안 될지 시험해 보는 일이면 `exp/<주제>`입니다.
- main에 직접 push하지 않습니다. 시험해 보는 변경도 브랜치에 올립니다.

```bash
git fetch origin
git switch -c fix/<주제> origin/main
# 고치고 커밋
git push -u origin fix/<주제>
gh pr create --base main --draft
```

## 2. PR에서 시험합니다

- main으로 가는 PR에 `app/`, `demo/`, 시험 작업 파일이 바뀌면 `emulator-e2e`가 자동으로 돕니다.
  같은 PR에 새 커밋을 올리면 앞선 실행은 취소되고 새 커밋으로 다시 돕니다.
- `app/`이나 `demo/`가 바뀌면 7분 시연 리허설도 같은 브랜치에서 돌립니다.

```bash
gh workflow run demo-rehearsal.yml --ref fix/<주제>
```

- 실패하면 브랜치에서 원인을 고쳐 다시 올립니다. 에뮬레이터가 중간에 죽는 것처럼 코드와 상관없는 실패는
  `gh run rerun <실행 번호>`로 다시 돌리고, 원인을 PR에 적습니다.

## 3. 실패한 실행 기록은 지우지 않습니다

- Actions의 실행 기록은 성공이든 실패든 지우지 않습니다. 실패 기록은 무엇을 시도했고 왜 안 됐는지 남기는 근거입니다.
- main이 초록색인 이유는 기록을 지워서가 아니라 통과한 변경만 합치기 때문이어야 합니다.
- 실행 결과 묶음(artifact)은 보관 기간이 지나면 저절로 사라지므로 따로 지우지 않아도 됩니다.

## 4. 통과하면 main에 합칩니다

다음이 모두 맞을 때 PR을 합칩니다.

- PR의 마지막 커밋에서 `emulator-e2e`가 통과했습니다.
- `app/`이나 `demo/`가 바뀌었다면 같은 커밋에서 `demo-rehearsal`도 통과했습니다.
- 브랜치가 최신 main 위에 있습니다. main이 그사이 바뀌었으면 `git rebase origin/main` 뒤 다시 시험합니다.

```bash
gh pr ready <PR 번호>
gh pr merge <PR 번호> --squash --delete-branch
```

합치기는 커밋 하나로 묶는 방식(squash)만 씁니다. 합친 뒤 main에서 `emulator-e2e`가 한 번 더 돌고, 작업 브랜치는
자동으로 지워집니다. 브랜치를 지워도 그 브랜치에서 돈 실행 기록은 남습니다.

## 5. 릴리스 APK

`apk/`의 서명된 APK는 앱 코드가 합쳐진 main 커밋으로 다시 빌드하고, 바꾼 APK도 같은 순서(브랜치, PR, 합치기)로 올립니다.
