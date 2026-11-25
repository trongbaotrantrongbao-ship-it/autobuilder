# AutoBuilder (Fabric 1.21.1, client-side)

Tự xây công trình theo block ảo (hologram) của Litematica.

## Cách build file .jar
1. Cài JDK 21.
2. Tải **litematica** và **malilib** đúng bản Minecraft (Modrinth), bỏ vào thư mục `libs/`.
3. Chạy `gradle build` (hoặc `./gradlew build` nếu có wrapper). File mod nằm ở `build/libs/autobuilder-1.0.0.jar`.
4. Bỏ jar vào thư mục `mods` cùng với: Fabric API, Litematica, MaLiLib (Zalith Launcher: chọn Fabric cho instance).
- Đổi sang phiên bản MC khác: sửa `gradle.properties` (minecraft_version, yarn_mappings, fabric_version). Một số tên hàm có thể phải sửa theo bản mới.

## Cách dùng
1. Nạp schematic trong Litematica, tạo placement và đứng gần công trình.
2. Bật bằng phím **K** hoặc lệnh `/autobuilder start` (tắt: `/autobuilder stop`).
   Lệnh khác: `/autobuilder status`, `/autobuilder radius <n>`, `/autobuilder reload`.
3. Mod xây từng tầng từ dưới lên. Mod không tự đi bộ: khi hết việc trong tầm với, thanh trạng thái báo "hãy lại gần ...".

## Mod làm gì
- Quét block ảo trong bán kính `scanRadius` quanh bạn, sắp xếp theo Y tăng dần.
- Đặt đúng trạng thái (hướng, nửa trên/dưới, trục...) bằng cách mô phỏng `getPlacementState` của vanilla với mọi tổ hợp mặt bấm / độ cao / hướng nhìn.
- Block lơ lửng không có điểm tựa: đặt giàn giáo tạm (cobblestone/dirt/netherrack), xong thì đập đi.
- Repeater (delay), comparator (mode), note block, trapdoor, fence gate: đặt rồi bấm chuột phải cho đến khi khớp.
- Slab đôi: đặt nửa đầu, bấm thêm để thành slab đôi.
- Block có `waterlogged=true` (lá có nước, slab, stairs...): dùng xô nước lên block. Mỗi block tốn 1 xô nước.
- Thiếu vật phẩm: gõ `/ah search <tên tiếng Anh>`, chọn món rẻ nhất (theo giá/1 item), bấm "Đồng ý". Có giới hạn giá `maxPricePerListing`.

## Cấu hình: `config/autobuilder.properties`
Chỉnh `ahCommand`, `resultTitle`, `confirmTitle`, `confirmButtonName` nếu menu /ah của server khác.

## Giới hạn đã biết
- Chưa biên dịch/chạy thử. Có thể cần sửa vài tên hàm theo bản Litematica/Yarn bạn dùng. Chỗ phụ thuộc nhiều nhất: `SchematicWorldHandler.getSchematicWorld()`.
- Bỏ qua: block entity có dữ liệu (rương, biển, banner), cửa 2 ô đang mở, block sai chỗ (không tự phá), đuốc tường ở vài trường hợp đặc biệt.
- Server có anti-cheat có thể chặn/ban việc đặt block tự động và quay đầu liên tục. Hãy đọc luật server trước khi dùng.
