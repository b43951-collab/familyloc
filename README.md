# 어디찌? (아이쉐어링 스타일 가족 위치 공유 앱)

Kotlin + Jetpack Compose 안드로이드 앱 + Firebase(로그인, Realtime Database) 서버.

## 1. 서버(Firebase) 설정 — 한 번만

1. 콘솔 → Authentication → **이메일/비밀번호** 사용 설정
2. 콘솔 → Realtime Database → 데이터베이스 만들기 (싱가포르, 잠금 모드)
3. Realtime Database → **규칙** 탭 → `database.rules.json` 내용 전체를 붙여넣고 **게시**
4. Realtime Database 화면 위쪽의 `https://…` 주소가
   `app/src/main/java/com/myfamily/locshare/data/FirebaseConfig.kt` 의 `DB_URL` 과 같은지 확인
   (미국 리전이면 `https://familyloc-f00ad-default-rtdb.firebaseio.com`)
5. `app/google-services.json` 은 이미 들어 있음 (프로젝트 familyloc-f00ad)

## 2. APK 만들기 (GitHub Actions)

저장소에 파일을 전부 올리면(`.github` 폴더 포함) Actions가 빌드합니다.
끝나면 Artifacts → `FamilyLoc-apk` → `app-debug.apk` 를 폰에 설치.
서명키를 고정해 두어서 이후 업데이트는 덮어쓰기 설치가 됩니다.

## 3. 사용 순서

1. 첫 번째 폰: 가입(이름·이메일·비밀번호) → **새 가족 서클 만들기** → 설정 탭에서 서클 코드 확인
2. 가족 폰: 가입 → **코드로 참여** → 6자리 코드 입력
3. 모든 폰에서 설정 탭의 체크리스트(위치 항상 허용, 알림, 배터리 제한 없음, 삼성 절전)를 ✅로
4. 지도 탭에서 가족이 보이면 성공. 장소 탭에서 집·학교를 등록하면 도착/출발 알림.

## 4. 동작 방식 / 한계

- 별도 푸시 서버 없이, 위치 서비스가 켜져 있는 동안 각 폰이 DB 변경을 직접 감지합니다.
  → 가족 폰의 앱이 절전으로 죽어 있으면 새로고침·SOS·도착 알림이 늦거나 안 옵니다. (삼성 절전 설정 필수)
- 도착/출발은 **각자의 폰**이 자기 위치로 판정해서 기록합니다.
- 위치 수집 주기: 보는 중/새로고침 5초, 이동 10초, 정지 2분, 절약 모드는 더 느림.
- 이동 기록은 각자 폰에만 저장(7일). 가족 이동 기록은 아직 없음.
- 알림 기록은 서버에 계속 쌓입니다(화면에는 최근 50개). 필요하면 콘솔에서 `circles/…/events`를 지우세요.

## 5. 코드 구조

```
data/FamilyRepository.kt          화면이 의존하는 인터페이스
data/FirebaseFamilyRepository.kt  서버 연동 구현 (현재 사용)
data/LocalFamilyRepository.kt     서버 없이 데모 가족으로 체험하는 구현 (FamilyApp.kt 에서 바꿔 끼울 수 있음)
location/LocationService.kt       포그라운드 위치 수집
ui/                               지도·장소·기록·알림·설정·로그인 화면
database.rules.json               Firebase 보안 규칙 (같은 서클 멤버만 읽기/쓰기)
```

## 6. 참고

- 지도는 OpenStreetMap(키 불필요)입니다. 사용자가 늘면 다른 지도로 교체하세요.
- 위치 공유는 가족 모두에게 알리고 동의를 받은 뒤 사용하세요. 상단 알림("위치 공유 중")은 일부러 항상 표시됩니다.
