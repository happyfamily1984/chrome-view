# 크롬뷰 인수인계 메모

새 세션에서 이 저장소를 이어 작업할 때 먼저 읽을 내용입니다.

## 코드 위치

- 작업 브랜치: `ccr-8b5eb7d2-6jv7vs` (아직 `main`에 합치지 않음)
- 마지막 배포 버전: 3.6 (`dist/chromeview.apk`)

## 서명 키 (가장 중요)

- `app/chromeview.keystore` 는 저장소에 올리지 않습니다(`.gitignore`). 사용자가 따로 보관하고 있습니다.
- 별칭 `chromeview`, 비밀번호 `chromeview`(환경 변수 `CHROMEVIEW_KEY_PASSWORD` 로 바꿀 수 있음).
- 인증서 SHA-256: `DA:C9:CF:15:D6:E7:A3:BD:3D:9D:25:45:88:C4:72:92:FC:1A:55:E1:F8:77:19:21:48:50:C5:A7:FA:3E:A2:20`
- 새 세션에서는 빌드 전에 사용자에게 받은 키 파일을 `app/chromeview.keystore` 에 넣어야 합니다.
  키 없이 빌드하면 디버그 키로 서명되어, 설치된 크롬뷰 위에 덮어 설치가 안 됩니다
  (지우고 다시 깔아야 하고, 그러면 탭·로그인·즐겨찾기가 지워짐).
- 확인: `apksigner verify --print-certs dist/chromeview.apk` 의 SHA-256 이 위 값과 같아야 합니다.

## 빌드 환경 만들기 (클라우드 컨테이너는 매번 새로 시작됨)

```
mkdir -p /opt/android-sdk/cmdline-tools && cd /opt/android-sdk/cmdline-tools
curl -sSo ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q ct.zip && mv cmdline-tools latest && rm ct.zip
yes | latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
# 프록시 환경이면 sdkmanager 에 --proxy=http --proxy_host=127.0.0.1 --proxy_port=<포트> 를 붙인다
cd <저장소> && echo "sdk.dir=/opt/android-sdk" > local.properties
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk dist/chromeview.apk
```

새 버전을 낼 때는 `app/build.gradle` 의 `versionCode`/`versionName` 을 올립니다.

## 사용자 정보와 정해진 방향

- 사용자: 갤럭시 사용(S21 → 램 12GB 모델로 교체 예정), 한국어로 답함.
- Play 스토어에 내지 않는 개인용 앱. 스토어 정책(상표, 배터리 요청, 유튜브 백그라운드 재생 등)은 따지지 않음.
- 앱 공통 규칙: 뒤로가기는 이전 행동/화면으로, 첫 화면에서 뒤로가기는 "앱을 종료하겠습니까?" 확인.
  크롬뷰에서는 페이지에서 더 돌아갈 곳이 없으면 종료 확인, 탭 목록(메뉴) 화면은 버튼으로만 열림.
- 인증은 크롬뷰 고정 창 안에서 해야 유지됨(Chrome 에서 시작한 인증은 크롬뷰가 붙잡지 못함).

## 기능 요약 (자세한 내용은 README)

고정 창 탭 유지(포그라운드 서비스), 기본 브라우저 지정, 인증 앱 복귀 처리, 시크릿 탭(androidx.webkit 프로필),
시작 페이지 설정, 주소창 자동완성(방문 횟수 순), 북마크 html 가져오기, 다운로드, 진단 기록,
영상 백그라운드 재생 + 알림창 재생 조절, 오래 안 본 탭 자동 내리기, 최근 탭 5개만 남기기.

## 진행 중이던 문제

- S21(램 8GB)에서 크롬뷰가 "메모리 부족"으로 꺼짐(진단 기록으로 확인, 탭 1개일 때도 발생).
- 3.4: 백그라운드 재생을 '절약 방식(가려진 상태)' 먼저 시도 → 멈추면 '화면 유지 방식'으로 자동 전환.
  eduzion.org(Kollus 플레이어)는 절약 방식에서 바로 멈춰 화면 유지 방식으로 전환됨.
- 또 메모리 부족으로 꺼지면 다음 후보: 백그라운드 재생 중 WebView 크기를 아주 작게 줄여 메모리 절약
  (복귀 시 원래 크기로). 아직 구현하지 않음. 휴대폰을 12GB 모델로 바꾸면 필요 없을 수 있음.
