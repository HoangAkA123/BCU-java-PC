# BCU Ability API v1 — revision 2

Tải `BCU-Ability-API.jar`, đặt vào thư mục BCU hiện có và chạy bằng Java 8.
Thêm plugin bằng cặp `TênAbility.java` + `TênAbility.png` trong `assets/ability`, rồi khởi động lại.
Chỉ dùng một JAR API chung cho các ability dùng API này. Không cần cài Codex để chạy.

Bản này mở rộng sát thương/HP, di chuyển, lọc trạng thái, tạo đòn đánh, chọn mục tiêu,
triệu hồi, hồi sinh tức thời, cooldown và effect tạm thời. Anchor vẫn tương thích.
`BCU-Anchor-Plugin.zip` chỉ chứa plugin và hướng dẫn, không chứa JAR riêng.

- [Hướng dẫn API, ví dụ và giới hạn](https://github.com/HoangAkA123/BCU-java-PC/blob/dfff9ab5/docs/ability-api.md)
- [Mã nguồn desktop và kiểm tra](https://github.com/HoangAkA123/BCU-java-PC/commit/dfff9ab5)
- [Mã nguồn engine](https://github.com/HoangAkA123/BCU-java-PC/commit/47619d45)

Build và smoke test Java 8 đã qua, gồm Anchor, ví dụ SecondWind, lưu dữ liệu pack,
hai editor, các hook chiến đấu và xử lý plugin lỗi. Smoke test dùng entity/battle fixture,
không thay thế việc thử hoạt ảnh và tổ hợp proc trong trận thật với asset/pack của bạn.

Các file animated-background và BCU-Anchor.zip cũ được giữ để tham khảo;
dùng `BCU-Ability-API.jar` cho bộ API mới nhất này.
