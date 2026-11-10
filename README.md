# Lucky Draw · Pleos Connect Android 앱

**Android Studio에서 열 실행용 프로젝트:** `C:\Users\josuh\AndroidStudioProjects\LuckyDraw`  
바탕 화면 OneDrive의 `Lucky_Draw_AndroidStudio`는 이전 작업본으로 남겨 두었습니다. OneDrive 안에서 빌드하면 Gradle 잠금 파일과 생성 리소스가 동기화 과정에서 잠길 수 있으므로 이 실행용 폴더를 여세요.

Android Studio에서 이 폴더를 프로젝트로 엽니다. 이 프로젝트는 Pleos Connect 차량용 Android Automotive 앱입니다. 실제 분할 화면 배치와 운전 중 앱 표시 허용 여부는 차량의 Pleos 호스트 설정과 검수 정책에 따릅니다.

**프로젝트 구분:** 이 앱의 표시 이름은 `Lucky Draw`, 패키지는 `com.example.luckydraw`입니다. 기존 `C:\Users\josuh\AndroidStudioProjects\luckyy` 프로젝트의 표시 이름 `luckyy`와 TMAP 지도 화면은 별도 앱입니다. 에뮬레이터에 `luckyy`가 보이면 이 프로젝트의 `app` 실행 구성을 선택해 다시 Run 하세요. 기존 프로젝트를 삭제하거나 파일을 복사해 덮어쓸 필요는 없습니다.

## 준비

- Android Studio와 JDK 17, 컴파일용 Android SDK 37. 이 컴퓨터에는 SDK 37과 Pleos Connect API 34 에뮬레이터 이미지가 설치되어 있습니다.
- `minSdk = 28`은 앱이 실행될 수 있는 최소 Android 버전입니다. SDK 28 플랫폼을 별도로 설치하라는 뜻이 아닙니다. `compileSdk = 37`은 이 프로젝트를 컴파일하는 SDK이고, Pleos 에뮬레이터의 API 34는 `minSdk`보다 높으므로 실행 대상입니다.
- `useLibrary("android.car")`가 차량 API를 컴파일할 수 있게 합니다. `AndroidManifest.xml`은 Automotive 기기를 필수로 선언하므로 일반 휴대전화 에뮬레이터 대신 Pleos Connect 에뮬레이터를 선택하세요.
- `settings.gradle.kts`에 Pleos Maven 저장소가 등록되어 있습니다. 정상 동기화에는 해당 저장소 접근 권한이 필요합니다.
- 실제 차량 또는 Pleos Connect 에뮬레이터에서 차량 서비스와 내비게이션 서비스를 사용할 수 있어야 합니다.
- 위치 권한은 앱에서 요청합니다. 기어 판독에는 `CAR_POWERTRAIN`, 브레이크 판독에는 `CAR_DRIVE`, 순정 내비 위치 판독에는 `NAVI_ROUTE`가 필요합니다.

## 실행

1. Android Studio에서 **File > Open**을 선택하고 `C:\Users\josuh\AndroidStudioProjects\LuckyDraw` 폴더 자체를 엽니다. `app` 폴더나 개별 Kotlin 파일을 새 프로젝트에 복사하지 마세요.
2. Gradle의 **Offline work**를 끄고 **Sync Project with Gradle Files**를 실행합니다.
3. Device Manager에서 `Pleos_Connect_v2.1.0` 또는 API 34 Pleos Connect AVD를 시작합니다. 일반 휴대전화 AVD에서는 이 앱을 실행할 수 없습니다.
4. 실행 구성을 `app`, 대상 기기를 Pleos AVD로 선택한 뒤 **Run**을 누릅니다.
5. D 기어에서 간단한 안내를 표시하고, P 기어에서 결과를 표시합니다. 에뮬레이터에서는 하단 테스트 버튼으로 화면을 먼저 확인할 수 있습니다.

Park 결과 화면은 제공된 분위기 그림을 참고해 만든 초원·꽃길 배경(`res/drawable-nodpi/park_meadow.png`) 위에 프리미엄 요정 벡터와 반투명 결과 카드를 배치했습니다. Drive 화면은 주행 중 가독성을 위해 기존의 단순한 배경을 유지합니다.

이 폴더에서 서명된 `:app:assembleDebug` 빌드를 확인했습니다. 빌드 출력은 OneDrive 밖의 `%LOCALAPPDATA%\LuckyDrawAndroidStudio\build\...\app\outputs\apk\debug`에 생성됩니다. 개발용 디버그 키도 `%LOCALAPPDATA%\LuckyDrawAndroidStudio\debug.keystore`에만 둡니다. 프로젝트 소스나 APK에 프로젝트 ID·클라이언트 시크릿을 넣지 않았습니다.

`activity_main.xml` 미리보기에서 `assetStream is null` 글꼴 오류가 났다면 테마의 글꼴 이름을 `sans-serif`로 수정한 뒤 미리보기를 새로고침하세요. 미리보기 오류와 에뮬레이터에서 기존 `luckyy` 앱이 보이는 현상은 서로 다른 문제입니다.

현재 이 컴퓨터에서는 NaviHelper 3.0.5 파일을 내려받지 못해 `gradle.properties`의 `skipPleosNavi=true`를 기본으로 설정했습니다. 이 상태에서는 Android 위치 제공자를 사용하며 순정 내비 도로명은 표시되지 않습니다. Pleos Maven 접속이 가능해지면 `skipPleosNavi=false`로 바꾸고 Gradle Sync를 다시 실행하면 NaviHelper 3.0.5가 앱에 포함됩니다.

Gradle 동기화 실패 시 Android Studio의 Gradle **Offline work**가 꺼져 있는지 확인하세요. 이 프로젝트는 `settings.gradle.kts`에서 Pleos 그룹을 공식 Maven 저장소로 먼저 보냅니다. `Permission denied: getsockopt`는 네트워크 제한, `401/403`은 접근 권한, `Could not find`는 버전 또는 저장소 경로를 먼저 점검할 오류입니다. Maven 다운로드에는 프로젝트 ID나 클라이언트 시크릿을 빌드 파일에 추가하지 않습니다.

Android Studio 동기화가 `AccessDeniedException: C:\.android`로 실패한다면, 프로젝트를 다시 동기화하세요. `settings.gradle.kts`가 Windows 사용자 프로필의 Android 설정 폴더를 선택하도록 보정했습니다. 이전 Gradle 데몬이 잘못된 경로를 계속 사용하면 Android Studio의 Terminal에서 `./gradlew --stop`을 실행한 뒤 다시 동기화할 수 있습니다.

NaviHelper의 `getCurrentLocationInfo()`는 조회 요청입니다. 결과는 등록한 `NaviHelperEventListener.onCurrentLocationInfo()`에서 비동기로 받습니다. 앱은 연결 성공 후 리스너를 등록하고, D 상태에서 1초마다 조회 요청을 보내며, P 상태와 화면 종료 시 요청과 리스너를 정리합니다. 자세한 구조는 `NaviBridge.kt`에 있습니다.

## 데이터와 개인정보

`app/src/main/assets/traffic_lights.json`은 제공받은 전국 신호등 JSON의 50,000건을 읽어 유효한 좌표 20,895개로 정리한 결과입니다. 앱에는 임의 ID와 위도·경도만 포함됩니다. 원본의 주소, 기관명, 전화번호 등은 들어가지 않습니다. `app/src/main/assets/drive_routes.json`은 한국관광공사 열린관광지 목록을 오프라인 추천 코스 111곳으로 정제한 데이터입니다.

앱은 서버·외부 지도·API 키를 사용하지 않고 위치 이력도 저장하지 않습니다. 위치는 D 기어 화면이 활성화된 동안만 사용합니다. 앱에 `INTERNET` 권한을 선언하지 않았습니다.

Pleos Playground의 프로젝트 ID는 앱 심사 요청 때 App Market Console에 입력합니다. 현재 Vehicle State와 NaviHelper 기능에는 클라이언트 ID·시크릿이 필요하지 않습니다. 이 자격 정보는 서버 연결이 필요한 다른 SDK에서 쓰이므로 이 프로젝트의 Kotlin 코드, Gradle 설정, 리소스, APK에 넣지 마세요. 테스트 CRN은 Pleos Connect Emulator 2.1.0 이후 필요하지 않습니다. 로컬 자격 정보 파일을 만들 경우 프로젝트 밖에 보관하고, 공개 저장소에 올리지 마세요. 상세 설명은 바탕 화면의 코드 설명 보고서에 있습니다.

## 규칙

주행 시작 점수는 100점입니다. 신호등 30m 이내에서 브레이크가 연속 5초 이상 감지되면 해당 신호등에 한해 10점을 한 번 감점합니다. 무정차 통과 보너스는 없습니다. 기획서의 일부 문장에는 10초 기준이 섞여 있지만 구현은 명시된 5초 규칙을 따릅니다. 이 점수는 오락용이며 실제 교통 신호와 안전 운전이 우선입니다.
