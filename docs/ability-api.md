# API ability và effect cho BCU

API v1 cho phép thêm logic riêng cho mèo và kẻ địch trong pack tùy chỉnh.
BCU biên dịch mã nguồn khi khởi động bằng trình biên dịch đi kèm trong JAR.
File Java chạy với quyền của BCU. Chỉ cài plugin do bạn viết hoặc nhận từ nguồn đáng tin cậy.

## Cài plugin

Tạo thư mục `assets/ability` trong thư mục dữ liệu của BCU, cạnh JAR khi chạy bản đóng gói.
Mỗi plugin cần một file Java và một icon cùng tên, ví dụ `PoisonStrike.java` và `PoisonStrike.png`.
Icon có thể dùng PNG, JPG, JPEG hoặc GIF. PNG trong suốt 32 × 32 là lựa chọn phù hợp cho danh sách.
Tên lớp public phải khớp tên file và lớp cần constructor public không tham số.
Có thể dùng package Java. Các lớp trợ giúp của mỗi plugin nên nằm trong cùng file Java.

Khởi động lại BCU sau khi thêm, chỉnh sửa hoặc xóa file. Mở trang chỉnh sửa một dạng mèo hoặc một kẻ địch
trong pack tùy chỉnh. Mục mới nằm cuối danh sách ability trên trang chính, có icon và nhãn
`[Ability]` hoặc `[Effect]`. Dùng Ctrl hoặc phím chọn nhiều mục của hệ điều hành để bật cùng
ability có sẵn. Nhấp đúp mục để nhập tham số, rồi lưu pack như bình thường.
Dữ liệu lưu ID và tham số, vì vậy người khác cần cài các plugin tương ứng để cơ chế hoạt động.
Các mục từ plugin chưa cài được giữ trong dữ liệu pack. Plugin lỗi được bỏ qua và ghi chẩn đoán
ra stderr, có thể xem bằng cách chạy JAR từ terminal.

## Tạo ability và effect gắn với đòn đánh

Lưu ví dụ sau thành `PoisonStrike.java` và cung cấp một ảnh `PoisonStrike.png`.

```java
import common.battle.extension.BattleExtension;
import java.util.Arrays;
import java.util.List;

public class PoisonStrike implements BattleExtension {
    public String id() { return "example.poison_strike"; }
    public String name() { return "Poison Strike"; }
    public String description() { return "Có xác suất gây độc khi đánh trúng."; }

    public List<Parameter> parameters() {
        return Arrays.asList(
            Parameter.integer("chance", "Xác suất (%)", 25, 0, 100),
            Parameter.integer("ticks", "Thời gian (tick)", 90, 1, 900)
        );
    }

    public Behavior create(Settings settings) {
        int chance = settings.integer("chance");
        int duration = settings.integer("ticks");
        return new Behavior() {
            public void onHit(Hit hit, int damage) {
                if (damage > 0 && !hit.target.isBase() && hit.context.chance(chance)) {
                    hit.context.applyEffect(hit.target, "example.poison", duration, Stacking.REFRESH);
                }
            }
        };
    }
}
```

Lưu effect thành `Poison.java` và cung cấp một ảnh `Poison.png`.

```java
import common.battle.extension.BattleExtension;
import java.util.Collections;
import java.util.List;

public class Poison implements BattleExtension {
    public String id() { return "example.poison"; }
    public String name() { return "Poison"; }
    public Kind kind() { return Kind.EFFECT; }

    public List<Parameter> parameters() {
        return Collections.singletonList(Parameter.integer("amount", "HP mất mỗi giây", 20, 0, 10000));
    }

    public Behavior create(Settings settings) {
        int amount = settings.integer("amount");
        return new Behavior() {
            private int ticks;
            public void onTick(Context context) {
                if (++ticks % 30 == 0) context.damage(amount);
            }
            public void onRender(Visual visual) {
                visual.rectangle(-12, -52, 24, 6, 120, 200, 75, 190);
            }
        };
    }
}
```

Chỉ cần bật **Poison Strike** cho nhân vật tấn công. Khi đánh trúng, ability tự gắn **Poison**
lên mục tiêu. Bật trực tiếp **Poison** trong trình chỉnh sửa sẽ gắn hiệu ứng thường trực lên
nhân vật đó. Để truyền tham số khi gắn effect, dùng overload của `applyEffect` nhận chuỗi JSON
ví dụ `"{\"amount\":40}"`.

## Quy ước API

- Dùng ID duy nhất và ổn định, ví dụ `tenpack.ten_ability`. Tên hiển thị và tên file có thể đổi
  mà dữ liệu đã lưu vẫn tìm được plugin theo ID.
- `side()` có thể trả về `CAT`, `ENEMY` hoặc `BOTH`. Quy tắc phe áp dụng cả trong editor
  và khi gắn effect lúc chiến đấu.
- `parameters()` khai báo số nguyên, số thập phân, boolean hoặc chuỗi. Kiểu số có giới hạn.
  `create(Settings)` tạo trạng thái riêng cho mỗi nhân vật hoặc mỗi stack.
- `onStart` chạy khi nhân vật được đặt vào trận hoặc effect được gắn.
  `onTick` chạy theo cập nhật mô phỏng của nhân vật, thường 30 tick mỗi giây ở tốc độ chuẩn.
  `onAttackStart` chạy khi bắt đầu động tác tấn công, kể cả đòn sau đó trượt.
  `onDeath` chạy khi nhân vật chết. `onKill` chạy khi BCU ghi nhận đòn kết liễu.
  `onEnd` dành cho effect tạm thời khi hết hạn hoặc bị gỡ.
- `outgoingDamage` và `incomingDamage` lần lượt chỉnh sát thương của bên đánh và bên nhận
  sau các bước miễn nhiễm, khiên và tính sát thương mặc định, trước khi BCU cộng vào lượng HP
  sẽ xử lý. Đòn đã bị chặn hoàn toàn trước bước này không gọi callback.
  `onDamaged` rồi `onHit` thông báo đòn đã được nhận. Chúng có thể chạy với sát thương bằng 0.
- `applyEffect` hỗ trợ `REFRESH` (đặt lại thời hạn và giữ trạng thái),
  `REPLACE` (kết thúc các instance cũ và tạo mới) và `STACK` (tạo instance riêng, tối đa 64
  effect tạm thời trên mỗi nhân vật). `removeEffect` gỡ tất cả stack tạm thời của ID.
- Plugin có thể tạo `Behavior` tạm thời ngay trong file Java và truyền nó vào overload
  `applyEffect(target, instanceId, behavior, ticks, stacking)`. Cách này phù hợp với effect
  riêng của một ability và không cần thêm definition hoặc icon thứ hai.
- `allowInterruption` có thể cho phép hoặc chặn `KNOCKBACK`, `DAMAGE_KNOCKBACK`,
  `BOSS_WAVE`, `SNIPER` và `WARP`. Dùng `cancelDisplacement` khi effect bắt đầu nếu cần
  hủy phần dịch chuyển đã được xếp hàng trước đó. Khi callback trả về `false`, sát thương và
  quy trình hạ gục vẫn được BCU xử lý.
- `Context` cung cấp nhân vật hiện tại, nguồn gắn hiệu ứng, trạng thái trận, xác suất dùng bộ
  sinh số của trận, hàng đợi thay đổi HP và quyền truy cập các đối tượng chiến đấu gốc.
  `heal` và `damage` đi qua bước xử lý HP tiếp theo của nhân vật.
- `onRender` nhận vị trí và độ thu phóng để vẽ hình chữ nhật RGBA hoặc sprite `FakeImage`
  lên nhân vật. Chỉ đọc trạng thái trong callback vẽ. Thực hiện thay đổi mô phỏng trong callback
  chiến đấu và dùng trạng thái an toàn giữa các luồng nếu cần truyền dữ liệu sang phần vẽ.
- Replay cần cùng phiên bản plugin, cùng tham số và logic xác định. Dùng `Context.chance`
  hoặc RNG của trận cho xác suất. Tránh dùng thời gian thực trong logic chiến đấu.

Build JAR bằng `./gradlew desktopJar` với JDK 21 để biên dịch BCU.
JAR tạo ra chạy với Java 8 và có trình biên dịch plugin tích hợp.
