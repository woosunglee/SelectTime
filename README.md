# SelectTime — 호계체육관 배드민턴 자동예약

안양도시공사([auc.or.kr](https://www.auc.or.kr)) 호계체육관 배드민턴장 예약을 돕는 **개인용** 자동화 도구입니다.

- **데스크톱**: Python + Playwright (`desktop/`)
- **안드로이드**: Java + WebView + WorkManager (`android/`)

공통 설정 스키마는 `shared/`에 있습니다.

## 예약 규칙 (시설 안내)

- 이용일 기준 **7일 전 15:00 ~ 1일 전 23:59** 예약 가능
- **당일 예약 불가**
- 공식 TRACER 대기열을 **우회하지 않습니다**

## 공정 이용 / 약관

- 본인 계정·본인 이용 목적의 개인 도구로만 사용하세요.
- 다중 계정, 대량 요청, 대기열 우회는 지원하지 않으며 권장하지 않습니다.
- 시설 이용약관·사이트 정책을 준수할 책임은 사용자에게 있습니다.
- 비밀번호·카드 정보는 Git에 올리지 말고 로컬 암호화 저장소/`.env`에만 두세요.

## 결제

| 수단 | 데스크톱 | 안드로이드 |
|------|----------|------------|
| 카드 (`card`) | PG 폼 자동입력 | PG 폼 자동입력 |
| 앱카드 (`app_card`) | 선택 후 알림·수동 승인 대기 | 카드사 앱 Intent 왕복 |

OTP / 3DS / 앱카드 PIN·생체 인증이 필요하면 알림 후 사용자 입력을 기다립니다.

## 데스크톱 빠른 시작

```powershell
cd desktop
.\scripts\setup.ps1
copy ..\shared\config.example.yaml .\config.local.yaml
copy .env.example .env
# .env 에 AUC_ID / AUC_PASSWORD 입력
# config.local.yaml 에서 선호 시간·결제수단 수정
python -m selecttime doctor
python -m selecttime once
python -m selecttime watch
```

## 안드로이드

Android Studio에서 `android/` 폴더를 열고 빌드하세요.

1. 앱 실행 → 설정에서 AUC 계정·선호 슬롯·결제수단(카드/앱카드) 저장
2. 「예약 예약하기」로 오픈 시각(기본 15:00) WorkManager 스케줄
3. 「지금 실행」으로 즉시 WebView 자동화 시작

## 디렉터리

```
shared/     공통 스키마·예시 설정
desktop/    Playwright CLI
android/    Java 앱
```

문의(시설): 호계체육관 통합프런트 031-389-5395 / 5392
