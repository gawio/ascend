# Payment Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Chế độ thực thi đã chọn từ đầu: MENTOR.** Người dùng tự gõ code. AI mỗi lượt chỉ làm 1 Task: giải thích 3–5 câu "vì sao", vẽ diagram (sequence / state machine / activity), đưa phần cốt lõi + `// TODO(bạn gõ)`, chạy điểm kiểm tra cùng người dùng, 2–3 câu hỏi tự kiểm (đáp án trong `<details>`), rồi DỪNG chờ "xong task N".

**Goal:** Dựng Payment Hub mock (BILLING + TOPUP) trên Vert.x 5: partner gọi REST, back-office gọi gRPC, Hub gọi từng connector supplier qua RabbitMQ RPC.

**Architecture:** Maven multi-module, monorepo root `ascend/` chứa `platform/` (dùng chung), `payment-hub/`, `connector/` (sau này thêm `ems/`, `noti/` ngang hàng). `platform-core` (AppData, Task/Workflow callback, MainVerticle đọc `verticle.json`, Guice) và `platform-messaging` (RabbitMQ RPC Direct Reply-to) dùng chung; `payment-hub` (1 service) chứa cửa vào REST/gRPC + workflow; mỗi loại dịch vụ là 1 connector service (`connector-topup` gọi SUP_A, `connector-billing` gọi SUP_B); `supplier-simulator` giả lập API nhà cung cấp.

**Tech Stack:** Java 25 (`25.0.2-tem`), Maven 3.9 multi-module, Vert.x 5.2.3, Guice 7.0.0, Jackson (theo BOM Vert.x), Lombok 1.18.48, Caffeine 3.3.1, Protobuf + vertx-grpc, Postgres 17, RabbitMQ 4 (management), Redis 7, JUnit 5.14.4 + vertx-junit5.

**Spec:** `.claude/specs/2026-11-21-payment-hub-design.md`

## Global Constraints

- Không Spring, không ORM, không Flyway, không Testcontainers. Thư viện chỉ như mục "Tech Stack".
- Java 25: `maven.compiler.release=25`; Maven phải chạy bằng JDK 25 (`.sdkmanrc` → `java=25.0.2-tem`); `maven-enforcer-plugin` `requireJavaVersion [25,)`.
- Lombok 1.18.48 (≥ 1.18.40 cho JDK 25), khai báo trong `annotationProcessorPaths` của `maven-compiler-plugin` (JDK 23+ không tự dò annotation processor).
- `pom.xml` gốc `dev.gawio:ascend:0.1.0-SNAPSHOT`, `packaging=pom` (monorepo); artifactId = tên module. Phần dùng chung ở `platform/`, payment hub ở `payment-hub/`, connector ở `connector/`; module tương lai (`ems`, `noti`, reconciliation) là thư mục ngang hàng.
- Package: `dev.gawio.platform.core`, `dev.gawio.platform.messaging` (dùng chung); `dev.gawio.payhub.hub`, `dev.gawio.simulator`, `dev.gawio.connector.topup`, `dev.gawio.connector.billing`. `platform/*` không phụ thuộc module nào khác; `payment-hub/*` và `connector/*` chỉ phụ thuộc `platform/*`, **không phụ thuộc lẫn nhau**.
- Bảng Postgres thân thiện CDC (cho EMS sau này): có khoá chính, `updated_at`, không xoá cứng giao dịch; Postgres chạy `wal_level=logical`.
- Không chặn event loop: không `Thread.sleep`, JDBC/HTTP đồng bộ, `.join()`, `toCompletionStage().get()`.
- Mọi nhánh `Task.exec` gọi `done` đúng 1 lần, kể cả trong catch; lỗi = `result=false` + `errorCode`, không throw.
- `result=false` nghĩa là request bị từ chối **trước khi chạm tiền**. Trạng thái tiền nằm ở `PaymentContext.txnStatus`; timeout/lỗi gọi connector giữ `result=true`, `txnStatus=UNKNOWN`.
- Chỉ FAILED khi supplier xác nhận thất bại. Timeout / lỗi mạng / mã lạ → UNKNOWN.
- Queue: `payment.<topup|billing>` (theo loại dịch vụ, không theo partner/supplier); header message `x-request-id`, `x-partner-code`; log tên queue mỗi lần publish. Thuật ngữ: partner = bên gọi vào Hub, connector/supplier = bên Hub gọi ra.
- Timeout local: connector→supplier **10s** < Task chờ RabbitClient + cache TTL **12s** < Route/gRPC chờ verticle **15s**. Lock Redis TTL **20s**.
- Config `app-<profile>.json` trên classpath, profile từ env `APP_PROFILE` (mặc định `local`). Secret chỉ qua env: `PARTNER_SECRET_<CODE>`, `BACKOFFICE_TOKEN`, `DB_PASSWORD`.
- Log/thông báo lỗi tiếng Anh; không in nguyên object có dữ liệu khách; SĐT/mã KH che, giữ 3 ký tự cuối.
- Port: hub REST 8080, hub gRPC 9090, simulator 8090.
- Test cần hạ tầng (Postgres/RabbitMQ/Redis) gắn `@Tag("it")`, chạy bằng `mvn -Pit test` sau `docker compose up -d`; `mvn test` chỉ chạy unit test (surefire `excludedGroups=it`).
- Lệnh theo module: `mvn -pl <đường dẫn module> -am test` (vd `mvn -pl payment-hub -am test`). Chạy service: `mvn -q -pl <module> -am package -DskipTests && java -jar <module>/target/<artifactId>.jar`.

## Review Focus

1. **Hai request đồng thời cùng `partnerTxnId`** → chỉ 1 lần gọi connector; request kia nhận 409 `TXN_IN_PROGRESS` hoặc trạng thái đang lưu. Test: Task 6 `concurrentSameTxn_callsConnectorOnce`.
2. **Reply đến muộn / recheck ghi đè trạng thái cuối** → giao dịch đã SUCCESS/FAILED không bao giờ bị đổi lại. Test: Task 6 `updateResult_doesNotOverwriteFinalStatus`.
3. **Số tiền / SĐT bậy** (amount ≤ 0, số thập phân, chuỗi, thiếu ô, SĐT không phải 10 số) → 400, không tạo giao dịch. Test: Task 3 `topup_invalidFields_returns400`.
4. **Sai chữ ký vẫn "đốt" requestId** → request ký sai không được ghi vào kho chống replay; gửi lại đúng chữ ký vẫn qua. Test: Task 7 `badSignature_doesNotConsumeRequestId`.
5. **Connector chết giữa chừng, message redelivered** → connector ack & bỏ qua (không gọi supplier lần 2); Hub giữ UNKNOWN, job tra lại. Test: Task 4 `redeliveredMessage_isAckedAndSkipped`.

---

## File Structure

```
pom.xml (gốc: dependencyManagement, pluginManagement, profile it), .sdkmanrc
platform/pom.xml   (aggregator trung gian — chỉ platform có module con; connector/ là thư mục gom, không có pom)
<mỗi module>/pom.xml
docker-compose.yml
platform/platform-core/src/main/java/dev/gawio/platform/core/
  AppData.java  AppDataCodec.java  EventBuses.java  ErrorCode.java
  Task.java  Workflow.java  WorkflowVerticle.java
  ConfigLoader.java  VerticleDescriptor.java  MainVerticle.java  Launcher.java
  log/AppLog.java  log/Masker.java  log/JsonLogFormatter.java
platform/platform-messaging/src/main/java/dev/gawio/platform/messaging/
  TxnStatus.java  CommandType.java  ConnectorRequest.java  ConnectorReply.java  QueueNames.java
  AbstractRabbitClient.java  AbstractRabbitServer.java
payment-hub/src/main/java/dev/gawio/payhub/hub/
  HubApp.java  HubModule.java
  api/HttpServerVerticle.java  api/PaymentRoutes.java  api/HttpStatusMapper.java  api/ResponseMapper.java
  api/dto/TopupRequest.java  api/dto/BillPayRequest.java  api/dto/BillInquiryRequest.java
  grpc/BackOfficeGrpcVerticle.java
  flow/PaymentContext.java  flow/ConfirmPaymentVerticle.java  flow/InquiryVerticle.java
  flow/CheckStatusVerticle.java  flow/BackOfficeVerticle.java  flow/TxnQueryVerticle.java
  task/*.java   (mỗi Task 1 file)
  rabbit/RabbitClientVerticle.java  route/SupplierRouter.java
  data/Partner.java  data/PaymentTxn.java  data/PartnerRepository.java  data/PgPartnerRepository.java
  data/PaymentTxnRepository.java  data/PgPaymentTxnRepository.java  data/RedisLock.java  data/ReplayGuard.java
  security/HmacSigner.java  security/PartnerSecrets.java  security/PayloadHasher.java
  job/RecheckUnknownVerticle.java
payment-hub/src/main/proto/backoffice.proto
payment-hub/src/main/resources/ verticle.json  app-local.json  app-test.json  db/001_init.sql  db/002_seed.sql
connector/connector-topup/src/main/java/dev/gawio/connector/topup/
  TopupConnectorApp.java  TopupRabbitServer.java  SupAApiClient.java  SupACodeMapper.java
connector/connector-billing/src/main/java/dev/gawio/connector/billing/
  BillingConnectorApp.java  BillingRabbitServer.java  SupBApiClient.java  SupBCodeMapper.java
supplier-simulator/src/main/java/dev/gawio/simulator/
  SimulatorApp.java  SimulatorVerticle.java  ScenarioStore.java
```

Mã lỗi (`ErrorCode`, hằng `String`) và HTTP status (`HttpStatusMapper`):

| errorCode | HTTP | Ý nghĩa |
|---|---|---|
| `INVALID_REQUEST` | 400 | JSON hỏng / thiếu ô / sai định dạng |
| `UNSUPPORTED_PROVIDER` | 400 | không có route supplier cho `serviceType+providerCode` |
| `UNAUTHORIZED` | 401 | thiếu header, partner lạ, sai chữ ký, timestamp lệch > 5 phút |
| `FORBIDDEN` | 403 | partner SUSPENDED |
| `NOT_FOUND` | 404 | không có giao dịch |
| `DUPLICATE_REQUEST` | 409 | `X-Request-Id` dùng lại |
| `TXN_PAYLOAD_MISMATCH` | 409 | `partnerTxnId` trùng, payload khác |
| `TXN_IN_PROGRESS` | 409 | không lấy được khoá Redis |
| `SUPPLIER_TIMEOUT` | 200 (status UNKNOWN) | connector/supplier không trả lời kịp |
| `SUPPLIER_ERROR` | 200 (status UNKNOWN/FAILED theo connector) | supplier trả lỗi |
| `SYSTEM_ERROR` | 500 | lỗi nội bộ, workflow trả null |

Response REST chung: `{ "requestId", "status", "errorCode", "errorDesc", "data" }` (`status` = `txnStatus` hoặc `null` khi bị từ chối).

---

### Task 0: Khởi tạo Maven multi-module từ đầu, Java 25, docker-compose

Trước task này: tóm tắt kiến trúc 10 dòng + sequence diagram luồng topup (copy từ spec mục 5) để người dùng xác nhận. (Đã làm.)

**Files:**
Repo `ascend` chưa có code. Các file `.gitattributes`, `.gitignore` do `gradle init` sinh ra → thay nội dung cho Maven.

- Create: `pom.xml`, `.sdkmanrc`, `platform/pom.xml`, `platform/platform-core/pom.xml`, `platform/platform-messaging/pom.xml`, `payment-hub/pom.xml`, `supplier-simulator/pom.xml`, `connector/connector-topup/pom.xml`, `connector/connector-billing/pom.xml`, `docker-compose.yml`, `.env.example`, `platform/platform-core/src/main/java/dev/gawio/platform/core/package-info.java`
- Modify: `.gitignore` (`target/`, `.idea/`, `*.iml`, `.env`, `.claude/settings.local.json`), `.gitattributes` (bỏ dòng gradlew; giữ `*.jar binary`)

- [x] **Step 1:** `.sdkmanrc`: `java=25.0.2-tem`; `sdk env` → `java -version` báo 25, `mvn -v` báo Java 25.
- [x] **Step 2:** `pom.xml` gốc: `dev.gawio:ascend:0.1.0-SNAPSHOT`, `packaging=pom`, `<modules>`: `platform`, `payment-hub`, `connector/connector-topup`, `connector/connector-billing`, `supplier-simulator`.
  - `<properties>`: `maven.compiler.release=25`, `project.build.sourceEncoding=UTF-8`, `vertx.version=5.2.3`, `guice.version=7.0.0`, `lombok.version=1.18.48`, `caffeine.version=3.3.1`, `junit.version=5.14.4`, `excludedGroups=it`.
  - `<dependencyManagement>`: import BOM `io.vertx:vertx-stack-depchain:${vertx.version}` và `org.junit:junit-bom:${junit.version}` (`type=pom`, `scope=import`); `guice`, `caffeine`, `lombok` có version; `dev.gawio:platform-core` / `platform-messaging` `${project.version}`.
  - `<dependencies>` chung mọi module: `lombok` (`provided`), `junit-jupiter` + `vertx-junit5` (`test`).
  - `<pluginManagement>`: `maven-compiler-plugin` 3.16.0 (`annotationProcessorPaths` lombok), `maven-surefire-plugin` 3.6.1, `maven-shade-plugin` 3.6.2, `maven-enforcer-plugin` 3.7.0.
  - `<build><plugins>`: enforcer `requireJavaVersion [25,)` + `requireMavenVersion [3.9,)`.
  - `<profiles>`: profile `it` đặt `groups=it`, `excludedGroups=none`.
- [x] **Step 3:** pom trung gian duy nhất `platform/pom.xml`: parent `ascend`, `packaging=pom`, liệt kê `platform-core`, `platform-messaging` (2 module này có parent `platform`). `payment-hub`, `supplier-simulator` có parent `ascend` (relativePath mặc định `../pom.xml`); `connector/connector-*` có parent `ascend` với `<relativePath>../../pom.xml</relativePath>`.
- [x] **Step 4:** pom module lá.
  - `platform-core`: `vertx-core`, `guice`, `jackson-databind`.
  - `platform-messaging`: `platform-core`, `vertx-rabbitmq-client`, `caffeine`.
  - `payment-hub`: `platform-messaging`, `vertx-web`, `vertx-pg-client`, `vertx-redis-client` (gRPC thêm ở Task 9); shade → `payment-hub.jar`, `Main-Class: dev.gawio.payhub.hub.HubApp`.
  - `connector-topup` / `connector-billing`: `platform-messaging`, `vertx-web-client`; shade (`TopupConnectorApp` / `BillingConnectorApp`).
  - `supplier-simulator`: `platform-core`, `vertx-web`; shade (`SimulatorApp`).
  - Shade: `finalName` = artifactId, `ManifestResourceTransformer` (Main-Class) + `ServicesResourceTransformer` (Vert.x dùng ServiceLoader), `createDependencyReducedPom=false`.
- [x] **Step 5:** `docker-compose.yml`: `postgres:17` (`command: postgres -c wal_level=logical` để sẵn cho CDC, db `payhub`, user `payhub`, password `${DB_PASSWORD}`, mount `./payment-hub/src/main/resources/db:/docker-entrypoint-initdb.d:ro`, healthcheck `pg_isready -U payhub -d payhub`), `rabbitmq:4-management` (5672, 15672, healthcheck `rabbitmq-diagnostics -q ping`), `redis:7` (6379, healthcheck `redis-cli ping`). `mkdir -p` thư mục `db` trước. `.env.example` chứa `DB_PASSWORD=`, `PARTNER_SECRET_PARTNER_X=`, `BACKOFFICE_TOKEN=`.
- [x] **Step 6:** (bỏ qua — Maven dùng JDK của shell, không cần file để kích hoạt tải JDK) `package-info.java` trong `platform-core` để module có ít nhất 1 file biên dịch.
- [ ] **Step 7: Kiểm tra**
  Run: `mvn -q validate` → không lỗi; Reactor liệt kê đủ 8 pom (gốc, platform, platform-core, platform-messaging, payment-hub, connector-topup, connector-billing, supplier-simulator) khi chạy `mvn validate`.
  Run: `mvn verify` → `BUILD SUCCESS`; `ls payment-hub/target/payment-hub.jar` có file.
  Run: `sdk use java 21.0.3-tem && mvn validate` → enforcer FAIL ("Detected JDK ... is not in the allowed range [25,)"); đổi lại `sdk env`.
  Run: `mvn -q -pl payment-hub dependency:tree -am | grep vertx-core` → `io.vertx:vertx-core:jar:5.2.3`.
  Run: `docker compose up -d && docker compose ps` → 3 container `healthy`; mở http://localhost:15672 (guest/guest).
- [ ] **Step 8: Commit** `git add -A && git commit -m "build: maven multi-module, java 25, docker-compose"`

---

### Task 1: Launcher + MainVerticle + verticle.json + config profile + Guice

Activity diagram: Launcher đọc profile → load config → tạo Injector → deploy MainVerticle → đọc `verticle.json` → deploy tuần tự (isDeploy=false thì bỏ qua) → 1 cái hỏng thì fail cả app.

**Files:**
- Create: `platform/platform-core/.../ConfigLoader.java`, `VerticleDescriptor.java`, `MainVerticle.java`, `Launcher.java`
- Test: `platform/platform-core/src/test/java/dev/gawio/platform/core/MainVerticleTest.java`, `ConfigLoaderTest.java`, test fixtures `HelloVerticle`, `BrokenVerticle`, `src/test/resources/app-test.json`

**Interfaces:**
- Produces:
  - `ConfigLoader.load(Vertx vertx, String profile): Future<JsonObject>` — đọc `app-<profile>.json` từ classpath (executeBlocking hoặc `vertx.fileSystem()`); không có file → Future fail `"Config not found: app-<profile>.json"`.
  - `record VerticleDescriptor(String name, String address, int instances, boolean isDeploy)`; `static List<VerticleDescriptor> parse(JsonArray)`. `address` = tên class đầy đủ.
  - `MainVerticle(Injector injector, List<VerticleDescriptor> verticles) extends VerticleBase` — `start()` deploy tuần tự bằng `vertx.deployVerticle(() -> (Deployable) injector.getInstance(clazz), new DeploymentOptions().setInstances(n).setConfig(config()))`, log `"deploy {name} success"`; fail → trả Future fail (không deploy tiếp).
  - Log dùng `System.Logger` của JDK (không thêm thư viện).
  - `Launcher.launch(String[] args, Function<JsonObject, List<Module>> modules): void` — gọi `run`; thành công → thêm shutdown hook `vertx.close().await()`; thất bại → log + `System.exit(1)`.
  - `Launcher.run(String[] args, Function<JsonObject, List<Module>> modules): Future<Vertx>` — profile từ env `APP_PROFILE` mặc định `local`; đăng ký `AppDataCodec` (Task 2 thêm vào); bind `Vertx` + `JsonObject` config (`@Named("config")`) vào Guice; đọc `verticle.json` từ classpath; deploy fail → log + `vertx.close()` + `System.exit(1)` (chỉ ở `main`, không trong `run`). Thêm shutdown hook gọi `vertx.close()`.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void deploysOnlyEnabledVerticles(Vertx vertx, VertxTestContext ctx) {
  var list = List.of(new VerticleDescriptor("hello", HelloVerticle.class.getName(), 1, true),
                     new VerticleDescriptor("skip", BrokenVerticle.class.getName(), 1, false));
  vertx.deployVerticle(new MainVerticle(Guice.createInjector(), list))
    .onComplete(ctx.succeeding(id -> ctx.verify(() -> {
      assertEquals(1, HelloVerticle.STARTED.get());   // static AtomicInteger trong fixture
      ctx.completeNow(); })));
}
@Test void failsFastWhenAVerticleFails(...)  // [Broken(isDeploy=true), Hello] → deploy fail, HelloVerticle.STARTED == 0
@Test void runsOneObjectPerInstance(...)      // Hello instances=3 → STARTED == 3
@Test void loadsProfileConfig(...)            // ConfigLoader.load(vertx,"test") → json.getString("name") == "payhub-test"
@Test void missingProfileFails(...)           // load(vertx,"nope") → failed, message chứa "app-nope.json"
```

- [ ] **Step 2:** `mvn -pl platform/platform-core test` → FAIL (class chưa có).
- [ ] **Step 3:** Cài đặt các class theo Interfaces.
- [ ] **Step 4:** `mvn -pl platform/platform-core test` → PASS 5 test.
- [ ] **Step 5: Commit** `feat(core): launcher, MainVerticle, verticle.json, profile config`

---

### Task 2: AppData + Codec + Task + Workflow

Activity diagram cho `Workflow.runOne(step)`; sequence diagram A→B(fail)→done (C không chạy).

**Files:**
- Create: `platform/platform-core/.../AppData.java`, `AppDataCodec.java`, `EventBuses.java`, `ErrorCode.java`, `Task.java`, `Workflow.java`
- Modify: `Launcher.java` (đăng ký codec)
- Test: `platform/platform-core/src/test/java/dev/gawio/platform/core/WorkflowTest.java`, `EventBusesTest.java`

**Interfaces:**
- Produces:
  - `@Getter @Setter class AppData { String requestId; String type; boolean result = true; String errorCode; String errorDesc; boolean breakFlow; Object request; Object response; Map<String,Object> extra = new HashMap<>(); void fail(String code, String desc) }` — `fail` set `result=false` + code + desc.
  - `AppDataCodec implements MessageCodec<AppData, AppData>` — `name() = "app-data"`, `transform` trả chính object, `encodeToWire/decodeFromWire` ném `UnsupportedOperationException("local only")`. Đăng ký bằng `eventBus.registerCodec` (không dùng default codec vì lớp con không khớp).
  - `EventBuses.request(EventBus eb, String address, AppData data, long timeoutMs): Future<AppData>` — dùng `DeliveryOptions().setCodecName("app-data").setSendTimeout(timeoutMs)`; `EventBuses.OPTIONS` cho `msg.reply`.
  - `abstract class Task { void run(AppData in, Handler<AppData> done); protected abstract void exec(AppData in, Handler<AppData> done); }`
  - `class Workflow extends Task { Workflow(List<Task> tasks); static Workflow of(Task... t); boolean stopWhenFail = true; boolean enableBreakFlow = false; }` — `runOne(step)` đúng công thức trong spec mục 4.
  - `ErrorCode`: các hằng trong bảng mã lỗi ở đầu plan.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void stopsAfterFailedTask(VertxTestContext ctx) {
  var ran = new ArrayList<String>();
  Task a = task(ran, "A", d -> {}); Task b = task(ran, "B", d -> d.fail("X", "boom")); Task c = task(ran, "C", d -> {});
  Workflow.of(a, b, c).run(new AppData(), out -> ctx.verify(() -> {
    assertEquals(List.of("A", "B"), ran); assertFalse(out.isResult()); assertEquals("X", out.getErrorCode());
    ctx.completeNow(); }));
}
@Test void runsAllWhenStopWhenFailFalse(...)  // stopWhenFail=false → ran == [A,B,C]
@Test void breakFlowStopsOnlyWhenEnabled(...)  // B set breakFlow=true: enableBreakFlow=true → [A,B]; false → [A,B,C]; result vẫn true
@Test void emptyWorkflowCallsDoneOnce(...)     // Workflow.of() → done gọi 1 lần
@Test void taskForgettingDoneTimesOut(...)     // Task không gọi done → ctx.awaitCompletion(500ms) == false; ghi chú: đây là lý do luật "luôn gọi done"
@Test void codecPassesSameReference(Vertx vertx, VertxTestContext ctx) // consumer nhận object == object gửi (assertSame)
```

- [ ] **Step 2:** `mvn -pl platform/platform-core test -Dtest='WorkflowTest,EventBusesTest'` → FAIL.
- [ ] **Step 3:** Cài đặt theo Interfaces.
- [ ] **Step 4:** chạy lại → PASS 6 test.
- [ ] **Step 5: Commit** `feat(core): AppData, local codec, Task/Workflow`

---

### Task 3: REST `/v1/topup` + WorkflowVerticle + workflow giả

Sequence diagram: curl → `PaymentRoutes` → `EventBuses.request(ConfirmPaymentVerticle, 15s)` → workflow giả → reply → JSON.

**Files:**
- Create: `platform/platform-core/.../WorkflowVerticle.java`
- Create (messaging): `TxnStatus.java`, `ServiceType.java`
- Create (hub): `HubApp.java`, `HubModule.java`, `api/HttpServerVerticle.java`, `api/PaymentRoutes.java`, `api/HttpStatusMapper.java`, `api/ResponseMapper.java`, `api/dto/TopupRequest.java`, `flow/PaymentContext.java`, `flow/ConfirmPaymentVerticle.java`, `task/FakeSupplierTask.java` (xoá ở Task 4), `resources/verticle.json`, `app-local.json`, `app-test.json`
- Test: `payment-hub/src/test/java/dev/gawio/payhub/hub/api/TopupRouteTest.java`

**Interfaces:**
- Consumes: `AppData`, `Workflow`, `EventBuses`, `ErrorCode` (Task 2); `Launcher.run` (Task 1).
- Produces:
  - `abstract class WorkflowVerticle extends VerticleBase` — `start()`: gọi `buildWorkflows()` 1 lần, consumer tại `getClass().getName()`; mỗi message: `workflowFor(data).run(data, out -> msg.reply(out == null ? systemError(data) : out, EventBuses.OPTIONS))`. Abstract: `protected abstract void buildWorkflows()`, `protected abstract Workflow workflowFor(AppData data)`. Exception khi chạy → reply `SYSTEM_ERROR`.
  - `@Getter @Setter class PaymentContext extends AppData { String partnerCode; String partnerTxnId; ServiceType serviceType /*TOPUP|BILLING*/; String providerCode; String customerRef; long amount; String supplierCode; String queue; TxnStatus txnStatus; String supplierTxnId; String rawBody; Long txnId; }` (`ServiceType` enum đặt trong `platform-messaging`, Task 3 tạo luôn cùng `TxnStatus {INIT, PROCESSING, SUCCESS, FAILED, UNKNOWN}`).
  - `record TopupRequest(String partnerTxnId, String providerCode, String phone, Long amount)` + `List<String> validate()` — lỗi nếu: thiếu ô, `amount == null || amount <= 0`, `phone` không khớp `^0\d{9}$`, `partnerTxnId` dài > 64.
  - `PaymentRoutes.topup(RoutingContext rc)` — parse body bằng `Json.decodeValue(body, TopupRequest.class)`; `DecodeException` hoặc `validate()` có lỗi → 400 `INVALID_REQUEST` và `return`; dựng `PaymentContext` (type `CONFIRM`, serviceType `TOPUP`, `customerRef = phone`, `requestId` từ header `X-Request-Id` hoặc UUID); request tới `ConfirmPaymentVerticle` timeout `config.hub.routeTimeoutMs` (15000); event bus timeout → 200 `status=UNKNOWN`, `errorCode=SUPPLIER_TIMEOUT`.
  - `ResponseMapper.toJson(AppData): JsonObject`; `HttpStatusMapper.status(String errorCode): int` theo bảng.
  - `HttpServerVerticle` — port `config.http.port`; route `POST /v1/topup` với `BodyHandler.create().setBodyLimit(64 * 1024)`.
  - `FakeSupplierTask` — set `txnStatus=SUCCESS`, `supplierTxnId="FAKE-"+partnerTxnId`, `response={"supplierTxnId":...}`.

- [ ] **Step 1: Viết test (fail)** — deploy `HttpServerVerticle` + `ConfirmPaymentVerticle` trên port ngẫu nhiên (config test), dùng `WebClient`.

```java
@Test void topup_valid_returns200() // body {"partnerTxnId":"T1","providerCode":"VIETTEL","phone":"0912345678","amount":10000}
  // → 200, json.status == "SUCCESS", json.data.supplierTxnId == "FAKE-T1", json.requestId không rỗng
@Test void topup_brokenJson_returns400()     // body "{oops" → 400, errorCode "INVALID_REQUEST"
@ParameterizedTest @ValueSource(strings = {
  "{\"partnerTxnId\":\"T1\",\"providerCode\":\"VIETTEL\",\"phone\":\"0912345678\",\"amount\":0}",
  "{\"partnerTxnId\":\"T1\",\"providerCode\":\"VIETTEL\",\"phone\":\"0912345678\",\"amount\":-5}",
  "{\"partnerTxnId\":\"T1\",\"providerCode\":\"VIETTEL\",\"phone\":\"0912345678\",\"amount\":10.5}",
  "{\"partnerTxnId\":\"T1\",\"providerCode\":\"VIETTEL\",\"phone\":\"12345\",\"amount\":10000}",
  "{\"providerCode\":\"VIETTEL\",\"phone\":\"0912345678\",\"amount\":10000}" })
void topup_invalidFields_returns400(String body)  // → 400 INVALID_REQUEST
@Test void workflowReturningNull_repliesSystemError() // WorkflowVerticle test con có workflow gọi done(null) → errorCode SYSTEM_ERROR
```
  Ghi chú: `10.5` vào `Long` — cấu hình `DatabindCodec.mapper()` với `DeserializationFeature.ACCEPT_FLOAT_AS_INT` tắt để ra lỗi.

- [ ] **Step 2:** `mvn -pl payment-hub -am test` → FAIL.
- [ ] **Step 3:** Cài đặt; `verticle.json` liệt kê `HttpServerVerticle`, `ConfirmPaymentVerticle`.
- [ ] **Step 4:** `mvn -pl payment-hub -am test` → PASS. Thủ công: `mvn -q -pl payment-hub -am package -DskipTests && java -jar payment-hub/target/payment-hub.jar` rồi
  `curl -s -XPOST localhost:8080/v1/topup -d '{"partnerTxnId":"T1","providerCode":"VIETTEL","phone":"0912345678","amount":10000}'` → `"status":"SUCCESS"`; `curl ... -d '{oops'` → HTTP 400.
- [ ] **Step 5: Commit** `feat(hub): topup REST route, WorkflowVerticle, fake workflow`

---

### Task 4: RabbitMQ RPC — RabbitClientVerticle (hub) + connector-topup (dữ liệu cứng)

Sequence diagram Direct Reply-to (publish với `correlationId` + `replyTo=amq.rabbitmq.reply-to` → connector → reply → cache lookup → `message.reply`), và nhánh timeout (cache hết hạn, Task nhận timeout → UNKNOWN).

**Files:**
- Create (messaging): `CommandType.java` (`INQUIRY, CONFIRM, CHECK_STATUS`), `ConnectorRequest.java`, `ConnectorReply.java`, `QueueNames.java`, `AbstractRabbitClient.java`, `AbstractRabbitServer.java`
- Create (hub): `rabbit/RabbitClientVerticle.java`, `route/SupplierRouter.java`, `task/RouteSupplierTask.java`, `task/CallConnectorTask.java`
- Delete: `payment-hub/.../task/FakeSupplierTask.java`
- Create (connector-topup): `TopupConnectorApp.java`, `TopupRabbitServer.java`, `resources/verticle.json`, `app-local.json`
- Test: `platform/platform-messaging/src/test/java/.../QueueNamesTest.java`, `RabbitRpcIT.java` (`@Tag("it")`), `payment-hub/src/test/.../SupplierRouterTest.java`

**Interfaces:**
- Consumes: `PaymentContext`, `TxnStatus`, `ServiceType` (Task 3); `EventBuses` (Task 2).
- Produces:
  - `record ConnectorRequest(String requestId, CommandType type, ServiceType serviceType, String supplierCode, String partnerTxnId, String providerCode, String customerRef, long amount, String supplierTxnId)`
  - `record ConnectorReply(String requestId, TxnStatus status, String supplierTxnId, String errorCode, String errorDesc, JsonObject data)` — encode/decode bằng `Json.encode` / `Json.decodeValue`.
  - `QueueNames.of(ServiceType st): String` → `"payment." + st.name().toLowerCase()`; `QueueNames.of(TOPUP) == "payment.topup"`.
  - `abstract class AbstractRabbitClient extends VerticleBase` — tạo `RabbitMQClient` từ `config.rabbit`; `start()`: `client.start()` → `basicConsumer("amq.rabbitmq.reply-to", new QueueOptions().setAutoAck(true))` → consumer EventBus tại `getClass().getName()`. Cache `Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(config.rabbit.requestTimeoutMs)).<String, Message<AppData>>build()`. Mỗi message EventBus: `correlationId = UUID`, `cache.put`, `basicPublish("", resolveQueue(data), props{correlationId, replyTo, headers(data)}, Json body)`; log `"publish queue={} correlationId={} requestId={}"`; publish lỗi → `cache.invalidate` + reply `SUPPLIER_ERROR`/UNKNOWN. Reply: `cache.getIfPresent(cid)` → null thì log `"late reply dropped"` và bỏ; có thì `invalidate` → `applyReply(data, reply)` → `msg.reply(data, EventBuses.OPTIONS)`. Abstract: `String resolveQueue(AppData)`, `ConnectorRequest toRequest(AppData)`, `void applyReply(AppData, ConnectorReply)`. Hook `protected Map<String,Object> headers(AppData d)` mặc định `{"x-request-id": d.getRequestId()}` (platform không biết `PaymentContext`).
    ⚠️ Direct Reply-to yêu cầu consume `amq.rabbitmq.reply-to` (auto-ack) **trước** và publish **cùng channel** → dùng chung 1 `RabbitMQClient` instance cho cả consume reply và publish.
  - `abstract class AbstractRabbitServer extends VerticleBase` — `queueDeclare(queueName(), durable=true, exclusive=false, autoDelete=false)`, `basicQos(config.rabbit.prefetch /*10*/)`, `basicConsumer(queue, autoAck=false)`. Mỗi message: `isRedeliver()` → `basicAck` + log warn `"redelivered message skipped"` + dừng. Ngược lại decode → switch `type`: `inquiry/confirm/checkStatus(ConnectorRequest): Future<ConnectorReply>` (mặc định: reply `errorCode="UNSUPPORTED_COMMAND"`, `status=null` — không bao giờ tự trả FAILED) → publish tới `properties.replyTo` với cùng `correlationId` → `basicAck`. Handler fail → reply `status=UNKNOWN`, `errorCode=SUPPLIER_ERROR` rồi ack. Abstract `String queueName()`. `stop()`: `consumer.cancel()` (graceful drain thêm ở Task 8).
  - `SupplierRouter(JsonObject routes)` từ `config.routes` dạng `{"TOPUP":{"VIETTEL":"SUP_A","MOBIFONE":"SUP_A"}}`; `Optional<String> supplierFor(ServiceType, String providerCode)`.
  - `RouteSupplierTask` — không có route → `fail(UNSUPPORTED_PROVIDER, ...)`; có → set `supplierCode`, `queue = QueueNames.of(serviceType)`.
  - `CallConnectorTask(CommandType type)` — `EventBuses.request(eb, RabbitClientVerticle.class.getName(), ctx, config.hub.connectorTimeoutMs /*12000*/)`; thành công → dùng ctx trả về; timeout/lỗi → **giữ `result=true`**, `txnStatus=UNKNOWN`, `errorCode=SUPPLIER_TIMEOUT`.
  - `RabbitClientVerticle extends AbstractRabbitClient` — `resolveQueue` = `ctx.getQueue()`; override `headers` thêm `"x-partner-code": ctx.getPartnerCode()`; `applyReply` set `txnStatus`, `supplierTxnId`, `errorCode`, `response=reply.data()`.
  - `TopupRabbitServer extends AbstractRabbitServer` — queue `QueueNames.of(TOPUP)` (`payment.topup`); `confirm` → `SUCCESS`, `supplierTxnId="SUPA-"+partnerTxnId`; `checkStatus` → `SUCCESS`.
  - Workflow `ConfirmPaymentVerticle` giờ là `RouteSupplierTask → CallConnectorTask(CONFIRM)`.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void queueName() { assertEquals("payment.topup", QueueNames.of(TOPUP)); assertEquals("payment.billing", QueueNames.of(BILLING)); }
@Test void routerUnknownProviderIsEmpty() // supplierFor(TOPUP,"UNKNOWN_TELCO").isEmpty()
// RabbitRpcIT (@Tag("it")): deploy test client (extends AbstractRabbitClient) + echo server trên queue "payment.it_echo"
@Test void roundTrip_repliesWithSameCorrelation()  // request → reply.status == SUCCESS, supplierTxnId == "ECHO-T1"
@Test void noConsumer_timesOutAndCacheEmpties()    // publish tới queue không có server, request timeout 1000ms → failed ReplyException TIMEOUT; sau 1500ms cache.estimatedSize()==0 (cleanUp() trước khi đo)
@Test void redeliveredMessage_isAckedAndSkipped()  // server handler giả lập crash trước ack lần 1 (nack requeue=true), lần 2 isRedeliver → handler đếm == 1, queue rỗng (messageCount == 0)
@Test void lateReply_isDropped()                   // server trả sau 1500ms, client timeout 1000ms → không exception, log "late reply dropped"
```

- [ ] **Step 2:** `mvn test` + `docker compose up -d && mvn -Pit -pl platform/platform-messaging -am test` → FAIL.
- [ ] **Step 3:** Cài đặt theo Interfaces; `app-local.json` hub thêm `rabbit{host,port,user,password(env RABBIT_PASSWORD hoặc guest local),requestTimeoutMs:12000}`, `routes`.
- [ ] **Step 4: Kiểm tra**
  `mvn test` và `mvn -Pit -pl platform/platform-messaging -am test` → PASS.
  Đóng gói rồi `java -jar connector/connector-topup/target/connector-topup.jar` và `java -jar payment-hub/target/payment-hub.jar`; curl topup như Task 3 → `"status":"SUCCESS","data"...` với `supplierTxnId` bắt đầu `SUPA-`. Log hub có `publish queue=payment.topup`.
  UI 15672 → Queues → `payment.topup` có **1 consumer**.
  Tắt connector → curl trả trong ~12s: `status=UNKNOWN`, `errorCode=SUPPLIER_TIMEOUT` (không treo). Bật lại → curl SUCCESS.
  `providerCode":"ABC"` → 400 `UNSUPPORTED_PROVIDER`.
- [ ] **Step 5: Commit** `feat: rabbitmq RPC with direct reply-to, connector-topup`

---

### Task 5: supplier-simulator + WebClient + bảng ánh xạ mã + connector-billing (BILLING)

State machine: mã supplier → TxnStatus. Sequence: connector → simulator (chậm) → timeout 10s → UNKNOWN.

**Files:**
- Create (simulator): `SimulatorApp.java`, `SimulatorVerticle.java`, `ScenarioStore.java`
- Create (core): `log/Masker.java` — `Masker.mask(String s): String` giữ 3 ký tự cuối, còn lại `*`; null → null; độ dài ≤ 3 → `***`
- Create (connector-topup): `SupAApiClient.java`, `SupACodeMapper.java`; Modify `TopupRabbitServer.java`
- Create (connector-billing): `BillingConnectorApp.java`, `BillingRabbitServer.java`, `SupBApiClient.java`, `SupBCodeMapper.java`, resources
- Create (hub): `api/dto/BillPayRequest.java`, `api/dto/BillInquiryRequest.java`, `flow/InquiryVerticle.java`; Modify `PaymentRoutes.java`, `HttpServerVerticle.java`, `verticle.json`, `app-local.json` (routes BILLING)
- Test: `SupACodeMapperTest`, `SupBCodeMapperTest`, `SimulatorVerticleTest`, `SupAApiClientTest` (dựng 1 `HttpServer` stub nhỏ ngay trong test của connector, dùng `vertx.setTimer` để trễ — **không** import `SimulatorVerticle`, vì `connector/*` không được phụ thuộc `payment-hub/*`), hub `BillingRouteTest`

**Interfaces:**
- Simulator (port 8090), state trong `ScenarioStore` (in-memory):
  - `POST /sup-a/topup` `{ref, phone, amount}` → `{code, supplierTxnId}`; `GET /sup-a/transactions/{ref}` → `{code}`
  - `POST /sup-b/bills/inquiry` `{customerRef, providerCode}` → `{code, amount, customerName}`; `POST /sup-b/bills/pay` `{ref, customerRef, amount}` → `{code, supplierTxnId}`; `GET /sup-b/transactions/{ref}` → `{code}`
  - `PUT /_control/scenario/{supplier}` `{delayMs, code}` — áp cho request tiếp theo của supplier đó (mặc định delay 0, code thành công). Delay dùng `vertx.setTimer`, **không** sleep.
  - `PUT /_control/transactions/{ref}` `{code}` — đổi kết quả check-status của 1 giao dịch.
  - Mặc định: SUP_A trả `"00"`, SUP_B trả `"SUCCESS"`; check-status trả code đã lưu khi pay.
- `SupACodeMapper.map(String code): TxnStatus` — `"00"→SUCCESS`, `"01"`,`"05"→FAILED`, `"99"→UNKNOWN`, khác/null → UNKNOWN.
- `SupBCodeMapper.map(String code): TxnStatus` — `"SUCCESS"→SUCCESS`, `"FAILED"`,`"BILL_NOT_FOUND"→FAILED`, `"PENDING"→UNKNOWN`, khác/null → UNKNOWN.
- `SupAApiClient(WebClient, String baseUrl, long timeoutMs /*10000*/)`: `Future<JsonObject> topup(String ref, String phone, long amount)`, `Future<JsonObject> status(String ref)`. Tương tự `SupBApiClient`: `inquiry`, `pay`, `status`.
- Connector: lỗi WebClient (timeout, connection refused) → `ConnectorReply(status=UNKNOWN, errorCode=SUPPLIER_TIMEOUT)`; FAILED từ mapper → `errorCode=SUPPLIER_ERROR`, `errorDesc=code`.
- `BillingRabbitServer` queue `payment.billing`; INQUIRY trả `data{amount, customerName}`.
- Hub: `POST /v1/billing/inquiry` `BillInquiryRequest(String providerCode, String customerRef)` → `InquiryVerticle` (RouteSupplierTask → CallConnectorTask(INQUIRY)), không lưu DB; `data.customerName` che bằng `Masker.mask` (tạo ở task này, xem dưới). `POST /v1/billing/pay` `BillPayRequest(String partnerTxnId, String providerCode, String customerRef, Long amount)` → `ConfirmPaymentVerticle` serviceType BILLING. Route config: `"BILLING":{"EVN":"SUP_B","SAWACO":"SUP_B"}`.

- [ ] **Step 1: Viết test (fail)**

```java
@ParameterizedTest @CsvSource({"00,SUCCESS","01,FAILED","05,FAILED","99,UNKNOWN","XYZ,UNKNOWN"})
void supAMap(String code, TxnStatus expected) { assertEquals(expected, SupACodeMapper.map(code)); }
@Test void supAMapNull() { assertEquals(UNKNOWN, SupACodeMapper.map(null)); }
@ParameterizedTest @CsvSource({"SUCCESS,SUCCESS","FAILED,FAILED","BILL_NOT_FOUND,FAILED","PENDING,UNKNOWN","??,UNKNOWN"}) void supBMap(...)
@Test void simulatorDelayDoesNotBlock()   // scenario delay 500ms; gửi 2 request song song → tổng < 900ms
@Test void slowSupplier_givesUnknown()   // SupAApiClient timeout 300ms, scenario delay 1000ms → TopupRabbitServer.confirm(...) trả status UNKNOWN, errorCode SUPPLIER_TIMEOUT
@Test void supplierFailedCode_givesFailed() // scenario code "01" → status FAILED, errorCode SUPPLIER_ERROR
@Test void maskPhone() { assertEquals("*******678", Masker.mask("0912345678")); assertEquals("***", Masker.mask("12")); }
```

- [ ] **Step 2:** chạy test các module → FAIL.
- [ ] **Step 3:** Cài đặt.
- [ ] **Step 4: Kiểm tra**
  Unit test PASS. Chạy simulator + 2 connector + hub.
  `curl -XPUT localhost:8090/_control/scenario/SUP_A -d '{"delayMs":11000,"code":"00"}'` rồi curl topup → `status=UNKNOWN` (**không** FAILED).
  `... -d '{"delayMs":0,"code":"01"}'` → `status=FAILED`.
  `curl -XPOST localhost:8080/v1/billing/inquiry -d '{"providerCode":"EVN","customerRef":"PE0123456789"}'` → `data.amount`, `customerName` đã che; log hub `publish queue=payment.billing`.
- [ ] **Step 5: Commit** `feat: supplier simulator, code mapping, connector-billing`

---

### Task 6: Postgres DAO + lưu giao dịch + idempotency + khoá Redis

State machine giao dịch (spec 5.6) + activity diagram TopupWorkflow (spec 5).

**Files:**
- Create: `payment-hub/src/main/resources/db/001_init.sql`, `002_seed.sql`
- Create (hub): `data/Partner.java`, `data/PaymentTxn.java`, `data/PartnerRepository.java`, `data/PgPartnerRepository.java`, `data/PaymentTxnRepository.java`, `data/PgPaymentTxnRepository.java`, `data/RedisLock.java`, `security/PayloadHasher.java`, `task/IdempotencyTask.java`, `task/LockTask.java`, `task/SaveTxnTask.java`, `task/UpdateTxnTask.java`, `flow/TxnQueryVerticle.java`
- Modify: `HubModule.java` (bind `Pool`, `Redis`, repositories), `ConfirmPaymentVerticle.java`, `PaymentRoutes.java` (`GET /v1/transactions/:partnerTxnId`)
- Test: `PgPaymentTxnRepositoryIT`, `RedisLockIT`, `PayloadHasherTest`, `ConfirmPaymentFlowIT`

**Interfaces:**
- SQL (`001_init.sql`): bảng `partner`, `payment_txn` đúng spec 5.7, thêm index `payment_txn(status, updated_at)`. `002_seed.sql`: partner `PARTNER_X` ACTIVE, `PARTNER_Y` SUSPENDED. (Init chỉ chạy khi volume trống → hướng dẫn `docker compose down -v` khi sửa SQL.)
- `record PaymentTxn(Long id, String partnerCode, String partnerTxnId, ServiceType serviceType, String providerCode, String supplierCode, long amount, String customerRef, String payloadHash, TxnStatus status, String supplierTxnId, String errorCode, String errorDesc, OffsetDateTime createdAt, OffsetDateTime updatedAt)`
- `record Partner(String partnerCode, String name, String status)`
- `interface PaymentTxnRepository`:
  - `Future<Optional<PaymentTxn>> find(String partnerCode, String partnerTxnId)`
  - `Future<Optional<Long>> insertProcessing(PaymentTxn t)` — `INSERT ... ON CONFLICT (partner_code, partner_txn_id) DO NOTHING RETURNING id`; trùng → `Optional.empty()`
  - `Future<Boolean> updateResult(long id, TxnStatus s, String supplierTxnId, String errorCode, String errorDesc)` — `UPDATE ... WHERE id=$1 AND status IN ('PROCESSING','UNKNOWN')`; trả `rowCount == 1`
  - `Future<List<PaymentTxn>> findByStatus(TxnStatus s, Duration olderThan, int limit)` (dùng ở Task 8/9)
- `interface PartnerRepository`: `Future<Optional<Partner>> find(String code)`, `Future<Boolean> updateStatus(String code, String status)`.
- `PayloadHasher.hash(ServiceType st, String providerCode, String customerRef, long amount): String` — SHA-256 hex của `st|providerCode|customerRef|amount`.
- `RedisLock`: `Future<Optional<String>> tryLock(String key, Duration ttl)` — `SET key token NX PX ttl`, trả token; `Future<Void> unlock(String key, String token)` — Lua:
  ```lua
  if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
  ```
  Key `lock:txn:<partnerCode>:<partnerTxnId>`, TTL 20s.
- Tasks:
  - `IdempotencyTask` — `find` → có: hash trùng → nạp `txnStatus/supplierTxnId/txnId` vào ctx + `breakFlow=true`; hash khác → `fail(TXN_PAYLOAD_MISMATCH)`. Không có → đi tiếp.
  - `LockTask` — không lấy được → `fail(TXN_IN_PROGRESS)`; lấy được → `extra.put("lockToken", token)`.
  - `SaveTxnTask` — `insertProcessing`; empty (thua race) → unlock, đọc lại, xử lý như IdempotencyTask (breakFlow / mismatch).
  - `UpdateTxnTask` — `updateResult` rồi `unlock` (luôn unlock, kể cả update lỗi; update lỗi → log error, giữ `txnStatus` đang có).
  - `ConfirmPaymentVerticle` workflow (`enableBreakFlow=true`): `IdempotencyTask → RouteSupplierTask → LockTask → SaveTxnTask → CallConnectorTask(CONFIRM) → UpdateTxnTask`.
  - `TxnQueryVerticle` (`GET /v1/transactions/:partnerTxnId`, partner tạm lấy từ header `X-Partner-Code`) → không có → `NOT_FOUND`.
  - Tạm thời (đến Task 7) route lấy `partnerCode` từ header `X-Partner-Code`, thiếu → 400.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void hashStable() // hash(TOPUP,"VIETTEL","0912345678",10000) == hash(...same) và != hash(... 20000)
// PgPaymentTxnRepositoryIT
@Test void insertDuplicate_returnsEmpty()
@Test void updateResult_doesNotOverwriteFinalStatus() // insert → update SUCCESS (true) → update UNKNOWN (false) → find().status == SUCCESS
// RedisLockIT
@Test void secondLockFails_unlockWithWrongTokenKeepsLock()
// ConfirmPaymentFlowIT: deploy hub verticles + fake consumer tại RabbitClientVerticle address đếm số lần gọi, trả SUCCESS
@Test void sameTxnSamePayload_callsConnectorOnce()      // 2 lần tuần tự → count == 1, lần 2 status SUCCESS
@Test void sameTxnDifferentAmount_returns409()          // errorCode TXN_PAYLOAD_MISMATCH, HTTP 409
@Test void concurrentSameTxn_callsConnectorOnce()       // fake consumer trả sau 300ms; 5 request song song → count == 1; mọi response là SUCCESS hoặc 409 TXN_IN_PROGRESS
@Test void connectorTimeout_persistsUnknown()           // fake consumer không trả → DB status UNKNOWN
```

- [ ] **Step 2:** `mvn -pl payment-hub -am test` và `mvn -Pit -pl payment-hub -am test` → FAIL.
- [ ] **Step 3:** Cài đặt.
- [ ] **Step 4: Kiểm tra** — test PASS. Thủ công: curl topup 2 lần cùng `partnerTxnId` → log connector chỉ 1 lần nhận; đổi `amount` → 409. `docker compose exec postgres psql -U payhub -c "select partner_txn_id,status from payment_txn"`.
- [ ] **Step 5: Commit** `feat(hub): persist transactions, idempotency, redis lock`

---

### Task 7: Task xác thực đầu workflow (partner, HMAC, timestamp, chống replay)

Activity diagram ValidateRequestTask (thứ tự kiểm tra) + sequence partner ký request.

**Files:**
- Create (hub): `security/HmacSigner.java`, `security/PartnerSecrets.java`, `data/ReplayGuard.java`, `task/ValidateRequestTask.java`
- Modify: `PaymentRoutes.java` (giữ `rawBody`, đọc 4 header), `ConfirmPaymentVerticle`, `InquiryVerticle`, `TxnQueryVerticle` (thêm ValidateRequestTask đầu workflow)
- Create: `scripts/sign.sh` (in header cho curl bằng `openssl dgst -sha256 -hmac`)
- Test: `HmacSignerTest`, `ValidateRequestTaskTest`

**Interfaces:**
- `HmacSigner.sign(String secret, String requestId, String timestamp, String body): String` — HMAC-SHA256 trên `requestId|timestamp|body`, hex thường. `HmacSigner.verify(...)` so sánh bằng `MessageDigest.isEqual`.
- `PartnerSecrets(Function<String,String> env)` — `Optional<String> secretOf(String partnerCode)` đọc `PARTNER_SECRET_<CODE>`; production bind `System::getenv`.
- `ReplayGuard`: `Future<Boolean> markIfNew(String partnerCode, String requestId)` — `SET replay:<partner>:<requestId> 1 NX EX 600`.
- `ValidateRequestTask(PartnerRepository, PartnerSecrets, ReplayGuard, Clock)` — thứ tự: thiếu header → `UNAUTHORIZED`; partner không có → `UNAUTHORIZED`; `SUSPENDED` → `FORBIDDEN`; `|now - timestamp| > 5 phút` (epoch millis) → `UNAUTHORIZED`; chữ ký sai → `UNAUTHORIZED`; **sau cùng** `markIfNew` false → `DUPLICATE_REQUEST`.
- GET không có body → ký với body `""`.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void knownVector() {
  assertEquals("9f352e275cee23ee5207ce5b5924733851c080843f2b9c5c359b48bd30b451c1",
    HmacSigner.sign("test-secret", "req-1", "1700000000000", "{\"amount\":10000}"));
}
// ValidateRequestTaskTest: repository/replay giả (in-memory), Clock.fixed(1700000000000)
@Test void validRequest_passes()
@Test void badSignature_returnsUnauthorized()
@Test void badSignature_doesNotConsumeRequestId()   // ký sai → 401; cùng requestId ký đúng → pass
@Test void reusedRequestId_returns409()
@Test void staleTimestamp_returnsUnauthorized()      // timestamp lệch 5 phút + 1ms
@Test void suspendedPartner_returns403()             // PARTNER_Y
@Test void unknownPartner_returns401()
@Test void missingHeaders_returns401()
```

- [ ] **Step 2:** FAIL → **Step 3:** cài đặt → **Step 4:** PASS.
- [ ] **Step 4b: Kiểm tra thủ công** — `export PARTNER_SECRET_PARTNER_X=...`; `scripts/sign.sh PARTNER_X '<body>'` in các header → curl → 200; sửa 1 ký tự body → 401; gửi lại y nguyên header hợp lệ → 409; `X-Partner-Code: PARTNER_Y` → 403.
- [ ] **Step 5: Commit** `feat(hub): request authentication task (HMAC, replay, partner status)`

---

### Task 8: Job tra lại UNKNOWN + log JSON + graceful shutdown

Sequence: job tick → `findByStatus(UNKNOWN)` → `CheckStatusVerticle` → connector CHECK_STATUS → simulator → update. State machine UNKNOWN → SUCCESS/FAILED.

**Files:**
- Create (core): `log/AppLog.java`, `log/JsonLogFormatter.java`, `src/main/resources/logging.properties` mẫu (mỗi service copy)
- Create (hub): `flow/CheckStatusVerticle.java`, `task/LoadTxnTask.java`, `job/RecheckUnknownVerticle.java`
- Modify: `AppData.java` + `PaymentContext.java` (`logFields()`), `AbstractRabbitServer.java` (drain khi stop), `AbstractRabbitClient.java` + `AbstractRabbitServer.java` (log có `requestId`, `partnerCode` từ header `x-request-id`, `x-partner-code`), connector `checkStatus` gọi simulator thật, `Launcher.java` (JUL dùng `JsonLogFormatter`), thay mọi chỗ log dữ liệu khách bằng `Masker`
- Test: `JsonLogFormatterTest`, `RecheckUnknownIT`, `GracefulShutdownIT`

**Interfaces:**
- `AppLog.info/warn/error(Logger, AppData d, String msg, Object... kv)` — ghi `d.logFields()`; **không** ghi `request/response/customerRef` thô. Thêm vào `AppData` (Modify `AppData.java`): `public Map<String,Object> logFields()` mặc định `{requestId, type}`; `PaymentContext` override thêm `partnerCode`, `supplierCode`, `partnerTxnId` (platform không import code hub).
- `JsonLogFormatter extends java.util.logging.Formatter` — 1 dòng JSON `{ts, level, logger, msg, ...kv, error}` (không thêm thư viện log).
- `LoadTxnTask` — nạp giao dịch theo `(partnerCode, partnerTxnId)` vào `PaymentContext`; không có → `NOT_FOUND`; trạng thái khác UNKNOWN/PROCESSING → `breakFlow=true` (không gọi supplier).
- `CheckStatusVerticle` workflow (`enableBreakFlow=true`): `LoadTxnTask → LockTask → CallConnectorTask(CHECK_STATUS) → UpdateTxnTask`.
- `RecheckUnknownVerticle` — `vertx.setPeriodic(config.recheck.intervalMs /*30000*/)`; cờ `running` để tick không chồng nhau; `findByStatus(UNKNOWN, olderThan=config.recheck.minAgeMs /*30000*/, limit 50)` → lần lượt `EventBuses.request(CheckStatusVerticle, ...)`; mỗi lỗi chỉ log, không dừng job.
- `AbstractRabbitServer.stop()`: `consumer.pause()` → chờ `inFlight == 0` (kiểm tra bằng `setPeriodic(100)`, tối đa `config.rabbit.shutdownGraceMs` 15000) → `consumer.cancel()` → `client.stop()`.

- [ ] **Step 1: Viết test (fail)**

```java
@Test void jsonLogIsOneLine() // format(record) không chứa "\n" trước cuối, parse được bằng new JsonObject(...)
// RecheckUnknownIT (fake connector consumer trả SUCCESS cho CHECK_STATUS, interval 200ms, minAge 0)
@Test void unknownBecomesSuccess()          // insert UNKNOWN → trong 2s status == SUCCESS
@Test void finalStatusNotRechecked()        // insert FAILED → fake consumer count == 0
// GracefulShutdownIT
@Test void inFlightMessageCompletesOnUndeploy() // handler mất 500ms; undeploy ngay sau khi nhận → reply vẫn tới client, queue messageCount == 0
```

- [ ] **Step 2:** FAIL → **Step 3:** cài đặt → **Step 4:** PASS.
- [ ] **Step 4b: Kiểm tra thủ công** — scenario SUP_A delay 11000 → topup ra UNKNOWN; `curl -XPUT localhost:8090/_control/transactions/<ref> -d '{"code":"00"}'` → trong ~30–60s DB chuyển SUCCESS. Log hub và connector cùng `requestId` (grep được cả 2 phía), SĐT bị che. Ctrl+C connector khi đang xử lý → log `"draining"` rồi dừng, không mất reply.
- [ ] **Step 5: Commit** `feat: recheck job for UNKNOWN, json logging, graceful shutdown`

---

### Task 9: gRPC BackOfficeService

Sequence back-office (spec 5, sơ đồ thứ 2).

**Files:**
- Modify: `payment-hub/pom.xml` — deps `vertx-grpc-server`, `protobuf-java` (4.36.x, xác nhận bản ổn định mới nhất); `vertx-grpc-client` scope `test`; plugin `io.github.ascopes:protobuf-maven-plugin` (5.1.x) với `protocVersion` khớp `protobuf-java` và JVM plugin `io.vertx:vertx-grpc-protoc-plugin2:${vertx.version}` (đọc docs của plugin để lấy đúng tên thẻ cấu hình và `mainClass` của protoc plugin Vert.x trước khi gõ).
- Create: `payment-hub/src/main/proto/backoffice.proto`, `grpc/BackOfficeGrpcVerticle.java`, `flow/BackOfficeVerticle.java`, `task/ListTxnTask.java`, `task/SetPartnerStatusTask.java`
- Modify: `verticle.json`, `app-local.json` (`grpc.port: 9090`)
- Test: `BackOfficeGrpcIT`

**Interfaces:**
- `backoffice.proto` (package `payhub.backoffice.v1`, `option java_package = "dev.gawio.payhub.hub.grpc.v1"`, `java_multiple_files = true`):

```proto
service BackOfficeService {
  rpc GetTransaction(TxnKey) returns (Transaction);
  rpc ListTransactions(ListTransactionsRequest) returns (ListTransactionsReply);
  rpc RecheckTransaction(TxnKey) returns (Transaction);
  rpc SetPartnerStatus(SetPartnerStatusRequest) returns (Partner);
}
message TxnKey { string partner_code = 1; string partner_txn_id = 2; }
message Transaction { string partner_code = 1; string partner_txn_id = 2; string service_type = 3;
  string provider_code = 4; string supplier_code = 5; int64 amount = 6; string status = 7;
  string supplier_txn_id = 8; string error_code = 9; string updated_at = 10; }
message ListTransactionsRequest { string status = 1; int32 limit = 2; }
message ListTransactionsReply { repeated Transaction items = 1; }
message SetPartnerStatusRequest { string partner_code = 1; string status = 2; }
message Partner { string partner_code = 1; string status = 2; }
```
  `customer_ref` cố ý không có trong `Transaction`.
- Sau khi `mvn -pl payment-hub -am generate-sources`, xem `payment-hub/target/generated-sources/` để lấy tên lớp service do plugin sinh (Vert.x 5 sinh lớp `...GrpcService`/`...Service` để override) và dùng lớp đó.
- `BackOfficeGrpcVerticle` — `GrpcServer.server(vertx)` + `HttpServer` port `config.grpc.port`; trước khi xử lý: metadata `authorization` phải bằng `"Bearer " + BACKOFFICE_TOKEN` (so sánh hằng thời gian) → sai/thiếu: `GrpcStatus.UNAUTHENTICATED`. Handler chỉ map → `PaymentContext`/`AppData` → `EventBuses.request(...)` → map kết quả. Mã lỗi → gRPC: `NOT_FOUND→NOT_FOUND`, `INVALID_REQUEST→INVALID_ARGUMENT`, `TXN_IN_PROGRESS→ABORTED`, khác → `INTERNAL`. `limit` ≤ 0 → 50, > 200 → 200.
- `BackOfficeVerticle extends WorkflowVerticle` — `workflowFor` theo `type`: `GET_TXN` (`LoadTxnTask`), `LIST_TXN` (`ListTxnTask`), `SET_PARTNER_STATUS` (`SetPartnerStatusTask`; status phải là `ACTIVE|SUSPENDED` → khác thì `INVALID_REQUEST`). `RecheckTransaction` gửi thẳng `CheckStatusVerticle` (Task 8).

- [ ] **Step 1: Viết test (fail)** — `GrpcClient` trong test, gọi qua stub sinh ra.

```java
@Test void getTransaction_returnsStoredTxn()        // seed SUCCESS → status "SUCCESS", không có customer_ref
@Test void getTransaction_missing_notFound()        // → GrpcStatus.NOT_FOUND
@Test void listUnknown_returnsOnlyUnknown()         // seed 2 UNKNOWN + 1 SUCCESS, status "UNKNOWN" → items.size == 2
@Test void recheck_movesUnknownToSuccess()          // fake connector consumer trả SUCCESS → reply.status == "SUCCESS"
@Test void suspendPartner_thenPartnerRestGets403()  // SetPartnerStatus(PARTNER_X, SUSPENDED) → REST topup ký đúng → 403
@Test void missingToken_unauthenticated()
```

- [ ] **Step 2:** FAIL → **Step 3:** cài đặt → **Step 4:** PASS.
- [ ] **Step 4b: Kiểm tra thủ công** (grpcurl dùng file proto, không cần reflection):
  `grpcurl -plaintext -import-path payment-hub/src/main/proto -proto backoffice.proto -H "authorization: Bearer $BACKOFFICE_TOKEN" -d '{"status":"UNKNOWN","limit":10}' localhost:9090 payhub.backoffice.v1.BackOfficeService/ListTransactions`
  → danh sách UNKNOWN; thiếu `-H` → `Code: Unauthenticated`.
- [ ] **Step 5: Commit** `feat(hub): gRPC back-office service sharing hub workflows`

---

## Sau cùng

- [ ] `mvn verify` và `mvn -Pit test` xanh với `docker compose up -d`.
- [ ] Cập nhật `README.md` (đã có khung, bỏ dòng "Trạng thái: … chưa có code"): cách chạy 5 service, biến môi trường, lệnh curl/grpcurl mẫu, cách đổi scenario simulator.
- [ ] Commit `docs: how to run payment hub locally`.
