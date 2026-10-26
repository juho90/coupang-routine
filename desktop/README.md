# 다시담기 PC 이미지 보내기

Windows에서 캡처한 상품 이미지를 이 PC의 BlueStacks 안에 설치된 다시담기 앱으로 보냅니다. PC와 Android 사이의 ADB 연결만 사용하며 서버는 필요하지 않습니다.

## 사용하기

1. BlueStacks를 실행하고 PC 이미지 받기를 지원하는 다시담기 앱을 설치합니다.
2. `RebuyClipboard.exe`를 실행합니다. 이 PC에는 필요한 .NET Windows Desktop Runtime 10이 설치되어 있습니다.
3. `Win+Shift+S`로 상품 화면을 캡처합니다.
4. PC 동반 앱에서 **클립보드 이미지 보내기**를 누르거나, 그 창에 포커스를 둔 상태에서 **Ctrl+V**를 누릅니다.
5. BlueStacks의 다시담기 앱에서 읽은 상품명·옵션·가격을 확인하고 입력란에 적용합니다. 읽은 표시가격과 배송비를 포함한 최종 금액은 다를 수 있습니다.

배포 폴더의 `RebuyClipboard.exe`, `RebuyClipboard.dll`, `*.json` 파일을 같은 폴더에 유지해 주세요. 실행 파일만 다른 곳으로 옮기면 실행되지 않습니다. 다른 PC에서는 .NET Windows Desktop Runtime 10이 필요합니다.

ADB 기본 경로는 자동으로 찾습니다. 찾지 못하면 **ADB 선택**으로 `C:\platform-tools\adb.exe`를 지정합니다. 기본 연결 주소는 `127.0.0.1:5555`이며, 다른 BlueStacks 인스턴스는 해당 인스턴스의 로컬 ADB 주소를 입력합니다. ADB가 꺼져 있다면 BlueStacks의 ADB 연결을 먼저 준비해야 합니다.

동반 앱은 버튼이나 자체 창의 Ctrl+V 입력 때만 클립보드 이미지를 읽습니다. 자동 감시·자동 캡처를 하지 않으며, 클립보드를 쓰거나 지우지 않습니다. 텍스트는 읽지 않습니다. 이미지는 메모리에서 PNG로 변환해 다시담기의 전용 이미지 수신 경로로 보냅니다. PC에는 이미지 사본이나 이미지 경로 로그를 남기지 않습니다. Android 앱은 임시 수신 이미지를 읽고 자체 만료 정책에 따라 정리합니다.

이미지는 가로·세로 각 60,000픽셀, 전체 1억6천만 픽셀, 변환된 PNG 24MB 이하를 지원합니다. 전송 후에도 내용을 검토하고 적용해야 하며, 이 동반 앱은 상품 저장·장바구니 담기·주문·결제를 실행하지 않습니다.

## 합성 이미지로 시험하기

아래 명령은 지정한 이미지 파일만 읽으며 클립보드는 읽지 않습니다. 파일은 실제 이미지로 해석한 뒤 GUI와 같은 PNG 변환·크기 제한을 적용합니다.

```powershell
$arguments = @('--image', 'C:\test\synthetic-product.png', '--send', '--adb', 'C:\platform-tools\adb.exe', '--device', '127.0.0.1:5555')
$result = Start-Process -FilePath .\RebuyClipboard.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput .\transfer.stdout.log -RedirectStandardError .\transfer.stderr.log
$result.ExitCode
Get-Content .\transfer.stdout.log, .\transfer.stderr.log
```

`--send`를 빼면 이미지 형식만 확인합니다. 위 명령은 이미지를 전달하고 Android 앱의 검토 화면을 엽니다. GUI 실행 파일이므로 PowerShell에서는 `Start-Process -Wait`와 출력 리디렉션으로 결과를 확인합니다. 종료 코드 `0`은 성공, `2`는 입력·전송 오류, `3`은 중단·시간 초과, `4`는 예상하지 못한 처리 오류입니다. 원본 파일 경로와 이미지 바이트는 출력하지 않습니다. 예시 파일 경로를 바꾸는 경우 공백이 있는 경로는 `ArgumentList` 안에서도 큰따옴표로 감싸 주세요.

## 다시 빌드하기

소스 폴더의 `build.ps1`을 PowerShell에서 실행합니다. .NET SDK 10과 Windows Desktop 참조 팩이 필요하며, NuGet 소스는 비활성화되어 외부 패키지를 내려받지 않습니다. 기본 출력은 작업 폴더의 `outputs/pc-clipboard-1.0.1`입니다.

```powershell
.\desktop\build.ps1
```

빌드는 Windows x64용이며 설치된 .NET Windows Desktop Runtime 10을 사용합니다. 빌드나 실행 중 시스템 설정과 클립보드 설정은 변경하지 않습니다.
