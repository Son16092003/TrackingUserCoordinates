import { client } from "./supabase.js";
import { devices, updateDeviceList } from "./map/devices.js";
import { addLivePoint, addSummaryPoints, clearSummaryMarkers } from "./map/markers.js";
import { createMap } from "./map/mapInit.js";

// --- DOM elements ---
const liveSidebar = document.getElementById("live-sidebar");
const summarySidebar = document.getElementById("summary-sidebar");
const liveContainer = document.getElementById("map-live");
const summaryContainer = document.getElementById("map-summary");

let mapLive = null;
let mapSummary = null;
let summaryDevicesLoaded = false;

// ------------------
// Khởi tạo map
// ------------------
function initMapLive() {
  if (!mapLive) {
    mapLive = createMap("map-live");
  }
}

function initMapSummary() {
  if (!mapSummary) {
    mapSummary = createMap("map-summary");
  }
}

/**
 * Reset flag để force reload summary devices
 */
function resetSummaryDevices() {
  summaryDevicesLoaded = false;
  const select = document.getElementById("summary-device");
  if (select) {
    select.innerHTML = "<option value=''>Chọn thiết bị</option>";
  }
}

/**
 * Refresh summary devices nếu đang ở tab summary
 */
function refreshSummaryDevicesIfNeeded() {
  const summaryTab = document.getElementById("tab-summary");
  if (summaryTab && summaryTab.classList.contains("active")) {
    resetSummaryDevices();
    loadSummaryDevices();
  }
}

/**
 * Lưu trạng thái visible của thiết bị vào localStorage
 */
export function saveDeviceVisibility() {
  const visibility = {};
  Object.entries(devices).forEach(([deviceId, device]) => {
    if (device.userName) {
      visibility[device.userName] = device.visible;
    }
  });
  localStorage.setItem('deviceVisibility', JSON.stringify(visibility));
  console.log('💾 Saved device visibility preferences');
}

/**
 * Restore trạng thái visible của thiết bị từ localStorage
 */
export function restoreDeviceVisibility() {
  try {
    const saved = localStorage.getItem('deviceVisibility');
    if (!saved) return;
    
    const visibility = JSON.parse(saved);
    let restoredCount = 0;
    
    Object.entries(devices).forEach(([deviceId, device]) => {
      if (device.userName && visibility.hasOwnProperty(device.userName)) {
        const wasVisible = device.visible;
        device.visible = visibility[device.userName];
        
        if (wasVisible !== device.visible) {
          restoredCount++;
          console.log(`🔄 Restored ${device.userName}: ${device.visible ? 'visible' : 'hidden'}`);
        }
      }
    });
    
    if (restoredCount > 0) {
      console.log(`✅ Restored visibility for ${restoredCount} devices`);
    }
  } catch (err) {
    console.error('Error restoring device visibility:', err);
  }
}

/**
 * Ẩn thông báo summary
 */
function hideSummaryMessage() {
  const messageDiv = document.getElementById("summary-message");
  if (messageDiv) {
    messageDiv.style.display = "none";
  }
}

/**
 * Hiển thị thông báo trong summary sidebar
 */
function showSummaryMessage(message, type = "info") {
  // Tìm hoặc tạo message container
  let messageDiv = document.getElementById("summary-message");
  if (!messageDiv) {
    messageDiv = document.createElement("div");
    messageDiv.id = "summary-message";
    messageDiv.className = "summary-message";
    
    // Chèn message div vào summary sidebar, sau button
    const summarySidebar = document.getElementById("summary-sidebar");
    const summaryButton = document.getElementById("btn-summary");
    summarySidebar.insertBefore(messageDiv, summaryButton.nextSibling);
  }

  // Set content và style
  messageDiv.textContent = message;
  messageDiv.className = `summary-message ${type}`;
  messageDiv.style.display = "block";

  // Auto hide sau 5 giây nếu là success message
  if (type === "success") {
    setTimeout(() => {
      hideSummaryMessage();
    }, 5000);
  }

  console.log(`📢 Summary message (${type}): ${message}`);
}

// Tab handlers đã được chuyển sang switchTab() function và addEventListener

// ------------------
// Tab Switching
// ------------------
function switchTab(tabName) {
  const tabLive = document.getElementById("tab-live");
  const tabSummary = document.getElementById("tab-summary");
  const liveContainer = document.getElementById("map-live");
  const summaryContainer = document.getElementById("map-summary");
  const liveSidebar = document.getElementById("live-sidebar");
  const summarySidebar = document.getElementById("summary-sidebar");

  if (tabName === "live") {
    // Active Live tab
    tabLive.classList.add("active");
    tabSummary.classList.remove("active");
    
    // Show/Hide containers
    liveContainer.style.display = "block";
    summaryContainer.style.display = "none";
    liveSidebar.style.display = "block";
    summarySidebar.style.display = "none";
    
    // Initialize map if needed
    initMapLive();
    if (mapLive) mapLive.invalidateSize();
    
    // Clear summary data từ map summary khi chuyển về live
    if (mapSummary) {
      import('./map/markers.js').then(module => {
        module.clearSummaryMarkers(mapSummary);
      });
    }
    
    // Hide summary message khi chuyển về live
    hideSummaryMessage();
    
  } else if (tabName === "summary") {
    // Active Summary tab
    tabLive.classList.remove("active");
    tabSummary.classList.add("active");
    
    // Show/Hide containers
    liveContainer.style.display = "none";
    summaryContainer.style.display = "block";
    liveSidebar.style.display = "none";
    summarySidebar.style.display = "block";
    
    // Initialize map and load devices if needed
    initMapSummary();
    if (mapSummary) mapSummary.invalidateSize();
    
    // Chỉ load devices một lần
    if (!summaryDevicesLoaded) {
      loadSummaryDevices();
      summaryDevicesLoaded = true;
    }
  }
}

// ------------------
// Live Mode
// ------------------
async function loadLiveDevices() {
  console.log("🔄 Loading live devices...");
  
  try {
    // Load dữ liệu gần đây nhất (trong vòng 30 phút) để khôi phục trạng thái thiết bị
    const thirtyMinutesAgo = new Date(Date.now() - 30 * 60 * 1000);
    
    const { data, error } = await client
      .from("locations")
      .select("deviceId, userName, latitude, longitude, timestamp")
      .gte("timestamp", thirtyMinutesAgo.getTime().toString())
      .order("timestamp", { ascending: false });

    if (error) {
      console.error("Error loading live devices:", error);
      return;
    }

    if (!data || data.length === 0) {
      console.log("ℹ️ No recent device data found (last 30 minutes)");
      
      // Try loading from last 24 hours as fallback
      const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000);
      const { data: dayData, error: dayError } = await client
        .from("locations")
        .select("deviceId, userName")
        .gte("timestamp", oneDayAgo.getTime().toString())
        .order("timestamp", { ascending: false })
        .limit(100);

      if (!dayError && dayData && dayData.length > 0) {
        console.log(`📋 Found ${dayData.length} device records from last 24h for reference`);
      }
      
      if (mapLive) updateDeviceList(mapLive);
      return;
    }

    console.log(`📍 Found ${data.length} recent location points`);

    // Group data theo deviceId và lấy điểm mới nhất của mỗi thiết bị
    const latestByDevice = {};
    data.forEach(point => {
      if (!latestByDevice[point.deviceId] || 
          point.timestamp > latestByDevice[point.deviceId].timestamp) {
        latestByDevice[point.deviceId] = point;
      }
    });

    const uniqueDevices = Object.values(latestByDevice);
    console.log(`👥 Found ${uniqueDevices.length} unique devices`);

    // Tạo lại devices với dữ liệu gần đây
    if (mapLive) {
      const { addLivePoint } = await import('./map/markers.js');
      
      uniqueDevices.forEach(point => {
        addLivePoint(
          point.deviceId,
          point.latitude,
          point.longitude,
          point.timestamp,
          point.userName,
          mapLive
        );
      });

      // Restore trạng thái visibility từ localStorage
      restoreDeviceVisibility();
      
      // Update device list với trạng thái đã restore
      updateDeviceList(mapLive);

      // Fit map đến thiết bị active nếu có
      const { fitMapToActiveDevicesExternal } = await import('./map/markers.js');
      fitMapToActiveDevicesExternal(mapLive, true);
    }

  } catch (err) {
    console.error("Exception in loadLiveDevices:", err);
  }
}

function subscribeLive() {
  client
    .channel("locations-changes")
    .on(
      "postgres_changes",
      { event: "INSERT", schema: "public", table: "locations" },
      (payload) => {
        const newRow = payload.new;
        if (!mapLive) initMapLive();
        addLivePoint(
          newRow.deviceId,
          newRow.latitude,
          newRow.longitude,
          newRow.timestamp,
          newRow.userName,
          mapLive
        );
      }
    )
    .subscribe();
}

// ------------------
// Summary Mode
// ------------------
async function loadSummaryDevices() {
  console.log("🔄 loadSummaryDevices() called - loading devices for summary...");
  
  const select = document.getElementById("summary-device");
  
  // Clear tất cả options cũ trừ option mặc định
  select.innerHTML = "<option value=''>Chọn thiết bị</option>";

  try {
    // Sử dụng RPC function để get distinct userName thay vì select tất cả
    const { data, error } = await client
      .from("locations")
      .select("userName")
      .not("userName", "is", null);

    if (error) {
      console.error("Lỗi load devices:", error);
      return;
    }

    if (!data || data.length === 0) return;

    // Loại bỏ duplicate userName
    const uniqueUsers = [...new Set(data.map((d) => d.userName))];
    
    // Sort theo alphabet
    uniqueUsers.sort();
    
    // Thêm options vào select
    uniqueUsers.forEach((userName) => {
      if (userName && userName.trim()) { // Kiểm tra userName không empty
        const opt = document.createElement("option");
        opt.value = userName;
        opt.textContent = userName;
        select.appendChild(opt);
      }
    });
    
    console.log(`Loaded ${uniqueUsers.length} unique devices for summary`);
    
  } catch (err) {
    console.error("Exception khi load summary devices:", err);
    summaryDevicesLoaded = false; // Reset flag nếu có lỗi
  }
}

document.getElementById("btn-summary").onclick = async () => {
  const userName = document.getElementById("summary-device").value;
  const dateStr = document.getElementById("summary-date").value;
  
  if (!userName && !dateStr) {
    showSummaryMessage("⚠️ Vui lòng chọn thiết bị và ngày để xem lịch sử!", "warning");
    return;
  }
  if (!userName) {
    showSummaryMessage("⚠️ Vui lòng chọn thiết bị!", "warning");
    return;
  }
  if (!dateStr) {
    showSummaryMessage("⚠️ Vui lòng chọn ngày!", "warning");
    return;
  }

  const from = new Date(dateStr);
  from.setHours(0, 0, 0, 0);
  const to = new Date(dateStr);
  to.setHours(23, 59, 59, 999);

  console.log(`🔍 Searching summary for ${userName} on ${dateStr}`);
  
  // Hiển thị thông báo đang tải
  const formattedDate = new Date(dateStr).toLocaleDateString("vi-VN");
  showSummaryMessage(`🔍 Đang tải dữ liệu cho thiết bị "${userName}" ngày ${formattedDate}...`, "info");
  
  initMapSummary(); // chắc chắn mapSummary đã init
  clearSummaryMarkers(mapSummary); // xóa tất cả markers cũ

  const { data, error } = await client
    .from("locations")
    .select("latitude, longitude, timestamp, deviceId")
    .eq("userName", userName)
    .gte("timestamp", from.getTime().toString())
    .lte("timestamp", to.getTime().toString())
    .order("timestamp", { ascending: true });

  if (error) {
    console.error("Lỗi load summary:", error);
    showSummaryMessage("❌ Có lỗi xảy ra khi tải dữ liệu. Vui lòng thử lại!", "error");
    return;
  }

  // Kiểm tra có dữ liệu không
  if (!data || data.length === 0) {
    const formattedDate = new Date(dateStr).toLocaleDateString("vi-VN");
    showSummaryMessage(
      `📅 Không có dữ liệu di chuyển cho thiết bị "${userName}" vào ngày ${formattedDate}`,
      "info"
    );
    console.log(`ℹ️ No data found for ${userName} on ${dateStr}`);
    return;
  }

  // Có dữ liệu - hiển thị trên map
  console.log(`✅ Found ${data.length} location points for ${userName}`);
  showSummaryMessage(
    `✅ Đã tải ${data.length} điểm vị trí cho thiết bị "${userName}"`,
    "success"
  );

  addSummaryPoints(userName, data, mapSummary);

  const coords = data.map((d) => [d.latitude, d.longitude]);
  if (coords.length) mapSummary.fitBounds(L.latLngBounds(coords).pad(0.2));
};

// ------------------
// Khi load web
// ------------------
window.addEventListener("DOMContentLoaded", () => {
  // Hiển thị Live Tracking mặc định
  liveContainer.style.display = "block";
  summaryContainer.style.display = "none";
  liveSidebar.style.display = "block";
  summarySidebar.style.display = "none";

  initMapLive();
  if (mapLive) mapLive.invalidateSize();

  loadLiveDevices();
  subscribeLive();
});

// ------------------
// Event Listeners cho Tabs và Dialog
// ------------------
document.getElementById("tab-live").addEventListener("click", () => {
  switchTab("live");
});

document.getElementById("tab-summary").addEventListener("click", () => {
  switchTab("summary");
});

// Dialog hướng dẫn
document.getElementById("btn-guide").addEventListener("click", () => {
  document.getElementById("guide-dialog").style.display = "flex";
});

document.getElementById("close-guide").addEventListener("click", () => {
  document.getElementById("guide-dialog").style.display = "none";
});

// Đóng dialog khi click bên ngoài
document.getElementById("guide-dialog").addEventListener("click", (e) => {
  if (e.target.id === "guide-dialog") {
    document.getElementById("guide-dialog").style.display = "none";
  }
});
