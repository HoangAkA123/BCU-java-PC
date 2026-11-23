# API ability và effect cho BCU

API v1 revision 2 cho phép thêm logic riêng cho mèo và kẻ địch trong pack tùy chỉnh.
Chỉ cần một JAR API chung; mỗi ability mới gồm Java và icon, không cần build lại BCU nếu dùng các điểm mở rộng bên dưới.
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

## Các điểm mở rộng chiến đấu (revision 2)

Giữ `API_VERSION = 1` để tương thích plugin cũ; `API_REVISION = 2` đánh dấu các hàm bổ sung.
Tất cả callback mới có hành vi mặc định không thay đổi trận đấu.

| Cơ chế | API và thứ tự xử lý |
| --- | --- |
| Né, miễn nhiễm cả đòn | `allowHit(Hit)` ở bên nhận, trước xử lý sát thương và proc gốc. Trả `false` bỏ toàn bộ đòn trên mục tiêu đó. |
| Sát thương | `outgoingDamage`, `incomingDamage`, `onDamaged`, `onHit` giữ nguyên. `Hit.attack.attacker` là nguồn, `Hit.target` là bên nhận. |
| HP, hồi máu, lá chắn hấp thụ | `healthChange(Context, delta)` điều chỉnh tổng HP thay đổi trong tick, sau giáp và trước kiểm tra ngưỡng knockback. Delta dương hồi máu, âm gây sát thương. `onHealthChanged(Context, previousHealth)` thông báo HP đã thay đổi. |
| Di chuyển | `movement(Context, distance)` điều chỉnh bước đi tự nguyện sau slow/speed. Khoảng cách có dấu theo trục thế giới, không phải tốc độ. Knockback/Warp vẫn dùng `allowInterruption`. |
| Tạo/sửa đòn đánh | `beforeAttack(Context, AttackDraft)` sửa sát thương, hai tọa độ thế giới, proc hoặc đặt `cancelled = true`. `index` là slot đòn gốc; `-1` là đòn tạo bằng API. |
| Trạng thái gốc | `incomingProcs(Hit, Proc)` sửa bản sao các proc trước khi engine áp trạng thái lên mục tiêu. Có thể `procs.STOP.clear()` để bỏ đóng băng mà giữ slow; không ảnh hưởng bản sao của mục tiêu khác. |
| Trạng thái riêng | `applyEffect`, `removeEffect`, `allowEffect(Context, source, effectId)` hỗ trợ effect thường trực/tạm thời, xếp chồng và miễn nhiễm theo ID. |
| Hạ gục/hồi sinh | `reviveHealth(Context)` trả HP dương để hồi sinh tức thời khi HP vừa về 0, sau cơ chế sống sót gốc và trước ghi nhận kill. `onRevive` thông báo hồi sinh API hoặc bắt đầu hồi sinh zombie gốc. `onDeath` là chết cuối cùng. |
| Chọn mục tiêu | `context.targets(radius, allies)` trả snapshot chỉ đọc theo thứ tự trận: cùng phe khi `true`, khác phe khi `false`; có thể gồm chính mình, loại trụ và entity đã chết. |
| Đánh thêm, đánh lan, phản đòn | `context.attack(damage, from, to, procs)` tạo đòn diện rộng theo hướng của nhân vật, có thể truyền `null` cho proc rỗng. Đòn vào lượt capture kế tiếp, không chen vào danh sách đang được duyệt. |
| Triệu hồi | `context.summon(Proc.SUMMON)` dùng cơ chế summon gốc: ID, form, cấp/magnification, vị trí, trễ, layer, giới hạn quân, chia sẻ HP. Trả `true` khi đưa được entity vào hàng chờ. |
| Thanh tẩy | `context.clearStatus(target, Status)` gỡ STOP, SLOW, WEAK, CURSE, SEAL, POISON, ARMOR, SPEED hoặc LETHARGY, kể cả stack nội bộ của weak/poison. `statusTime` đọc số tick còn lại, lớn nhất nếu nhiều stack. |
| Hẹn giờ | `context.age()` đếm tick riêng của instance; `cooldown(key, ticks)` trả `true` và bắt đầu cooldown nếu đã sẵn sàng. |
| Hiệu ứng hình ảnh | `onRender(Visual)` vẽ hình RGBA hoặc `FakeImage`; trạng thái animation có thể cập nhật trong `onTick`. |

`context.damage(target, amount)` và `heal(target, amount)` bổ sung overload tác động lên mục tiêu.
Đây là thay đổi HP trực tiếp qua hàng đợi, chịu giáp và `healthChange`, không đi qua phòng thủ của một đòn đánh,
không phát `onHit` và không tự cấp kill cho nguồn. Dùng `attack` nếu cần xử lý như đòn đánh gốc.
Tổng delta qua callback HP được giới hạn trong ±maxHP; HP hồi không vượt maxHP.
Không hồi entity đã chết bằng `heal`; dùng `reviveHealth` tại thời điểm mất HP.

Các callback chạy theo ID ability đã lưu, rồi theo thứ tự effect được gắn; modifier sau nhận kết quả trước.
Một callback từ chối hit/interruption/effect là đủ để chặn; hủy `AttackDraft` không thể được callback sau bật lại.
Hồi sinh dùng callback đầu tiên trả về HP dương. Plugin tự lưu số lần hồi sinh còn lại trong `Behavior`.
`RuntimeException`/`LinkageError` vô hiệu hóa instance lỗi; kết quả sửa proc/attack lỗi bị bỏ.
NaN/Infinity trong khoảng cách bị từ chối. Không sao chép proc nếu plugin không sử dụng hook lọc/sửa proc.
Hàng đợi đòn API giới hạn 256 đòn mỗi lượt capture; summon lồng nhau giới hạn 8 cấp để tránh vòng lặp sinh vô hạn.

## Ví dụ hồi sinh, miễn đóng băng và tăng tốc

Lưu thành `SecondWind.java`, thêm `SecondWind.png` rồi bật trong editor:

```java
import common.battle.extension.BattleExtension;
import common.util.Data.Proc;
import java.util.Arrays;
import java.util.List;

public class SecondWind implements BattleExtension {
    public String id() { return "example.second_wind"; }
    public String name() { return "Second Wind"; }
    public List<Parameter> parameters() {
        return Arrays.asList(
            Parameter.integer("lives", "Số lần hồi sinh", 1, 0, 10),
            Parameter.integer("hp", "HP hồi (%)", 30, 1, 100)
        );
    }
    public Behavior create(Settings settings) {
        return new Behavior() {
            int lives = settings.integer("lives");
            public void incomingProcs(Hit hit, Proc procs) { procs.STOP.clear(); }
            public long reviveHealth(Context c) {
                if (lives == 0) return 0;
                lives--;
                return Math.max(1, c.self.maxH * settings.integer("hp") / 100);
            }
            public void onRevive(Context c) {
                c.clearStatus(c.self, Status.SLOW);
                c.applyEffect(c.self, "example.second_wind.haste", new Behavior() {
                    public float movement(Context c, float distance) { return distance * 1.5f; }
                    public void onRender(Visual v) {
                        v.rectangle(-10, -60, 20, 4, 70, 210, 255, 200);
                    }
                }, 90, Stacking.REFRESH);
            }
        };
    }
}
```

Ví dụ gọi summon từ `onTick`, `onHit` hoặc `onDeath` (ID phải tồn tại trong dữ liệu đã tải):

```java
common.util.Data.Proc.SUMMON summon = common.util.Data.Proc.blank().SUMMON;
summon.id = new common.pack.Identifier<>("000000", common.util.unit.Enemy.class, 0);
summon.mult = 100; // 100% magnification cho enemy; đối với Unit là cấp
summon.type.fix_buff = true;
summon.dis = summon.max_dis = 100;
summon.time = 30;
boolean queued = context.summon(summon);
```

Đặt `type.ignore_limit = true` chỉ khi chủ ý bỏ giới hạn quân của màn.
Summon không tự tung xác suất: gọi `context.chance(percent)` và `cooldown` trước nếu cần.
Đòn API cũng dùng giá trị proc đã chọn; ví dụ đặt `STOP.time = 60` sẽ yêu cầu freeze trên đòn đó,
còn kháng, trait và các điều kiện gốc vẫn do engine xét.

## Giới hạn và kiểm tra

API này bao phủ các nhóm chiến đấu trên, không bảo đảm mọi cơ chế tương lai đều không cần sửa engine.
`incomingProcs` can thiệp các trạng thái trong `Entity.processProcs`, không tính lại crit, barrier hay các proc
đã xử lý ở bước sát thương. `beforeAttack` chạy sau chuẩn bị đòn gốc; hủy draft không hoàn tác summon,
dịch chuyển theo animation hoặc hiệu ứng thời gian đã kích hoạt trong bước chuẩn bị.
Đòn wave/counter do engine tự sinh không gọi lại `beforeAttack`, nhưng vẫn qua các hook nhận sát thương.
`movement` không thay thế teleport, burrow hoặc dịch chuyển theo animation.
Hồi sinh API là tức thời, độc lập Zombie Killer, không tạo hoạt ảnh zombie; việc xóa entity hoặc tự hủy
trực tiếp không đi qua `reviveHealth`. Base không dùng hồi sinh API.

Plugin là Java có quyền truy cập engine qua `Context.self`/`battle()`; thay đổi trực tiếp các trường gốc
nằm ngoài bảo đảm của các helper. Không tạo đòn trong `beforeAttack`, không gọi đệ quy lại sự kiện đang xử lý.
Phản đòn từ `onDamaged` nên có cooldown hoặc bộ đếm để tránh hai phía phản đòn vô tận.
Không bảo đảm tua ngược/snapshot trận với trạng thái `Behavior` tùy ý; replay từ đầu cần cùng plugin và RNG.

Chạy `JAVA_HOME=/path/to/jdk8 bash scripts/test-ability-api.sh` sau khi build JAR.
Bộ kiểm tra không cần asset game, kiểm tra các hook nối engine, HP/hồi sinh tức thời, proc riêng từng mục tiêu,
đòn đánh, di chuyển, cooldown, xử lý plugin lỗi và hàng đợi. Các hoạt ảnh và tổ hợp proc trong trận thật
cần được thử thêm với pack mà người dùng sử dụng.
