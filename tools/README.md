# 다시담기 이미지 전송 CLI

Windows 콘솔에서 실행하는 이미지 전송 도구입니다. GUI 창 없이 결과는 stdout, 진행 상태와 오류는 stderr로 출력합니다.

## 게시

저장소 루트에서 아래 명령을 실행합니다. .NET SDK 10이 필요합니다.

```powershell
dotnet publish .\tools
```

게시할 때 WindowsX64 프로필을 기본으로 선택합니다. .NET 10의 기본 Release 게시를 사용하므로 -c나 -p 옵션이 필요하지 않습니다. tools 폴더 안에서는 dotnet publish만 실행해도 됩니다. 설정은 [Properties/PublishProfiles/WindowsX64.pubxml](Properties/PublishProfiles/WindowsX64.pubxml)에 있습니다. Windows x64용 단일 EXE를 `tools/dist`에 만들고 README도 함께 복사합니다. 별도의 build.ps1은 사용하지 않습니다. 실행할 PC에는 .NET Windows Desktop Runtime 10이 필요하며 ADB는 별도로 준비합니다.

출력 폴더를 바꾸려면 `-o 'C:\output\rebuy-cli'`를 추가합니다. 명령을 실행한 위치와 관계없이 기본 출력은 프로젝트의 dist 폴더입니다. SDK용 패키지는 공식 NuGet에서 복원하며 패키지는 `tools/obj/packages`에 둡니다. `bin`, `obj`, `dist`는 Git에서 제외합니다.

게시 프로필 사용법은 [공식 dotnet publish 문서](https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-publish#pubxml-files)를 따릅니다.


현재 도구는 Windows 클립보드와 System.Drawing을 사용하므로 Windows에서 동작합니다. C#/.NET 자체는 여러 OS를 지원하지만, 이 의존성을 교체하거나 분리하기 전에는 DLL도 Windows 전용입니다. [.NET 게시 방식](https://learn.microsoft.com/en-us/dotnet/core/deploying/)과 [System.Drawing의 Windows 지원 범위](https://learn.microsoft.com/en-us/dotnet/core/compatibility/core-libraries/6.0/system-drawing-common-windows-only)를 참고하세요.


## 실행

```powershell
.\tools\dist\RebuyClipboard.exe --help
.\tools\dist\RebuyClipboard.exe --version

# 연결된 Android 장치 목록
.\tools\dist\RebuyClipboard.exe devices --adb 'C:\platform-tools\adb.exe'

# 이미지 확인만 하기
.\tools\dist\RebuyClipboard.exe check --image 'C:\captures\product image.png'

# 파일 보내기
.\tools\dist\RebuyClipboard.exe send --image 'C:\captures\product image.png' --device '127.0.0.1:5555'

# Win+Shift+S로 캡처한 뒤 클립보드 이미지 보내기
.\tools\dist\RebuyClipboard.exe send --clipboard

# 직전 명령 결과
$LASTEXITCODE
```

인자 없이 실행하면 도움말을 표시합니다. PowerShell이나 명령 프롬프트에서 사용하세요. 이전 `--image <파일> [--send]` 형식도 지원하며 `--send`가 없으면 확인만 합니다. 경로에 공백이 있으면 따옴표로 감쌉니다. Ctrl+C로 중단할 수 있고 전체 제한 시간은 45초입니다.

`--adb`를 생략하면 EXE 옆, PATH, `C:\platform-tools\adb.exe`, 사용자 Android SDK 등의 위치를 찾습니다. 기본 기기는 `127.0.0.1:5555`이며 `--device`로 바꿀 수 있습니다. BlueStacks와 ADB 연결을 켜고 PC 이미지 받기를 지원하는 다시담기 앱을 설치해야 합니다.

클립보드는 `send --clipboard`를 실행할 때만 읽습니다. 자동 감시·캡처·클립보드 변경은 하지 않습니다. 파일 입력은 클립보드를 읽지 않습니다. 이미지 사본을 PC에 저장하지 않고 메모리에서 PNG로 변환해 앱의 전용 수신 경로로 보냅니다. PNG는 24MB, 가로·세로 각 60,000픽셀, 전체 1억6천만 픽셀 이하입니다.

전송 성공은 Android의 검토 화면이 열렸다는 의미입니다. 앱에서 내용을 확인하고 적용해야 합니다. 상품 저장·장바구니 담기·주문·결제를 실행하지 않습니다.

## 공통 샘플로 기능 확인

공통 데이터는 저장소의 [testdata/coupang-product.json](../testdata/coupang-product.json)에 있습니다. 게시 시 EXE에 포함하며 mock 검사도 같은 JSON을 읽습니다.

```powershell
# 샘플 데이터 확인
.\tools\dist\RebuyClipboard.exe sample show

# 같은 URL을 BlueStacks의 쿠팡 앱에서 열기
.\tools\dist\RebuyClipboard.exe sample open --device '127.0.0.1:5555'
```

`sample open`은 Android 기본 링크 처리기로 공유 URL을 엽니다. 짧은 공유 링크는 브라우저를 거쳐 쿠팡 앱으로 연결될 수 있습니다. 실제 상품 화면은 기기에서 확인해야 합니다. 상품 입력 자동 적용·저장·담기·주문·결제는 실행하지 않습니다. 상품명은 실제 상품 화면에서 확인한 값이며, 별도의 선택 옵션은 아직 미확인이라 null입니다. 변경·추가 방법은 [테스트 데이터 안내](../testdata/README.md)를 참고하세요.


## 종료 코드

| 코드 | 의미 |
| --- | --- |
| 0 | 도움말·조회·확인·전송 성공 |
| 2 | 잘못된 명령·이미지·ADB 연결 또는 전송 오류 (단계별 시간 초과 포함) |
| 3 | 사용자 중단 또는 전체 제한 시간 초과 |
| 4 | 예상하지 못한 처리 오류 |

진행 상태도 stderr로 나가므로 종료 코드로 성공 여부를 판단하세요. 이미지 경로와 이미지 바이트는 출력하지 않습니다.

빌드 설정 참고: [.NET 단일 파일 배포](https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview), [콘솔 출력 형식](https://learn.microsoft.com/en-us/dotnet/core/compatibility/sdk/5.0/automatically-infer-winexe-output-type).


## 개발 중 CLI 점검

PowerShell 7에서 아래 명령을 실행합니다. 실제 ADB 대신 별도 mock EXE를 쓰며 클립보드나 기기에 접근하지 않습니다.

```powershell
.\tools\tests\build-mock.ps1
.\tools\tests\smoke.ps1 -Executable .\tools\dist\RebuyClipboard.exe
```

도움말·버전·입력 오류·이미지 검사·합성 이미지 전송·장치 조회와 공통 샘플 표시·URL 열기까지 11개를 확인합니다. mock은 전달 인자, PNG 픽셀, 전송 ID 재사용과 READY 확인 후 앱 실행 순서도 검사합니다. 검사 로그는 `tools/tests/obj/run-*`에 생성합니다.
