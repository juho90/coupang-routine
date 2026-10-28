# 공통 기능 확인 데이터

실제 쿠팡 연동을 확인할 때는 [coupang-product.json](coupang-product.json)을 기본 샘플로 사용합니다. 상품 URL은 이 JSON 한 곳에만 기록합니다.

## 두 상품 묶음 확인

[cart-batch.json](cart-batch.json)은 기본 바나나 샘플과 [coupang-porridge.json](coupang-porridge.json)의 동원 용기죽을 파일명으로 참조합니다. URL은 각 상품 JSON에서 관리하며 목록 파일에 복제하지 않습니다. 이 파일은 실제 확인 대상을 고정하는 명세이며 읽기만으로 앱 등록이나 담기를 실행하지 않습니다. CLI의 `sample show`·`sample open`은 계속 기본 바나나 한 상품을 사용합니다.

두 샘플을 구매 수량 1개로 등록하고 빈 장바구니를 확인한 뒤 앱의 **모두 장바구니에 담기**를 한 번 시작합니다. 앱의 2/2 완료와 실제 쿠팡의 두 상품·각 주문 수량 1을 함께 확인합니다. 기존 상품이 남아 있으면 그 수량이 늘어날 수 있으므로 같은 결과로 취급하지 않습니다. 2026-10-27에 이 흐름을 실제로 확인했습니다. 주문서 접근 확인은 별도이며 최종 결제를 누르지 않습니다.

## 사용하는 곳

- CLI의 `sample show`: EXE에 포함된 공통 데이터를 JSON으로 표시합니다.
- CLI의 `sample open`: 같은 URL을 지정한 Android 기기의 기본 링크 처리기로 엽니다. 브라우저를 거쳐 쿠팡 앱으로 연결될 수 있습니다.
- `tools/tests/smoke.ps1`: 원본 JSON과 EXE에 포함된 데이터가 일치하는지 확인하고, mock ADB가 같은 URL을 받았는지 검사합니다.
- 수동 기능 확인: `sample show`에서 URL을 가져와 다시담기의 상품 등록·장바구니 점검 입력에 재사용합니다.

```powershell
dotnet publish .\tools
$sample = .\tools\dist\RebuyClipboard.exe sample show | ConvertFrom-Json
$sample.productUrl
.\tools\dist\RebuyClipboard.exe sample open
```

샘플은 게시할 때 EXE 안에 포함되므로 EXE만 옮겨도 사용할 수 있습니다. JSON을 수정한 뒤에는 `dotnet publish .\tools`로 다시 게시합니다. mock 검사는 재게시하지 않은 예전 데이터와 원본 JSON이 다르면 실패합니다.

## 필드

| 필드 | 용도 |
| --- | --- |
| schemaVersion | 데이터 형식 버전. 현재 1 |
| id | 실행 기록에서 사용할 샘플 식별자 |
| productUrl | 사용자가 지정한 쿠팡 HTTPS URL |
| expectedProductName | 실제 상품 화면에서 확인한 정확한 상품명. 미확인 시 null |
| expectedOption | 실제 상품 화면에서 확인한 정확한 옵션. 미확인 시 null |

상품명과 옵션은 링크만 보고 추측하지 않습니다. 상품명은 실제 상품 화면에서 확인해 기록했습니다. 선택 옵션도 실제 상품 화면과 앱 OCR에서 확인해 기록했습니다. 상품 화면과 스크린샷을 확인한 뒤 이 파일에 추가하면 이후 OCR·옵션 점검의 기준으로 사용할 수 있습니다.

`sample open`은 URL 열기 요청만 수행합니다. Android가 요청을 수락해도 실제 상품이 맞는지까지 자동 검증하지 않습니다. 담기 시작·주문·결제 버튼은 누르지 않으며 다시담기 DB에도 상품을 저장하지 않습니다.

32×24 합성 PNG는 전송 바이트 검사용으로 유지합니다. 실제 상품 이미지와 다른 용도이므로 이 샘플 URL의 상품명·옵션 근거로 사용하지 않습니다.

## 구매 주기 설정 샘플

[purchase-settings.json](purchase-settings.json)은 2026-10-28 사용자 표와 마지막 ‘주기만’ 요청을 반영한 설정 샘플입니다. 앱의 상품 수정 화면에서 바나나 7일, 포스트 단백질바 미니 10일, 참치 45일(1~2개월의 대표값), 양반죽 14일을 저장했습니다. 상품 링크·옵션·주문 수량·활성 상태·다음 구매일·금액 한도·구매 로그는 유지합니다. 표의 첫 구매 포장은 참고 정보로만 남기며 현재 참치 150g × 10캔, 구운계란/훈제란 15구 옵션도 바꾸지 않았습니다.

반숙란의 ‘소비기한에 맞춰’, 자숙새우의 ‘냉동실이 있을 때만’, 훈제연어의 ‘먹을 주에 구매’는 `note_only` 조건 메모입니다. 이 세 항목의 `intervalDays: null`과 빈 `applyFields`는 기존 주기를 비우거나 비활성화하라는 뜻이 아닙니다. 앱에는 기존 30일·활성 상태가 유지되며 이 메모를 자동 구매 조건으로 실행하지 않습니다.

`configuredSnapshot`은 설정 직후의 참고 상태이며 적용 대상은 네 항목의 `intervalDays`뿐입니다. 다음 구매일은 바나나·단백질바·참치 2026-10-28, 죽 2026-11-26 그대로입니다. 이 JSON은 앱 가져오기 기능과 연결되어 있지 않습니다. CLI의 `sample show`·`sample open`도 계속 기존 상품 링크 샘플을 사용하며 이 파일을 읽거나 적용하지 않습니다.

샘플의 `registeredProduct.productUrl`에는 실제 앱에 등록된 일곱 상품의 링크도 함께 보관합니다. 앱은 자체 저장된 링크로 동작하며 이 JSON을 실행 설정으로 읽지는 않습니다. 기존 등록 링크를 그대로 옮겼으므로 계란 링크에는 상품 번호만 있고 옵션 식별자는 없습니다(`urlHasOptionIds: false`). 나머지 여섯 링크는 itemId·vendorItemId가 포함되어 있습니다. 이 값은 URL 구조 확인 결과이며 새 기기에서의 재등록·쿠팡 연동 성공을 검증했다는 뜻은 아닙니다.
