# 제3자 구성요소

자체 소스의 MIT 라이선스는 아래 SDK·모델·도구의 별도 조건을 대체하지 않습니다. Gradle은 직접 및 전이 의존성을 내려받습니다. 배포 전 전체 의존성 및 해당 버전의 고지문을 검토해야 합니다.

| 구성요소 | 직접 사용 버전 | 라이선스/공식 확인 위치 |
|---|---|---|
| AndroidX Room | 2.8.4 | Apache-2.0, https://developer.android.com/jetpack/androidx/releases/room |
| AndroidX WorkManager | 2.11.0 | Apache-2.0, https://developer.android.com/jetpack/androidx/releases/work |
| AndroidX ExifInterface | 1.3.7 | Apache-2.0, https://developer.android.com/jetpack/androidx/releases/exifinterface |
| AndroidX Palette | 1.0.0 | Apache-2.0, https://developer.android.com/jetpack/androidx/releases/palette |
| ZXing core | 3.5.3 | Apache-2.0, https://github.com/zxing/zxing |
| NanoHTTPD | 2.3.1 | Modified BSD, https://github.com/NanoHttpd/nanohttpd |
| Google ML Kit face-detection | 16.1.7 | Google API/ML Kit 조건, https://developers.google.com/ml-kit/terms |
| JUnit (테스트) | 4.13.2 | EPL-1.0, https://junit.org/junit4/ |
| Gradle Wrapper | 8.13 | Apache-2.0, https://github.com/gradle/gradle |

ML Kit의 번들 얼굴 모델과 Google Play services 전이 라이브러리는 이 저장소가 직접 작성한 오픈소스가 아닙니다. 얼굴 좌표 추출은 기기에서 실행하지만, SDK의 진단 데이터 동작 및 고지 요건은 공식 [Android 데이터 공개](https://developers.google.com/ml-kit/android-data-disclosure)를 확인해야 합니다.

액자 문구 32개, 기본 벡터 아이콘, HTML/CSS/Java 앱 코드는 이 프로젝트의 자체 콘텐츠입니다. 배포용 사진, 외부 폰트, 제3자 인용 명언, 외부 추적 스크립트는 포함하지 않습니다.
