# Kho Video (Android)

Ứng dụng Android lưu trữ video từ máy vào kho riêng của app.

## Tính năng
- Tự tìm **tất cả video trong máy** (bộ nhớ trong và thẻ nhớ) qua MediaStore, không cần chọn thủ công, không chép file.
- Xin quyền "Truy cập video" một lần. Trên Android 14+, nếu chỉ cho phép một số video, app hiện thông báo để cho phép tất cả.
- Nhóm theo thư mục (Camera, Download, Zalo...), tìm kiếm, sắp xếp; xem dạng lưới hoặc danh sách.
- Tự cập nhật khi máy có video mới hoặc video bị xoá.
- Phát video (Media3 ExoPlayer) với giao diện riêng: thanh tua, nút khoá màn hình, video trước/sau trong thư mục, tua lùi/tới 5 giây, giữ màn hình để tua nhanh x2, kéo thanh tua có ảnh xem trước, xoay ngang/dọc. Luôn mở ở màn hình dọc.
- Đổi tên hiển thị (chỉ trong app), chia sẻ, ẩn/bỏ ẩn video. App không xoá hay sửa video gốc.
- **Gacha**: quay ngẫu nhiên ra một video **chưa xem** (toàn máy hoặc trong một thư mục). App ghi nhớ video đã mở xem; khi xem hết thì bắt đầu vòng mới.
- **Chuỗi lửa**: mỗi ngày (tính từ 1:30 sáng đến 1:30 sáng hôm sau) xem đủ 30 phút được 1 🔥 lửa đỏ (thiếu ngày thì về 0). 7 lửa đỏ → 1 lửa trắng, 3 lửa trắng → 1 lửa xanh (mốc tăng thêm 1 sau mỗi lần nhận, dùng thì mốc về lại ban đầu). Lửa xanh bù ngày bị lỡ; lửa trắng giảm mục tiêu hôm nay (xanh + trắng + đỏ/100 phút, tối thiểu 1 phút, không dùng trong tháng 11); đổi 3 trắng → 1 xanh. Chỉ tính thời gian video thực sự đang phát.
- **Mục Ảnh** (tách riêng với Video, chuyển bằng thanh dưới cùng): tự tìm mọi ảnh trong máy kể cả thẻ nhớ, nhóm theo album, lưới ảnh chia theo ngày chụp, tìm theo tên (không cần dấu), sắp xếp. Xem ảnh toàn màn hình: vuốt ngang chuyển ảnh, chụm 2 ngón hoặc chạm 2 lần để phóng to, chạm 1 lần để ẩn/hiện thanh công cụ; chia sẻ, mở bằng app khác, xem thông tin ảnh. Quyền truy cập ảnh xin riêng, chỉ đọc, không sửa hay xoá ảnh gốc.
- **Chọn thư mục ảnh**: mục Ảnh chỉ hiện các thư mục bạn chọn (nút thư mục có dấu + ở góc trên). Thư mục được nhớ theo thẻ nhớ + đường dẫn: rút thẻ ra thì tạm ẩn, cắm lại tự hiện, không cần chọn lại.

Tên đổi và danh sách video bị ẩn lưu trong SharedPreferences của app.
Video chép từ bản 1.0 (nếu có, trong `files/videos`) hiện ở thư mục "Bản chép trong app", có thể lưu ra Thư viện hoặc xoá.

## Build
Cần JDK 17 và Android SDK (compileSdk 36). Android tối thiểu: 8.0 (API 26).

```bash
# Bản gọn nhẹ để cài dùng (~1,9 MB, đã bật R8 thu gọn code và tài nguyên)
./gradlew assembleRelease
# -> tự xoá các KhoVideo-*.apk cũ và tạo KhoVideo-<versionName>.apk ở thư mục gốc dự án
#    (đổi versionCode/versionName trong app/build.gradle.kts khi ra bản mới)

# Bản debug để phát triển (~21 MB, chưa tối ưu)
./gradlew assembleDebug
```

Bản release hiện đang ký bằng khoá debug để cài thử. Khi đưa lên Google Play cần tạo khoá release riêng.

## Cấu trúc
- `VideoRepository.kt`: sao chép, liệt kê, đổi tên, xoá và xuất video; tạo ảnh thu nhỏ.
- `LibraryViewModel.kt`: trạng thái màn hình và tiến trình nhập video.
- `MainActivity.kt`: giao diện Compose và nhận intent chia sẻ.
- `PlayerActivity.kt`: trình phát video.
