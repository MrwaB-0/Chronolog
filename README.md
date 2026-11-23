# Chronolog

삼성 캘린더와 연동되는 **아날로그 + 디지털 통합 크로노그래프 스톱워치** Android 앱입니다.
정밀 아날로그 다이얼과 `HH:MM:SS.mmm` 디지털 표시를 함께 보여 주고, 모든 측정 기록을 영구 저장하며,
기기 내 캘린더(삼성 캘린더 포함)에 일정을 원터치로 등록할 수 있습니다.

- 패키지: `com.marwabadak.chronolog`
- 최소 지원: Android 8.0 (API 26)
- 언어: Kotlin, 프레임워크 뷰(커스텀 Canvas View)
- 빌드: GitHub Actions (Ubuntu · JDK 17 · Android SDK 34 · Gradle 8.7)

## 주요 기능

### 1. 정밀 아날로그 크로노그래프 다이얼
- 다크 메탈릭/글래스모피즘 톤의 다이얼 (`ChronographDialView`, Custom View + Canvas)
- 외곽 베젤에 **1/5초 미세 눈금 300개**, 초 눈금 60개, 5초 단위 숫자 `00`–`55`
- 9시 방향 **30분 적산계**, 3시 방향 **1/100초 서브다이얼**
- 초침은 단순 직선이 아니라 **테이퍼드 메탈릭 바늘 + 카운터웨이트(꼬리 균형추) + 중심 허브 + 야광 포인트**
- 정적 요소는 비트맵으로 캐시하고 바늘만 매 프레임 다시 그려 60fps 로 부드럽게 회전

### 2. 디지털 스톱워치
- `HH:MM:SS.mmm` 대형 표시 (`fontFeatureSettings="tnum"` 으로 숫자 폭 고정)
- 측정 구간을 `시작 YYYY-MM-DD HH:mm:ss.SSS` · `현재/종료 …` 로 실시간 표시
- 경과 시간은 `SystemClock.elapsedRealtimeNanos()` 기반 단조 시계로 계산(화면이 꺼져도 정확)

### 3. 초침 소리 ON / OFF
- 외부 음원 없이 `AudioTrack` PCM 합성으로 기계식 틱(Tick)/톡(Tock) 사운드를 생성
- 1초마다 번갈아 재생, 설정은 `SharedPreferences` 에 저장

### 4. 측정 상세 기록 (영구 저장)
기록마다 다음 4가지를 모두 표시합니다.
1. 측정 날짜 + 요일 — `2026-11-23 (월)`
2. 측정 구간 — `18:33:26.123 ~ 18:33:52.456`
3. 누적 회차 — `#1`, `#2`, …
4. 소요 시간 — `00시간 00분 26초 333밀리초` (정밀 표기 `00:00:26.333` 병기)

기록은 `SharedPreferences` 에 자체 경량 텍스트 포맷(`RecordCodec`)으로 저장되며,
카드별 **삭제**와 **전체 삭제**를 지원합니다.

### 5. 삼성 캘린더 연동
- `READ_CALENDAR` / `WRITE_CALENDAR` 권한 요청
- 기기 캘린더 목록(`CalendarContract.Calendars`)에서 삼성 캘린더/기본 캘린더 선택(★ 표시)
- `CalendarContract.Events` 에 **원터치 직접 등록**
- **측정 종료 시 자동 등록** 토글(기본 OFF)
- 직접 등록이 불가하면 `Intent.ACTION_INSERT` 로 캘린더 앱 새 일정 화면을 여는 폴백
- 일정 제목/설명에 회차, 측정 날짜, 시작~종료 시각, 소요 시간(시·분·초·밀리초)을 모두 포함

## 소스 구조

```
app/src/main/java/com/marwabadak/chronolog/
├─ MainActivity.kt            # 화면 구성 및 흐름 제어
├─ core/
│  ├─ StopwatchEngine.kt      # 나노초 클럭 주입식 스톱워치 엔진 (단위 테스트 가능)
│  ├─ TimeFormat.kt           # HH:MM:SS.mmm · 날짜+요일 · 한국어 소요 시간 포맷
│  ├─ RecordEntry.kt          # 기록 모델 + 직렬화(RecordCodec)
│  ├─ RecordStore.kt          # SharedPreferences 저장소(기록·설정)
│  ├─ TickPlayer.kt           # AudioTrack PCM 틱 사운드 합성
│  └─ CalendarHelper.kt       # 캘린더 목록 조회 · 일정 등록 · Intent 폴백
└─ ui/ChronographDialView.kt  # 아날로그 크로노그래프 다이얼
app/src/test/java/...         # JVM 단위 테스트
```

## 빌드

로컬에 Android SDK 를 설치하지 않아도 됩니다. `main` 브랜치에 푸시하면
`.github/workflows/build.yml` 이 단위 테스트와 디버그 APK 빌드를 수행하고
`Chronolog-debug-apk` 아티팩트로 APK 를 올립니다.

APK 를 내려받는 예시:

```bash
gh run download -R <owner>/Chronolog -n Chronolog-debug-apk -D ./apk
```

## 개인정보

이 앱은 네트워크 통신을 하지 않으며 어떤 데이터도 외부로 전송하지 않습니다.
측정 기록과 설정은 기기 내부 저장소에만 남고, 캘린더 등록은 사용자가 선택한
기기 캘린더에만 기록됩니다.
