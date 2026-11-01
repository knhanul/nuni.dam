# Play 배포와 유무료 분리 후속 작업

현재 소스에는 결제 SDK·상품 조회·구매·복원 코드가 없습니다. 개발용 전체 기능 공개판이며, `Pro` 버튼으로 결제가 된 것처럼 표시하거나 임의 구매 상태를 만들지 않습니다. 원안의 무료 100장 및 테마 제한도 아직 적용하지 않았습니다.

실제 배포 단계에서 다음 연결을 구현해야 합니다.

| 연결 지점 | 필요한 구현 |
|---|---|
| `PhotoStore.process` | 새 고유 사진을 등록하기 전에 공통 이용 권한 정책 확인. 로컬·QR·동기화 모두 같은 경로로 제한 |
| `PhotoStore.restore` | 무료 복원 제한의 제품 정책 결정. 기존 사진을 결제 해제 이유로 삭제하지 않기 |
| `Prefs.restore` / QR 설정 | UI뿐 아니라 실제 설정 적용 경로에서도 Pro 기능 권한 확인 |
| `SettingsActivity` | 실제 상품 가격·구매·복원·오류 안내, 중복 요청 제어 |
| 별도 결제 계층 | Google Play Billing 연결, 상품 조회, 구매 상태 확인, 완료 구매 검증·승인 |
| 서버 계층 | 필요에 따라 구매 토큰 검증, 취소·환불 처리, 재설치·기기 변경·오프라인 정책 |
| 배포 설정 | 본인 소유 applicationId, Play Console 앱·일회성 상품, 앱 서명, 테스트 계정·내부 테스트 |

첫 판매 상품은 비소모성 일회성 Pro 언락 형태로 설계할 수 있습니다. 결제 처리 중인 구매에는 권한을 부여하지 않고, 사용자 취소·네트워크 오류·이미 구매한 항목·복원·환불을 별도로 시험해야 합니다. 검증되지 않은 로컬 플래그만으로 정식 구매 여부를 판단하지 마세요.

`100장`은 활성 사진인지 휴지통 포함인지, 동일 파일 중복은 어떻게 셀 것인지를 제품 정책으로 고정하세요. 무료 한도 초과로 기존 사진을 자동 삭제하지 않는 방식이 현재 보관 정책과 맞습니다. 구독 상품은 구현하지 않았으며 이 소스만으로 Play 결제가 발생하지 않습니다.

공식 문서 확인 지점:

- [Google Play Billing 통합](https://developer.android.com/google/play/billing/integrate)
- [구매 보안](https://developer.android.com/google/play/billing/security)
- [결제 테스트](https://developer.android.com/google/play/billing/test)
- [ML Kit Android 데이터 공개](https://developers.google.com/ml-kit/android-data-disclosure)

출시 시점의 Billing 버전·target API·데이터 보안·SDK 조건은 다시 확인해야 합니다. 본 문서는 출시 완료나 정책 심사 통과를 뜻하지 않습니다.
