# Anchor (Ghim chặt)

Ghim đối thủ khi nhận đòn đánh. Khi đang bị ghim, đối thủ vẫn có thể di chuyển
và tấn công theo cơ chế bình thường, nhưng không bị đẩy lùi, Warp hoặc lùi khi
qua ngưỡng HP. Sát thương vẫn được tính. Khi hết HP, nhân vật đi qua cơ chế
hạ gục và hồi sinh của BCU tại vị trí hiện tại.

Chạy bản `BCU-Ability-API.jar` bằng Java 8. Giữ hai file `Anchor.java` và
`Anchor.png` trong `assets/ability` cạnh JAR. Anchor sử dụng API effect tạm thời
và hook interruption tổng quát, nên không cần một bản JAR riêng.

Khởi động lại BCU và bật **Anchor (Ghim chặt)** cho mèo hoặc kẻ địch **tấn công**
trong trình chỉnh sửa pack. Nhấp đúp để chỉnh thời hạn **0,1–60 giây**
(nhập dấu chấm cho phần thập phân). Mặc định **3 giây**. Đòn trúng tiếp theo
làm mới thời gian nếu thời lượng mới dài hơn thời gian còn lại. Căn cứ và
đòn hồi máu không nhận Anchor.
