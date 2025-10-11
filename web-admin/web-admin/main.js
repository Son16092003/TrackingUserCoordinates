import { client } from "./supabase.js";
import { devices, updateDeviceList } from "./map/devices.js";
import { addLivePoint, addSummaryPoints, clearSummaryMarkers, addDetailedPoints, clearDetailedMarkers } from "./map/markers.js";
import { createMap } from "./map/mapInit.js";

// --- DOM elements ---
const liveSidebar = document.getElementById("live-sidebar");
const summarySidebar = document.getElementById("summary-sidebar");
const liveContainer = document.getElementById("map-live");
const summaryContainer = document.getElementById("map-summary");

let mapLive = null;
let mapSummary = null;
let summaryDevicesLoaded = false;
let summaryDeviceMap = {}; // deviceId -> userName (may be null)

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

// Zoom threshold to trigger detailed load
const DETAIL_ZOOM_THRESHOLD = 14;
let summaryMoveEndHandler = null;

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
function showSummaryMessage(message, type = "info", options = {}) {
  // Tìm hoặc tạo message container
  let messageDiv = document.getElementById("summary-message");
  if (!messageDiv) {
    messageDiv = document.createElement("div");
    messageDiv.id = "summary-message";
    messageDiv.className = "summary-message";
    
    // Chèn message div vào summary sidebar, sau button
    const summarySidebar = document.getElementById("summary-sidebar");
    const summaryButton = document.getElementById("btn-summary");
    // Insert safely: if summaryButton is a child of summarySidebar, insert after it.
    if (summarySidebar && summaryButton) {
      const ref = summaryButton.nextSibling;
      // Ensure ref is a direct child of summarySidebar before using insertBefore
      if (ref && ref.parentNode === summarySidebar) {
        summarySidebar.insertBefore(messageDiv, ref);
      } else {
        summarySidebar.appendChild(messageDiv);
      }
    } else if (summarySidebar) {
      // Fallback: append to sidebar
      summarySidebar.appendChild(messageDiv);
    } else {
      // As last resort, append to body
      document.body.appendChild(messageDiv);
    }
  }

  // Set content và style
  messageDiv.className = `summary-message ${type}`;
  messageDiv.style.display = "block";
  // Optional spinner
  if (options.showSpinner) {
    messageDiv.innerHTML = `<span class="summary-spinner"></span>${message}`;
  } else {
    messageDiv.textContent = message;
  }

  // Auto hide sau 5 giây nếu là success message
  if (type === "success") {
    setTimeout(() => {
      hideSummaryMessage();
    }, 5000);
  }

  console.log(`📢 Summary message (${type}): ${message}`);

  // Expose helper on window for other modules (markers.js) to reuse
  try { window.showSummaryMessage = showSummaryMessage; } catch (e) { /* ignore */ }
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

    // Do not auto-attach moveend listener; user will click 'Load detail' to fetch detailed points.
  }

  if (tabName !== 'summary') {
    // Clear detailed markers when leaving summary
    try { clearDetailedMarkers(mapSummary); } catch (e) { /* ignore */ }
  }
}

// Handler that loads detailed points for current view when user clicks the button
async function loadDetailForCurrentView() {
  if (!mapSummary) return;
  try {
    const bounds = mapSummary.getBounds();
    const sw = bounds.getSouthWest();
    const ne = bounds.getNorthEast();

    const deviceSelect = document.getElementById('summary-device');
    const dateSelect = document.getElementById('summary-date');
    if (!deviceSelect || !dateSelect) return;
    const deviceId = deviceSelect.value;
    const dateStr = dateSelect.value;
    if (!deviceId || !dateStr) {
      showSummaryMessage('⚠️ Vui lòng chọn thiết bị và ngày trước khi load detail', 'warning');
      return;
    }

    const from = new Date(dateStr);
    from.setHours(0,0,0,0);
    const to = new Date(dateStr);
    to.setHours(23,59,59,999);

  showSummaryMessage('🔄 Đang tải dữ liệu chi tiết cho vùng hiện tại...', 'info', { showSpinner: true });

    const { data, error } = await client
      .from('locations')
      .select('latitude, longitude, timestamp')
      .eq('deviceId', deviceId)
      .gte('timestamp', from.getTime())
      .lte('timestamp', to.getTime())
      .gte('latitude', sw.lat)
      .lte('latitude', ne.lat)
      .gte('longitude', sw.lng)
      .lte('longitude', ne.lng)
      .order('timestamp', { ascending: true })
      .limit(5000);

    if (error) {
      console.warn('Error loading detailed points for bbox:', error);
      showSummaryMessage('❌ Có lỗi khi tải dữ liệu chi tiết', 'error');
      return;
    }

    if (data && data.length) {
  addDetailedPoints(deviceId, data, mapSummary, 5000);
  showSummaryMessage(`✅ Đã tải ${data.length} điểm chi tiết cho vùng hiện tại`, 'success');
    } else {
  clearDetailedMarkers(mapSummary);
  showSummaryMessage('ℹ️ Không có dữ liệu chi tiết trong vùng hiện tại', 'info');
    }

  } catch (err) {
    console.error('Error in loadDetailForCurrentView:', err);
    showSummaryMessage('❌ Có lỗi khi tải dữ liệu chi tiết', 'error');
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
      .gte("timestamp", thirtyMinutesAgo.getTime())
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
    // Lấy danh sách deviceId và userName từ bảng locations.
    // Chúng ta muốn danh sách theo deviceId để đảm bảo các điểm được ghi khi
    // webadmin đóng vẫn được truy vấn bằng deviceId.
    // Use DISTINCT to get unique deviceId (+ userName when available) across the table
    const { data, error } = await client
      .from("locations")
      .select("deviceId, userName", { distinct: true })
      .not("deviceId", "is", null);

    if (error) {
      console.error("Lỗi load devices:", error);
      return;
    }

    if (!data || data.length === 0) return;

    // Build map deviceId -> userName (may be null). If multiple rows exist,
    // prefer the first non-empty userName we encounter.
    summaryDeviceMap = {};
    data.forEach((row) => {
      const id = row.deviceId;
      const name = row.userName;
      if (!summaryDeviceMap[id]) {
        summaryDeviceMap[id] = name || null;
      } else if (!summaryDeviceMap[id] && name) {
        // If previously null but we find a name, use it
        summaryDeviceMap[id] = name;
      }
    });

    // Convert to array and sort by display name (userName) or deviceId
    const deviceEntries = Object.keys(summaryDeviceMap).map((id) => ({
      deviceId: id,
      userName: summaryDeviceMap[id],
    }));

    deviceEntries.sort((a, b) => {
      const aKey = (a.userName || a.deviceId).toLowerCase();
      const bKey = (b.userName || b.deviceId).toLowerCase();
      return aKey.localeCompare(bKey);
    });

    // Thêm options vào select - value là deviceId để query chính xác
    deviceEntries.forEach(({ deviceId, userName }) => {
      const opt = document.createElement("option");
      opt.value = deviceId;
      opt.textContent = userName && userName.trim() ? `${userName} — ${deviceId}` : deviceId;
      select.appendChild(opt);
    });

    console.log(`Loaded ${deviceEntries.length} unique devices for summary (by deviceId)`);
    if (deviceEntries.length > 0) {
      console.log("Sample summary devices:", deviceEntries.slice(0, 10));
    }

  } catch (err) {
    console.error("Exception khi load summary devices:", err);
    summaryDevicesLoaded = false; // Reset flag nếu có lỗi
  }
}

document.getElementById("btn-summary").onclick = async () => {
  const deviceId = document.getElementById("summary-device").value;
  const dateStr = document.getElementById("summary-date").value;

  if (!deviceId && !dateStr) {
    showSummaryMessage("⚠️ Vui lòng chọn thiết bị và ngày để xem lịch sử!", "warning");
    return;
  }
  if (!deviceId) {
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

  const displayName = summaryDeviceMap[deviceId] || deviceId;
  console.log(`🔍 Searching summary for ${deviceId} (${displayName}) on ${dateStr}`);

  // Hiển thị thông báo đang tải
  const formattedDate = new Date(dateStr).toLocaleDateString("vi-VN");
  showSummaryMessage(`🔍 Đang tải dữ liệu cho thiết bị "${displayName}" ngày ${formattedDate}...`, "info");

  initMapSummary(); // chắc chắn mapSummary đã init
  clearSummaryMarkers(mapSummary); // xóa tất cả markers cũ

  // Supabase/PostgREST often defaults to 1000 rows per request. Use paging via .range()
  // to fetch all rows for the given device + day.
  // First, try to get exact count for diagnostics
  try {
    const { data: countData, error: countError, count } = await client
      .from('locations')
      .select('id', { count: 'exact', head: false })
      .eq('deviceId', deviceId)
      .gte('timestamp', from.getTime())
      .lte('timestamp', to.getTime());

    if (countError) {
      console.warn('Could not get exact count for summary (count query):', countError);
    } else {
      console.log('Summary exact count reported by Supabase:', count || (countData && countData.length));
    }
  } catch (err) {
    console.warn('Exception when fetching summary count:', err);
  }

  const pageSize = 1000;
  let results = [];
  try {
  // Keyset pagination by id to avoid issues when rows are inserted/removed during paging.
  let lastId = null;
  let pageIndex = 0;
  while (true) {
      let query = client
        .from("locations")
        .select("id, latitude, longitude, timestamp, deviceId, userName")
        .eq("deviceId", deviceId)
        .gte("timestamp", from.getTime())
        .lte("timestamp", to.getTime())
        .order("id", { ascending: true })
        .limit(pageSize);

      if (lastId) {
        query = query.gt('id', lastId);
      }

      const { data: page, error: pageError } = await query;

      if (pageError) {
        console.error("Lỗi load summary page:", pageError);
        showSummaryMessage("❌ Có lỗi xảy ra khi tải dữ liệu. Vui lòng thử lại!", "error");
        return;
      }

      if (page && page.length) {
        results = results.concat(page);
        lastId = page[page.length - 1].id;
        console.log(`Fetched page ${pageIndex} size=${page.length} lastId=${lastId}`);
      } else {
        console.log(`Fetched page ${pageIndex} size=0`);
      }

      pageIndex++;

      // If returned less than pageSize then we're done
      if (!page || page.length < pageSize) break;
    }
  } catch (err) {
    console.error('Exception while paging summary results', err);
    showSummaryMessage("❌ Có lỗi xảy ra khi tải dữ liệu. Vui lòng thử lại!", "error");
    return;
  }

  console.log(`Fetched total ${results.length} rows for device ${deviceId} between ${from.getTime()} and ${to.getTime()}`);

  // If Supabase reported an exact count earlier and it doesn't match fetched results,
  // warn so we can investigate possible server-side limits or ordering issues.
  // (We logged the reported count above if available.)
  // Note: count may be undefined if the earlier query failed.

  // Kiểm tra có dữ liệu không
  if (!results || results.length === 0) {
    const formattedDate = new Date(dateStr).toLocaleDateString("vi-VN");
    showSummaryMessage(
      `📅 Không có dữ liệu di chuyển cho thiết bị "${displayName}" vào ngày ${formattedDate}`,
      "info"
    );
    console.log(`ℹ️ No data found for ${deviceId} (${displayName}) on ${dateStr}`);
    return;
  }

  // Có dữ liệu - hiển thị trên map
  console.log(`✅ Found ${results.length} location points for ${deviceId} (${displayName})`);
  showSummaryMessage(
    `✅ Đã tải ${results.length} điểm vị trí cho thiết bị "${displayName}"`,
    "success"
  );

  // addSummaryPoints expects first parameter as deviceId
  addSummaryPoints(deviceId, results, mapSummary);

  const coords = results.map((d) => [d.latitude, d.longitude]);
  if (coords.length) {
    // Ensure the map knows its size (in case the container was hidden before)
    try { mapSummary.invalidateSize(); } catch (e) { /* ignore */ }

    // Delay fitBounds a tick to allow invalidateSize/layout to complete
    setTimeout(() => {
      try {
        mapSummary.fitBounds(L.latLngBounds(coords).pad(0.2));
        console.log('main.js: fitBounds executed for summary map');
      } catch (err) {
        console.warn('main.js: fitBounds failed', err);
      }
    }, 80);
  }
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

  // Wire Load detail button
  const btnLoadDetail = document.getElementById('btn-load-detail');
  if (btnLoadDetail) {
    btnLoadDetail.addEventListener('click', () => {
      loadDetailForCurrentView();
    });
  }
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
