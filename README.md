# 스터디 스탑워치

삼성 캘린더와 연동되는 **아날로그 + 디지털 통합 크로노그래프 스톱워치** Android 앱입니다.
실물 기계식 크로노그래프를 재현한 정밀 아날로그 다이얼과 `HH:MM:SS.mmm` 디지털 표시를 함께 보여 주고,
모든 측정 기록을 영구 저장하며, 기기 내 캘린더(삼성 캘린더 포함)에 일정을 원터치로 등록할 수 있습니다.
측정은 화면이 꺼지거나 앱이 백그라운드로 내려가도 포그라운드 서비스와 알림으로 계속 이어집니다.

- 패키지: `com.marwabadak.chronolog`
- 버전: 2.1 (versionCode 3)
- 최소 지원: Android 8.0 (API 26)
- 언어: Kotlin, 프레임워크 뷰(커스텀 Canvas View)
- 빌드: GitHub Actions (Ubuntu, JDK 17, Android SDK 34, Gradle 8.7)

## 주요 기능

### 1. 정밀 아날로그 크로노그래프 다이얼
`ChronographDialView` 는 정적인 요소를 비트맵 두 장(다이얼, 사파이어 글래스)으로 캐시하고,
매 프레임에는 바늘과 중심 허브만 다시 그려 60fps 를 유지합니다.

- **메탈릭 케이스와 베젤**: 다층 브러시드 스틸 링, 동전 테두리 형태의 플루팅, 상단 하이라이트와 하단 음영,
  2시와 4시 방향 푸셔, 3시 방향 크라운 실루엣
- **타키미터 스케일**: 500에서 65까지 15단계 `TACHYMETRE` 각인과 눈금
- **선버스트 다이얼**: 다크 슬레이트 방사형 광선, 미세 동심원 결, 가장자리 비네팅
- **경사진 챕터 링과 레일웨이 트랙**: 1초 강조 눈금 60개, 1/5초 눈금 300개, 1/10초 보조 눈금 600개
- **아플리케 인덱스**: 5초 간격 입체 메탈 바, 좌우 베벨 명암, 야광 인레이, 12시 더블 마커
- **리세스드 서브다이얼 3개**: 9시 30분 적산계, 3시 1/100초, 6시 12시간 적산계.
  상단 내부 그림자와 하단 림 하이라이트, 동심원 스네일 홈, 서브 바늘과 중심 캡
- **패싯 초침**: 좌우 명암이 다른 다면체 바늘, 3겹 오프셋 낙하 그림자, 포인트 컬러 팁,
  카운터웨이트, 2단 중심 피니언 캡
- **사파이어 글래스 오버레이**: 대각선 반사광, 안티리플렉션 틴트, 가장자리 림 비네팅

### 2. 디지털 스톱워치
- `HH:MM:SS.mmm` 대형 표시 (`fontFeatureSettings="tnum"` 으로 숫자 폭 고정)
- 측정 구간을 `시작 YYYY-MM-DD HH:mm:ss.SSS` 과 `현재/종료` 로 실시간 표시
- 경과 시간은 `SystemClock.elapsedRealtimeNanos()` 기반 단조 시계로 계산(화면이 꺼져도 정확)

### 3. 백그라운드 측정 (포그라운드 서비스)
- 측정을 시작하면 `StopwatchService` 가 포그라운드 서비스로 동작합니다.
- 알림에 **현재 회차, 실시간 경과 시간(HH:MM:SS), 시작 시각**을 표시합니다.
- 알림 액션 **종료 및 저장**: 앱을 열지 않아도 측정을 끝내고 기록을 저장합니다.
- 알림을 누르면 앱이 열리고, 알림은 측정이 끝나는 즉시 제거됩니다.
- 화면이 꺼져도 타이밍이 정확하도록 측정 중에만 부분 wake lock 을 사용합니다(최대 12시간).
- Android 13 이상에서는 첫 측정 시작 시 알림 권한을 요청합니다. 권한을 거부해도 측정과 기록 저장은 정상 동작합니다.

### 4. 초침 소리 ON / OFF
- 외부 음원 없이 `AudioTrack` PCM 합성으로 기계식 틱(Tick)/톡(Tock) 사운드를 생성
- 소리 스케줄링을 서비스가 맡으므로 앱이 백그라운드이거나 화면이 꺼져도 1초마다 계속 재생됩니다.
- 소리를 끄면 즉시 재생이 멈추고, 설정은 `SharedPreferences` 에 저장

### 5. 측정 상세 기록 (영구 저장)
기록마다 다음 4가지를 모두 표시합니다.
1. 측정 날짜 + 요일: `2026-11-23 (월)`
2. 측정 구간: `18:33:26.123 ~ 18:33:52.456`
3. 누적 회차: `#1`, `#2`, ...
4. 소요 시간: `00시간 00분 26초 333밀리초` (정밀 표기 `00:00:26.333` 병기)

기록은 `SharedPreferences` 에 자체 경량 텍스트 포맷(`RecordCodec`)으로 저장되며,
카드별 삭제와 전체 삭제를 지원합니다.

### 6. 삼성 캘린더 연동
- `READ_CALENDAR` 와 `WRITE_CALENDAR` 권한 요청
- 기기 캘린더 목록(`CalendarContract.Calendars`)에서 삼성 캘린더/기본 캘린더 선택(`[삼성]` 표기)
- `CalendarContract.Events` 에 원터치 직접 등록
- 측정 종료 시 자동 등록 토글(기본 OFF). 자동 등록은 서비스에서도 동작합니다.
- 직접 등록이 불가하면 `Intent.ACTION_INSERT` 로 캘린더 앱 새 일정 화면을 여는 폴백
- 일정 제목과 설명에 회차, 측정 날짜, 시작과 종료 시각, 소요 시간(시, 분, 초, 밀리초)을 모두 포함

## 소스 구조

```
app/src/main/java/com/marwabadak/chronolog/
├─ MainActivity.kt             # 화면 구성과 흐름 제어
├─ service/
│  └─ StopwatchService.kt      # 백그라운드 측정, 알림, 초침 소리 스케줄링
├─ core/
│  ├─ MeasurementSession.kt    # 앱과 서비스가 공유하는 측정 세션과 저장 처리
│  ├─ StopwatchEngine.kt       # 나노초 클럭 주입식 스톱워치 엔진 (단위 테스트 가능)
│  ├─ TimeFormat.kt            # HH:MM:SS.mmm, 알림용 HH:MM:SS, 날짜와 요일, 한국어 소요 시간
│  ├─ RecordEntry.kt           # 기록 모델과 직렬화(RecordCodec)
│  ├─ RecordStore.kt           # SharedPreferences 저장소(기록과 설정)
│  ├─ TickPlayer.kt            # AudioTrack PCM 틱 사운드 합성
│  └─ CalendarHelper.kt        # 캘린더 목록 조회, 일정 등록, Intent 폴백
└─ ui/ChronographDialView.kt   # 아날로그 크로노그래프 다이얼
app/src/test/java/...          # JVM 단위 테스트
```

## 권한

| 권한 | 용도 |
| --- | --- |
| `READ_CALENDAR`, `WRITE_CALENDAR` | 측정 기록을 기기 캘린더에 등록 |
| `POST_NOTIFICATIONS` | 측정 중 상태 알림 표시 (거부해도 측정은 유지) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | 화면이 꺼져도 측정과 초침 소리를 유지 |
| `WAKE_LOCK` | 화면이 꺼진 동안 타이머와 초침 소리 유지 (측정 중에만 사용) |
| `VIBRATE` | 시스템 알림 진동 |

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
