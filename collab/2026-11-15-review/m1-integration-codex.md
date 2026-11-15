# M1 합본 테스트 검수 · codex

대상: Sol의 불변 `03e2c4ccaf48185f2f2fadc32bef3b4f18eb8b35`에 추가된 `OfficialFixtureIntegrationTest`와 보고. 초기 기반은 #4 e709922 + #5 지정 head + 공식 fixture이며 최신 #4 반영 결과는 별도 확인한다.

신규 3개 테스트를 수용한다. 전체 공식 목록 497건은 실제 파서·반영 트랜잭션·DB를 지나며 불필요한 상세 fanout을 하지 않는다. 상세는 원본 목록에서 307032 한 행을 발췌하고 totalCount만 조정한 파생 입력을 사용한다. 실제 DetailCollector·RecruitmentClient·영속 ApiBudget·DB에서 공식 상세를 저장하고 MockMvc HTML에 전형 숫자·첨부·우대·기준 시각이 나오는지 확인한다. 목록 재반영 후 DONE 상세와 변경 0도 확인한다. 빈 공식 응답은 정기 INCOMPLETE와 최초 적재 COMPLETE 0건의 차이를 검증한다.

고정 loopback 대역, 실제 키 없는 fixture-only 값, 스케줄·최초 적재 자동 실행 비활성, 테스트 전후 전체 테이블 정리, 미예상 대역 요청 검출이 있다. 화면 렌더링이 추가 API를 부르지 않는 것도 요청 수로 검사한다. source 버튼은 단일 발췌 입력에서 공유 URL 판정 근거가 사라진 점을 반영해 검사한다.

이 테스트는 실제 배포·브라우저·공식 API 재호출을 하지 않는다. 전체 목록의 RegularCollector HTTP/스케줄 경로를 통째로 실행하는 시험도 아니다. 그 부분은 기존 실행기 통합 테스트와 함께 판단한다. 전형 행의 일반적 의미를 새로 추정하지 않고 현재 화면 계약을 확인했다.

root는 소스를 읽기 검수했고 로컬 테스트를 다시 실행하지 않았다. 최신 56faf051 반영 후 합본 check/bootJar 결과와 Claude의 독립 리뷰를 마지막으로 대조한다.
