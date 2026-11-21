# C00-B — Baseline tải trước thay đổi Phase 2

Ngày chạy: 21/11/2026 (Asia/Saigon). Owner: B. Người review/nghiệm thu: A.

## 1. Kết luận

Baseline đã chạy lại trên application commit
`2797255bdf03558fc010f446b7141e7e3ec594f8`, gồm warm-up, ba lượt load chuẩn
và một lượt hot-wallet đầy đủ 1.920 giây. Tất cả lượt chính k6 exit 0; 951/951
ví đối soát đúng với ledger, không có số dư âm và k6 không quan sát invariant
violation.

Hệ thống không đạt sạch lỗi dưới workload này. Achieved RPS trung vị là 92,55
trên mục tiêu 100; p99 trung vị là 4.441 ms. Có 1.209 unexpected responses:
993 conflict 409 và 216 lỗi 500. Trong 216 lỗi 500 có 189 deadlock khi update
wallet và 27 lỗi connection pool/JPA EntityManager chỉ xuất hiện ở run 02.
Những lỗi này là đầu vào cho C05-A; C00-B không sửa logic Payment để làm đẹp
baseline.

Hot-wallet giữ cùng 100 RPS mục tiêu nhưng dồn transfer vào một receiving
wallet. Scenario đạt 91,47 RPS, p95 1.807 ms và phát sinh 14.075 unexpected
transfer failures: 7.419 conflict 409 và 6.656 deadlock 500. Đây là bottleneck
contention nghiêm trọng cần giữ làm mốc cho C05-A/C11-B.

## 2. Phạm vi và phương pháp

- Application/SUT, MySQL và k6 là ba container riêng nhưng cùng một Docker
  Desktop host. k6 gọi thẳng `http://app:8080` qua Compose bridge network.
- App process được warm trước và giữ nguyên giữa ba lượt để không reset JIT.
- Trước mỗi lượt đo, `digitalwallet_db` được tạo lại từ schema Phase 1; mọi ví
  mở ở số dư 0.
- Profile: 200 accounts, target 100 RPS, ramp-up 120 giây, hold 1.800 giây,
  tổng `durationSec=1920`, 50 receiving wallets.
- Executor `ramping-vus` là closed model. Vì vậy k6 không phát
  `dropped_iterations`; báo cáo ghi N/A thay vì giả định bằng 0.
- Sau mỗi lượt, expected balance được tính từ ledger:
  `topup + transfer-in - withdraw - transfer-out`, rồi so với `wallets.balance`.
- Raw CSV được giữ local/upload dưới dạng PR hoặc CI artifact. File không commit
  vào Git vì mỗi lượt khoảng 298–304 MB; byte size và SHA-256 nằm trong
  `run-summary.json`.

Warm-up qua `host.docker.internal` cho thấy Docker Desktop NAT tạo dial timeout;
lượt đó được dừng và lưu dưới tên `diagnostic-host-gateway-aborted`, không dùng
trong baseline. Warm-up bridge network sau đó đạt 0 unexpected failure và được
dùng để xác nhận topology đo.

## 3. Môi trường

| Thành phần | Giá trị |
|---|---|
| OS | Windows NT 10.0.26300.0 |
| CPU | Intel Core i5-12450HX, 12 logical processors |
| RAM | 12.578.578.432 bytes (~11,71 GiB) |
| Docker Server | 29.6.1 |
| Java trong SUT | Eclipse Temurin 21.0.13.1+2 LTS |
| Gradle / Spring Boot | 9.8.1 / 4.0.8 |
| MySQL | 8.4.13, image digest ghi trong `environment.json` |
| k6 | 2.4.0, image digest ghi trong `environment.json` |

Hạn chế quan trọng: host RAM trung bình 89,6–93,3%, cực đại 96,4–97,5% trong
ba lượt. Vì load generator và SUT vẫn chia sẻ cùng host, kết quả này là baseline
local có thể tái lập, không phải tuyên bố capacity cô lập.

## 4. Kết quả ba lượt

| Run | Requests | Achieved RPS | p50 | p95 | p99 | Unexpected | HTTP failed* | Deadlocks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 01 | 179.301 | 92,85 | 8,59 ms | 217,00 ms | 4.747,05 ms | 374 | 4,72% | 54 |
| 02 | 175.386 | 90,71 | 8,50 ms | 357,56 ms | 4.441,38 ms | 422 | 4,72% | 71 |
| 03 | 178.913 | 92,55 | 8,42 ms | 1.124,18 ms | 4.292,73 ms | 413 | 4,67% | 64 |
| Trung vị | — | **92,55** | **8,50 ms** | **357,56 ms** | **4.441,38 ms** | **413** | **4,72%** | **64** |

`*` `http_req_failed` của k6 gồm cả HTTP 400 thiếu tiền mà workload chủ động
mong đợi, nên không dùng nó thay `unexpected_failure`. Unexpected rate thực tế
lần lượt xấp xỉ 0,21%, 0,24% và 0,23%.

Throughput khá ổn định (CV 1,03%) và p50 ổn định (CV 0,79%). Tail latency có
vấn đề: p95 dao động 217–1.124 ms (CV 70,4%), trong khi p99 luôn rất cao,
4.293–4.747 ms. Không chọn riêng run đẹp nhất để kết luận.

## 5. Lỗi và contention

| Nhóm lỗi | Run 01 | Run 02 | Run 03 | Tổng |
|---|---:|---:|---:|---:|
| Transfer 409 conflict | 320 | 324 | 349 | 993 |
| 500 do DB deadlock | 54 | 71 | 64 | 189 |
| 500 do connection pool unavailable | 0 | 27 | 0 | 27 |
| Tổng unexpected | 374 | 422 | 413 | 1.209 |

Run 02 connection-pool failures phân bố: topup 8, transfer 6, get wallet 6,
get linked banks 3, withdraw 3 và history 1. Deadlock log là MySQL error 1213 /
SQLState 40001 khi update `wallets`. Row-lock waits tăng 408/449/461 và tổng
row-lock time tăng 20.225/27.918/25.659 ms theo từng run.

### Hot-wallet riêng

| Requests | RPS | p50 | p95 | p99 | Unexpected | 409 | Deadlock 500 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 176.909 | 91,47 | 9,59 ms | 1.807,28 ms | 4.116,68 ms | 14.075 | 7.419 | 6.656 |

Hot-wallet có 22.411 row-lock waits và 693.680 ms row-lock time tăng thêm.
201/201 ví vẫn đối soát khớp, nhưng tỷ lệ unexpected `7,95%` cho thấy hệ thống
hiện không chịu được contention tập trung dù không làm sai số dư sau workload.

Phân công xử lý tiếp theo:

- C05-A: retry boundary/transaction mới, lock ordering, phân biệt 409 retryable
  với 500 và kiểm tra pool exhaustion.
- C11-B: chạy lại cùng profile/topology sau khi các thay đổi Phase 2 đã merge;
  so sánh cả tail latency, conflict/deadlock và invariant, không chỉ RPS.

## 6. Đối soát dữ liệu

| Run | Wallets | Actual total | Expected total | Mismatch | Số dư âm | Violation phía client |
|---|---:|---:|---:|---:|---:|---:|
| 01 | 250 | 1.754.117.619 | 1.754.117.619 | 0 | 0 | 0 |
| 02 | 250 | 1.703.002.450 | 1.703.002.450 | 0 | 0 | 0 |
| 03 | 250 | 1.740.760.927 | 1.740.760.927 | 0 | 0 | 0 |
| Hot-wallet | 201 | 1.525.804.903 | 1.525.804.903 | 0 | 0 | 0 |

Kết quả trên chứng minh dữ liệu của bốn workload cụ thể tự nhất quán sau tải; nó
không phải bằng chứng tuyệt đối rằng mọi lịch chạy concurrency đều an toàn.

## 7. Evidence và tái lập

- Hướng dẫn/lệnh: `benchmark/README.md`.
- Kết quả tổng hợp máy đọc được:
  `benchmark/evidence/C00-B-redo/aggregate-summary.json`.
- Môi trường: `benchmark/evidence/C00-B-redo/environment.json`.
- Mỗi run có metadata, k6 summary, normalized summary, sampler, app/MySQL log,
  reconciliation TSV và checksum raw metrics.
- Lệnh tổng hợp lại:

```powershell
python benchmark/scripts/aggregate_runs.py `
  --evidence-dir benchmark/evidence/C00-B-redo `
  --output benchmark/evidence/C00-B-redo/aggregate-summary.json
```

Không tuyên bố "0 violation" là chứng minh tuyệt đối; các lỗi 409/500, p95 biến
động và shared-host RAM pressure đều là known limits bắt buộc giữ trong so sánh
C11-B.
