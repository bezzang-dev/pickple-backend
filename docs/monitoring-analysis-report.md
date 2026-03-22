
## 모니터링 구현 분석

전체 아키텍처

┌─────────────────────────────────────────────────┐
│           Grafana (Port 3000) - UI 대시보드      │
├────────────────┬───────────────┬─────────────────┤
│  Prometheus    │     Loki      │     Zipkin       │
│  (Port 9090)   │  (Port 3100)  │  (Port 9411)     │
│  메트릭 수집   │  로그 집계    │  분산 추적       │
└────────────────┴───────────────┴─────────────────┘
파일 위치
역할	경로
Docker Compose	docker/docker-compose.monitor.yml
Prometheus 설정	settings/prometheus/config/prometheus.yml
Loki 설정	settings/grafana/loki/loki-config.yml
Grafana 데이터소스	settings/grafana/provisioning/datasources/gf_datasource.yml
Grafana 알림 규칙	settings/grafana/provisioning/alerting/gf_alertrule.yml
Grafana 알림 채널	settings/grafana/provisioning/alerting/gf_contactpoint.yml
대시보드 설정	settings/grafana/provisioning/dashboards/
1. Prometheus - 메트릭 수집
settings/prometheus/config/prometheus.yml에서 8개 마이크로서비스 + Node Exporter를 스크래핑합니다.

수집 간격: 10초
모든 서비스의 /actuator/prometheus 엔드포인트 대상
포트 19090~19097 범위 (서비스별 고정 포트)
host.docker.internal을 통해 호스트의 서비스에 접근
각 서비스는 build.gradle에 아래 의존성을 포함합니다:


runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
implementation 'org.springframework.boot:spring-boot-starter-actuator'
2. Loki - 로그 수집
애플리케이션 → Loki 로그 흐름:

각 서비스의 logback-spring.xml에 Loki4jAppender가 설정되어 있어, 로그를 HTTP로 Loki에 직접 전송합니다.


<appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
  <http><url>${LOKI_URL}</url></http>
  <format>
    <label><pattern>service=${SERVICE_NAME}</pattern></label>
    <message class="com.github.loki4j.logback.JsonLayout"/>
  </format>
</appender>
service=user-service, service=commerce-service 등 라벨로 서비스 구분
JSON 형식 전송으로 Loki 쿼리 최적화
개발 환경은 파일시스템 기반 저장소 (/tmp/loki)
3. Zipkin - 분산 추적

management:
  zipkin:
    tracing:
      endpoint: "http://localhost:9411/api/v2/spans"
  tracing:
    sampling:
      probability: 1.0  # 100% 샘플링
micrometer-tracing-bridge-brave + zipkin-reporter-brave 조합
모든 요청을 추적 (100% 샘플링 → 프로덕션에서는 낮춰야 함)
4. Grafana Provisioning - 자동화 핵심
Grafana가 시작될 때 /etc/grafana/provisioning/ 디렉토리를 읽어 수동 설정 없이 자동으로 구성됩니다:

데이터소스: Prometheus + Loki 자동 연결

대시보드: 6개 서비스의 로그를 표시하는 패널 자동 생성

알림 규칙 3가지:

server-alert: Eureka 서버 다운 감지 (up < 1, 1분 지속)
hot-traffic-alert: 초당 요청 40,000건 초과 (위험, 3분 지속)
traffic-alert: 초당 요청 25,000건 초과 (경고, 3분 지속)
알림 채널: Slack 웹훅 2개 (서버 상태용, 트래픽용) — 환경 변수 GF_SERVER_CHANNEL, GF_TRAFFIC_CHANNEL로 설정

주요 설계 특징
완전 자동화: Grafana provisioning으로 어떤 환경에서도 동일한 대시보드/알림 자동 적용
서비스 레이블링: Prometheus job + Loki label로 각 서비스 메트릭/로그 분리 조회 가능
Slack 통합: 알림이 자동으로 Slack으로 전송 (실시간 대응)
개발 환경 한계: Loki는 파일시스템 저장, Zipkin은 메모리 기반 → 프로덕션에서는 영구 저장소 필요