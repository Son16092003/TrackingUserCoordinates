import { client } from "../supabase.js";

// Hàm này dùng để cập nhật danh sách các userName vào dropdown <select>
export async function refreshDeviceSelect() {
  // Lấy danh sách userName duy nhất từ bảng "locations"
  const { data, error } = await client
    .from("locations")
    .select("userName", { distinct: true }); // DISTINCT để loại bỏ các userName trùng lặp

  if (!error) {
    // Lấy thẻ <select> có id "filter-device"
    const select = document.getElementById("filter-device");
    if (select) {
      const current = select.value || ""; // lưu giá trị đang chọn để giữ lại sau khi cập nhật
      select.innerHTML = `<option value="">Tất cả</option>`; // option mặc định "Tất cả"

      // Duyệt danh sách userName và tạo option cho mỗi user
      [...new Set((data || []).map((d) => d.userName))].forEach((userName) => {
        const opt = document.createElement("option");
        opt.value = userName;        // value = userName
        opt.textContent = userName;  // hiển thị tên user
        if (userName === current) opt.selected = true; // giữ lại lựa chọn cũ nếu có
        select.appendChild(opt);     // thêm vào dropdown
      });
    }
  }
}

// Hàm này gắn sự kiện cho nút filter để lọc dữ liệu theo ngày và userName
export function setupFilterUI() {
  // setTimeout 0 để chắc chắn DOM đã render xong
  setTimeout(() => {
    const btn = document.getElementById("btn-filter"); // lấy nút lọc
    if (btn) {
      btn.addEventListener("click", () => { // khi nhấn nút
        const date = document.getElementById("filter-date").value; // lấy ngày
        const userName = document.getElementById("filter-device").value; // lấy userName

        if (!date) { // kiểm tra đã chọn ngày chưa
          alert("Vui lòng chọn ngày!");
          return;
        }

        // Chuyển ngày sang mốc thời gian bắt đầu và kết thúc của ngày đó
        const from = new Date(date);
        from.setHours(0, 0, 0, 0); // 00:00:00
        const to = new Date(date);
        to.setHours(23, 59, 59, 999); // 23:59:59

        const fromMs = from.getTime().toString(); // timestamp bắt đầu ngày
        const toMs = to.getTime().toString();     // timestamp kết thúc ngày

        console.log("Lọc:", { fromMs, toMs, userName });

        // Gọi hàm global loadInitialData để lọc dữ liệu:
        // - from: thời gian bắt đầu
        // - to: thời gian kết thúc
        // - userName: nếu chọn "Tất cả" thì null, không lọc theo userName
        window.loadInitialData({
          from: fromMs,
          to: toMs,
          userName: userName || null,
        });
      });
    }
  }, 0);
}
