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
