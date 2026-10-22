# 검증 결과

검증일: 2026-10-22 (Asia/Seoul). Windows 11, JDK 17, Android SDK 35 환경에서 0.4.0-preview 소스와 APK를 검증했습니다. **사용자의 구매 금지 지시에 따라 실제 결제와 자동 구매 예약은 차단되어 있습니다.**

## 실행한 검사

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --continue
```

| 검사 | 결과 |
|---|---|
| Kotlin·Compose·Room KSP 컴파일 | 통과 |
| 단위 테스트 및 Robolectric | 79개 실행, 실패 0, 오류 0, 건너뜀 0 |
| Android Lint | 오류 0, 경고 24 |
| 앱 디버그 APK 빌드 | 통과 |
| 기기 테스트 APK 빌드 | 통과 |
| APK 서명 검사 | 통과 (개발용 v2 서명) |
| APK 메타데이터 확인 | dev.local.rebuy / 0.4.0-preview / 최소 API 26 / 대상 API 35 |
| 최종 앱 manifest 인터넷 권한 확인 | 공유 링크 리디렉션 확인용 INTERNET 권한 있음 |
| BlueStacks Android 11 기기 테스트 | 14개 실행, 실패 0 |
| BlueStacks 앱 업데이트 | 0.4.0-preview 설치·실행 확인 |

Lint 경고 24건은 라이브러리 업데이트 안내 17건, KTX 사용 스타일 제안 5건, 플랫폼 ExifInterface 대신 AndroidX 사용 권고 1건, 실행 세션의 Context 필드에 대한 정적 참조 경고 1건입니다. 세션에는 Activity가 아닌 applicationContext를 저장하고 종료 시 세션을 해제합니다. 검사 전체 비활성화나 baseline은 적용하지 않았습니다.

## 테스트 구성

| 클래스 | 실행 수 | 검증 대상 |
|---|---:|---|
| RoutineRepositoryTest | 11 | 실제 Room SQLite에서 원자적 완료·24개 동시 요청·이력 보존·오래된 회차 거부·입력 검증·자정 및 시간대 경계 |
| CoupangUrlTest | 4 | 일반·공유 HTTPS URL, 도메인 위장·사용자 정보·포트·제어 문자·잘못된 인코딩 거부 |
| PurchaseScheduleTest | 4 | 완료일 기준 계산, 윤년·연말·입력 경계 |
| ReminderSchedulerTest | 10 | 회차별 알림 중복 방지, 알림 차단 뒤 재시도, 일시정지·보관 취소, 고유 예약 유지·교체, 시계 변경, PendingIntent 구분, 설정 저장 |
| ReminderTimeTest | 6 | 한국 시간, 지연 계산, 과거 일정, 시간대 변경, 일광절약시간 시작·종료 |
| CoupangProductLinkTest | 9 | 정확한 상품·옵션 ID, 네이티브 링크, 공유 링크 리디렉션의 허용 도메인·횟수·루프·취소·실패 |
| CartAssistPolicyTest | 6 | 상품명·옵션 일치, 관찰된 담기 버튼 부모만 선택, 추천·결제 버튼 거부, 완료 안내 조건 |
| AutoBuyStoreTest | 8 | 조건 버전·상품 스냅샷, 동시 선점, 프로세스 재시작 뒤 재실행 거부, 금액 한도, 실제 구매 차단 |
| AutoBuyCheckoutPolicyTest | 7 | 단일 상품·수량·옵션, 배송비 포함 총액, 두 가격 표시와 한도 초과 거부 |
| AutoBuyControllerGateTest | 1 | 실제 구매 요청을 외부 앱 실행 전에 차단 |
| ProductTextParserTest | 13 | 한국어 상품명·포장 옵션, 복수 가격, 통화 미인식 숫자 분리, 배송비·적립·할부 제외, URL 추정 금지와 입력 크기 제한 |

알림이 이미 전달된 현재 회차는 시간·시간대·알림 시각 변경 때 표시를 유지합니다. 오래된 완료 요청은 새 회차의 알림을 취소하지 않도록 통합 검토에서 수정했습니다.

## 기기 검증과 남은 한계

BlueStacks Rvc64(Android 11)에 0.4.0-preview APK를 설치했습니다. 기기 검사 14개가 모두 통과했습니다. 기존 Room 2개, 실제 구매 요청 차단 1개, 결제 비활성화 안내 화면 1개, 상품 등록→완료 확인→이력·중복 방지 흐름 1개와 이미지 인식 5개, 검토 UI 4개가 포함됩니다. 입력 검사에서는 저장 알림이 버튼을 가리는 동안 기다린 뒤 실제 터치를 사용합니다. 검사 데이터는 고유 ID로 구분해 정리합니다.

Coupang 9.4.5의 공개 상품 URI `coupang://product?pId=…&itemId=…&vendorItemId=…`로 지정 옵션 화면이 열리는 것을 확인했습니다. 외부 UI 조작으로 상품의 장바구니 수량 1을 확인했고, 결제 전 주문 화면까지 열었습니다. 최종 결제 버튼은 누르지 않았습니다. 이 외부 조작은 다시담기의 접근성 서비스 성공 증거가 아닙니다.

접근성 권한을 사용자가 활성화한 뒤 앱 자체 미리보기에서 공개 네이티브 링크로 지정 상품의 ProductDetailActivity가 열리는 것을 확인했습니다. 앱이 상품명·옵션을 판독하고 주문 화면까지 이동하는 전체 흐름의 성공은 확인하지 못했습니다. 이 환경의 Android 공유 링크 확인 요청은 실패하여 동일 상품의 확인된 전체 주소로 시험했습니다. 현재 결제 화면의 상품 행·수량 선택자는 확보하지 못했고 할인 전·후 금액이 함께 보이면 중단합니다. 따라서 실제 결제와 자동 예약 기능은 활성화할 수 없으며, 시험으로 구매 이력을 기록하지 않습니다. 제조사별 절전 동작과 재부팅 뒤 알림 수신도 추가 기기 검증이 필요합니다.

## 이미지 인식 검증의 범위

실제 번들 한국어 ML Kit로 고정 Canvas 이미지의 상품명과 `600ml, 2개` 옵션을 읽습니다. 테스트용 별도 ContentProvider의 PNG URI를 앱의 디코더→OCR→후보 파서로 통과시키며, 불량 이미지·file/http URI 거부와 이미지 크기 축소도 검사합니다. 테스트 Provider는 합성 이미지와 불량 바이트만 제공하고 사용자 파일에 접근하지 않으며 테스트 APK에만 포함됩니다. 일반 앱에는 사진·카메라·외부 저장소 권한을 추가하지 않았습니다.

실제 인식에서 `75,000원`, `67,500원`의 원 글자가 빠지거나 `l`로 읽히는 사례를 확인했습니다. 이 경우 숫자 75,000과 67,500은 확정 가격이 아닌 별도 검토 후보가 됩니다. 통화 기호가 없는 모든 숫자를 가격으로 올리지 않으며, 독립된 쉼표 묶음 숫자만 제한적으로 제시합니다. 기본 선택은 없고, 사용자가 금액인지 확인하여 선택한 뒤 입력란 적용을 눌러야 합니다. 그 값도 결제 화면에서 확인한 최종 금액이라는 뜻은 아닙니다.

UI 검사는 기본 가격 미선택, 검토 전 콜백 없음, 수정·선택한 값만 복사, 취소 시 기존 입력 유지, 통화 미인식 숫자의 명시적 선택을 확인합니다. 사진 선택기와 실제 쿠팡 스크린샷을 사람이 직접 선택하는 전체 흐름은 별도 수동 검증 대상입니다. 이미지 OCR 검사는 쿠팡을 열거나 장바구니·주문·구매 이력을 변경하지 않습니다.

이번 BlueStacks 환경에서는 Gradle 연결 테스트 종료 후 대상 앱이 제거되는 동작을 관찰했습니다. 최종 검증은 앱을 유지하는 아래 직접 설치·실행 방법으로 수행했습니다:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.local.rebuy.test/androidx.test.runner.AndroidJUnitRunner
```

화면 흐름 테스트는 자신이 생성한 고유 ID의 테스트 상품과 이력만 종료 시 정리합니다. 일반 앱의 보관 기능은 기존 구매 이력을 유지합니다.

알림은 WorkManager의 비정확 예약입니다. 설정 시각과 실제 수신 사이에 지연이 있을 수 있으며, 정확한 시각 보장과 결제 검증은 이 MVP의 기능이 아닙니다.
