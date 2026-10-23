# Coop online: thiết kế (phase 3)

> Trạng thái: **đã duyệt**. M1 (nhóm lượt trong hotseat) đã làm, code ở `coop/CoopTime.java`. Dựa trên khảo sát code v4.0.2 + phần coop hotseat đã có (phase 0–1).

## 1. Mục tiêu

- Mỗi người chơi một máy (desktop trước, Android sau). Một máy làm **host**, các máy khác **join** qua LAN hoặc IP.
- Mỗi người chỉ điều khiển hero của mình và chỉ thấy tầm nhìn của hero đó (đã có từ phase 0).
- **Mô hình thời gian kiểu Divinity: Original Sin 2**:
  - Hero ở xa nhau, không ảnh hưởng nhau → hành động **độc lập, cùng lúc**, không ai phải chờ ai.
  - Khi các hero **ảnh hưởng tới nhau** (gần nhau, cùng đánh một nhóm quái, cùng trong phòng boss) → gộp vào chung một **nhóm lượt** và đi theo lượt như hotseat hiện tại.
- Giữ nguyên nguyên tắc cũ: code mới trong package `coop`, file gốc chỉ có hook `// COOP-HOOK`, solo không đổi.

## 2. Những gì khảo sát cho thấy

| Vấn đề | Thực tế trong code |
|---|---|
| Vòng lặp lượt | `Actor.process()` dùng **một đồng hồ chung** `now`; chọn actor có `time` nhỏ nhất trong một `HashSet` toàn cục. 51 chỗ thêm actor/timer, 322 lời gọi `spend`. |
| Ngẫu nhiên | `Random` là một generator **toàn cục, không seed** khi chơi; logic (≈1400 lời gọi) và hiệu ứng hình ảnh (≈250) dùng chung, kể cả trên 2 luồng khác nhau. |
| Thứ tự duyệt | `Actor.all`, `Level.mobs` là `HashSet` của object (hash theo địa chỉ bộ nhớ) → thứ tự khác nhau giữa các máy. |
| Nhập lệnh | ≈150 điểm vào, gần như tất cả chạy logic **ngay trên luồng render** (dùng item, cửa sổ nâng cấp, chọn mục tiêu, talent…). |
| Gắn kết hiển thị | Logic gọi trực tiếp sprite ≈970 chỗ, `GameScene` ≈550, `GLog` ≈610, âm thanh ≈500, hiệu ứng ≈470. Nhiều hành động chỉ kết thúc khi animation chạy xong. |
| Lưu trữ | `Bundle` ghi/đọc được ra byte trong bộ nhớ (JSON + gzip): game ≈3 KB, một tầng ≈2–15 KB. |
| Mạng | Chưa có gì (chỉ HTTP cho tin tức/cập nhật). |

## 3. Kiến trúc mạng: chọn **host-authoritative**

### Phương án A: lockstep (mọi máy cùng mô phỏng, chỉ gửi lệnh), **không chọn**
Muốn mọi máy ra kết quả giống hệt nhau thì phải:
- tách RNG logic khỏi RNG hiệu ứng ở hàng trăm chỗ. Có cả những chỗ "chỉ tạo hiệu ứng nếu ô đó nằm trong tầm nhìn", nên RNG bị rút khác nhau trên mỗi máy vì mỗi người có tầm nhìn riêng;
- đổi mọi `HashSet` actor/mob sang thứ tự cố định (≈100 khai báo);
- biến ≈150 điểm nhập lệnh trên luồng render thành lệnh chạy ở cùng một thời điểm trên mọi máy.

Việc này sửa sâu vào hàng trăm file gốc, đi ngược nguyên tắc "merge rẻ", và rất dễ lệch (desync) âm thầm.

### Phương án B: host-authoritative, **đề xuất**
- **Host** chạy game thật như hotseat hiện tại: mọi hero, quái, RNG đều nằm trên host. Host là nguồn sự thật duy nhất, nên không bao giờ desync.
- **Client** là một "màn hình từ xa" có game scene riêng nhưng **không chạy logic**:
  1. **Snapshot**: khi join hoặc đổi tầng, client nhận `Bundle` của tầng và các hero (vài chục KB), rồi dựng scene từ đó.
  2. **Luồng sự kiện**: host bắt sự kiện ở vài "nút thắt" (`CharSprite` move/attack/zap/die/status, các lớp hiệu ứng, `GLog`, âm thanh, cập nhật ô/đống đồ/fog), gửi kèm id nhân vật. Client phát lại trên sprite của mình, nên animation giống hệt host.
  3. **Đồng bộ trạng thái nhẹ** sau mỗi lượt: HP, vị trí, buff của các nhân vật trong tầm nhìn, túi đồ và quickslot của hero mình. Thỉnh thoảng gửi lại snapshot đầy đủ để tự sửa sai lệch.
- **Lệnh từ client**: bấm ô, dùng item (id item + action + mục tiêu), chọn trong cửa sổ… Host chạy lệnh đúng như khi UI gọi. Cửa sổ chọn lựa (nâng cấp, chọn item, quest) **mở trên máy client** (client có bản sao túi đồ), rồi gửi kết quả về host.
- Hook ở các nút thắt thì ít: khoảng vài chục điểm, thay vì hàng trăm.

**Đánh đổi:** client có độ trễ đúng bằng một vòng gửi-nhận với host (LAN thì không đáng kể); các cửa sổ chọn lựa phải viết "adapter" dần dần.

## 4. Mô hình thời gian kiểu DOS2: "nhóm lượt" (time bubble)

- Mỗi hero thuộc một **nhóm lượt**, và mỗi nhóm có **đồng hồ riêng** (`now` riêng).
- **Ngoài combat các hero luôn tự do**, kể cả khi đứng cạnh nhau (như DOS2).
- **Hai hero liên kết** (chung nhóm) khi có ít nhất một điều kiện:
  1. **cùng bị kéo vào một trận đánh**, giống combat của DOS2: một hero *tham gia* trận với một con quái đang săn khi con quái nhắm vào hero đó, hoặc hero nhìn thấy nó, hoặc hero vừa đánh trúng nó. Mọi hero tham gia cùng một con quái thì chung nhóm, dù đứng xa nhau, **hoặc**
  2. có **boss còn sống và đã tỉnh** trên tầng: aggro của boss phủ cả tầng, nên cả đội đi theo lượt chung, **hoặc**
  3. cùng trong một tầng boss đang khóa (arena).
  - Không dùng bán kính trận: ai **thấy** trận mới bị kéo vào, nên người ở phòng bên cạnh, cách tường, không bị khóa lượt mà không hiểu vì sao.
  - Liên kết có tính bắc cầu: A–B và B–C thì cả ba chung một nhóm.
  - Trong một nhóm, lượt đi đúng theo thứ tự thời gian như hotseat. Ai đang chờ lệnh thì đó là lượt của người đó.
- **Quái và vật thể** thuộc nhóm của hero gần nhất hoặc hero nó đang săn. Quái ở chỗ không có hero nào thì đứng yên, cho tới khi một nhóm tới gần.
- **Actor toàn tầng** (khí độc, lửa, bộ hồi sinh quái, timer) chạy theo **đồng hồ tầng** = đồng hồ của nhóm đang đi trước nhất, để không chạy nhanh gấp đôi.
- **Lịch chạy:** host cho từng nhóm chạy **độc lập**. Nhóm nào có hero vừa nhận lệnh thì chạy tới khi hero đó cần lệnh tiếp. Nhóm đang chờ người chơi thì tạm dừng, không chặn nhóm khác.
- **Gộp nhóm:** khi hai nhóm liên kết lại, nhóm có đồng hồ chậm hơn được **dời thời gian** cho bằng nhóm kia, giữ nguyên thứ tự lượt bên trong. **Chỉ đồng hồ thay đổi**: buff của hero (hunger, hồi máu, nạp charge của wand/artifact, thời hạn buff) được dời cùng, nên không có gì được tính cho khoảng thời gian bị bỏ qua. Từ lúc đó cả nhóm đi theo lượt như hotseat.
- **Tách nhóm:** khi không còn điều kiện liên kết (đi xa, hết quái), mỗi hero lại có đồng hồ riêng.
- Luật cầu thang giữ nguyên: cả đội phải đứng sát cầu thang mới xuống được (không cần chung nhóm lượt).

Phần này làm và kiểm thử **trong hotseat trước** (bot headless), rồi mới gắn mạng. Vì vậy lỗi về thời gian không bị lẫn với lỗi mạng.

## 5. Milestone

| # | Nội dung | Kiểm thử |
|---|---|---|
| M1 | Nhóm lượt kiểu DOS2 trong hotseat: mỗi nhóm một đồng hồ, liên kết/gộp/tách, đồng hồ tầng | bot headless: 2–4 hero tách xa thì đi độc lập, vào combat thì gộp; save/load giữ đúng đồng hồ |
| M2 | Tầng mạng + lobby: host/join qua IP hoặc LAN, giao thức tin nhắn (Bundle JSON nén), ping, ngắt kết nối | test 2 tiến trình headless trên cùng máy |
| M3 | Client "màn hình từ xa": snapshot, sự kiện sprite/hiệu ứng/log/âm thanh, fog theo hero mình; lệnh di chuyển/đánh/nhặt/cầu thang | 1 host + 1 client headless chơi tới tầng 5, so trạng thái client với host |
| M4 | Túi đồ, quickslot, dùng item, chọn mục tiêu (wand, ném, spell), cửa sổ nâng cấp/chọn item | như trên + kịch bản dùng item |
| M5 | Quest, shop, alchemy, talent, armor ability, nối lại khi rớt mạng, snapshot sửa lệch | chơi tay qua LAN |

## 6. Rủi ro chính

- Một số hành động chỉ kết thúc khi animation xong. Host vẫn phải chạy animation (headless được, như bộ test hiện tại), nên không ảnh hưởng client.
- Cửa sổ chọn lựa nhiều, mỗi loại cần adapter. Làm dần theo mức độ hay dùng.
- Mô hình nhóm lượt chạm vào `Actor.process`, file cốt lõi. Mình sẽ giữ hook nhỏ và đặt logic trong `coop/CoopTime.java`.
