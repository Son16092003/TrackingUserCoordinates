import { startIcon, pauseIcon, liveIcon, endIcon } from "./icons.js";
import { devices, getColorFromId, updateDeviceList } from "./devices.js";

export let summaryMarkers = [];

// Debounce timer cho việc fit map
let fitMapTimer = null;

/**
 * Fit bản đồ đến các thiết bị đang active (realtime và visible)
 */
function fitMapToActiveDevices(map, immediate = false) {
  const performFit = () => {
    const activeDevices = Object.values(devices).filter(
      d => d.visible && d.status === "realtime" && d.coords.length > 0 && !d.isOffline
    );

    if (activeDevices.length === 0) return;

    // Lấy tọa độ mới nhất của tất cả thiết bị active
    const activeCoords = activeDevices.map(device => {
      const lastCoord = device.coords[device.coords.length - 1];
      return [lastCoord.lat, lastCoord.lon];
    });

    if (activeCoords.length === 1) {
      // Nếu chỉ có 1 thiết bị, center và zoom đến vị trí đó
      map.setView(activeCoords[0], 16);
    } else {
      // Nếu có nhiều thiết bị, fit bounds để hiển thị tất cả
      const bounds = L.latLngBounds(activeCoords);
      map.fitBounds(bounds.pad(0.1)); // Thêm 10% padding
    }
  };

  if (immediate) {
    // Fit ngay lập tức cho thiết bị mới
    performFit();
  } else {
    // Clear timer cũ
    if (fitMapTimer) {
      clearTimeout(fitMapTimer);
    }

    // Debounce 2 giây để tránh fit liên tục
    fitMapTimer = setTimeout(performFit, 2000);
  }
}

/**
 * Thêm điểm Live (realtime) + trail + trạng thái
 * map: mapLive truyền vào
 */
export function addLivePoint(deviceId, lat, lon, timestamp, userName, map) {
  const color = getColorFromId(deviceId);
  const time = new Date(Number(timestamp));

  if (!devices[deviceId]) {
    devices[deviceId] = {
      deviceId,
      userName,
      coords: [],
      trailMarkers: [],
      color,
      visible: true,
      liveMarker: null,
      liveTimer: null,
      lastTimestamp: time,
      status: "realtime", // realtime, pause, offline
      isOffline: false,
    };
  }

  const device = devices[deviceId];
  device.coords.push({ lat, lon, timestamp: time });
  if (device.coords.length > 50) device.coords.shift();

  device.lastTimestamp = time;
  device.status = "realtime";
  device.isOffline = false; // Đánh dấu là online lại

  // Remove old liveMarker nếu có
  if (device.liveMarker) {
    map.removeLayer(device.liveMarker);
    clearInterval(device.liveTimer);
  }

  const marker = L.marker([lat, lon], { icon: liveIcon }).bindPopup(
    `📡 ${deviceId}<br>${time.toLocaleString("vi-VN")}`
  );

  if (device.visible) marker.addTo(map);
  device.liveMarker = marker;

  // Timer check trạng thái Realtime / Pause / Offline
  device.liveTimer = setInterval(() => {
    const now = new Date();
    const diffSec = (now - device.lastTimestamp) / 1000;

    let newStatus = "realtime";
    if (diffSec > 180) newStatus = "offline"; // >3 phút
    else if (diffSec > 60) newStatus = "pause"; // >1 phút

    if (newStatus !== device.status) {
      device.status = newStatus;

      // Xử lý icon
      if (device.liveMarker) map.removeLayer(device.liveMarker);

      if (newStatus === "realtime") {
        device.liveMarker = L.marker([lat, lon], { icon: liveIcon }).addTo(map);
      } else if (newStatus === "pause") {
        device.liveMarker = L.marker([lat, lon], { icon: pauseIcon }).addTo(map);
      } else if (newStatus === "offline") {
        // Hiển thị endIcon thay vì xóa marker
        device.liveMarker = L.marker([lat, lon], { icon: endIcon })
          .bindPopup(`📴 ${deviceId} - Offline<br>${device.lastTimestamp.toLocaleString("vi-VN")}`)
          .addTo(map);
        
        // Giữ lại trail, không xóa
        // Đánh dấu là offline để không hiển thị trong sidebar
        device.isOffline = true;
        
        // Dừng timer vì đã offline
        clearInterval(device.liveTimer);
        device.liveTimer = null;
        
        // Cập nhật sidebar để xóa thiết bị offline
        updateDeviceList(map);
      }
    }
  }, 5000);

  // Thêm trail
  const trailMarker = L.circleMarker([lat, lon], { radius: 4, color });
  if (device.visible) trailMarker.addTo(map);
  device.trailMarkers.push(trailMarker);

  // Fit bản đồ đến thiết bị realtime nếu đang visible
  if (device.visible && device.status === "realtime") {
    // Nếu đây là điểm đầu tiên của thiết bị, fit ngay lập tức
    const isFirstPoint = device.coords.length === 1;
    fitMapToActiveDevices(map, isFirstPoint);
  }

  updateDeviceList(map);
}

/**
 * Load toàn bộ điểm Summary
 */
export function addSummaryPoints(deviceId, coords, map) {
  let device = devices[deviceId] || { coords: [], trailMarkers: [], color: "#3388ff" };

  // Xóa marker cũ
  device.trailMarkers.forEach((m) => map.removeLayer(m));
  if (device.liveMarker) {
    map.removeLayer(device.liveMarker);
    clearInterval(device.liveTimer);
  }

  device.coords = coords.map((c) => ({
    lat: c.latitude,
    lon: c.longitude,
    timestamp: new Date(Number(c.timestamp)),
  }));
  device.trailMarkers = [];

  if (device.coords.length === 0) return;

  // Start marker
  const first = device.coords[0];
  const startMarker = L.marker([first.lat, first.lon], { icon: startIcon })
    .addTo(map)
    .bindPopup(`Bắt đầu<br>${deviceId}<br>${first.timestamp.toLocaleString()}`);
  device.trailMarkers.push(startMarker);

  // Trail
  device.coords.forEach((c) => {
    const m = L.circleMarker([c.lat, c.lon], { radius: 4, color: device.color });
    m.addTo(map);
    device.trailMarkers.push(m);
  });

  // End marker
  const last = device.coords[device.coords.length - 1];
  const endMarker = L.marker([last.lat, last.lon], { icon: endIcon })
    .addTo(map)
    .bindPopup(`Kết thúc<br>${deviceId}<br>${last.timestamp.toLocaleString()}`);
  device.trailMarkers.push(endMarker);

  devices[deviceId] = device;

  // Fit map
  const allCoords = device.coords.map((c) => [c.lat, c.lon]);
  map.fitBounds(L.latLngBounds(allCoords).pad(0.2));
}

/**
 * Xóa tất cả marker summary cũ
 */
export function clearSummaryMarkers(map) {
  // Xóa tất cả markers từ summaryMarkers array (nếu có)
  summaryMarkers.forEach((m) => m.remove());
  summaryMarkers = [];
  
  // Xóa tất cả trail markers từ tất cả devices
  Object.values(devices).forEach((device) => {
    if (device.trailMarkers && device.trailMarkers.length > 0) {
      device.trailMarkers.forEach((marker) => {
        if (map) {
          map.removeLayer(marker);
        } else {
          marker.remove();
        }
      });
      device.trailMarkers = [];
    }
    
    // Xóa live marker nếu có (trong summary mode không cần live marker)
    if (device.liveMarker && map) {
      map.removeLayer(device.liveMarker);
      device.liveMarker = null;
    }
    
    // Clear timer nếu có
    if (device.liveTimer) {
      clearInterval(device.liveTimer);
      device.liveTimer = null;
    }
  });
  
  const deviceCount = Object.keys(devices).length;
  console.log(`🧹 Cleared all summary markers from ${deviceCount} devices on map`);
}

/**
 * Export hàm fit map để sử dụng từ file khác
 */
export function fitMapToActiveDevicesExternal(map, immediate = false) {
  fitMapToActiveDevices(map, immediate);
}
