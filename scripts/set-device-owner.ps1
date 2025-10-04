# =========================
# Config
# =========================
$avdName = "Pixel_6a_DeviceOwner"   
$apkPath = "C:\tttn\BackgroundLocationTracking\app\build\outputs\apk\debug\app-debug.apk"  
$packageName = "com.plcoding.backgroundlocationtracking"  
$deviceAdminReceiver = ".admin.MyDeviceAdminReceiver"  

function Wait-ForBoot {
    Write-Host "Waiting for emulator to boot..."
    do {
        Start-Sleep -Seconds 3
        $deviceStatus = adb devices | Select-String "emulator"
    } while (-not $deviceStatus)

    Write-Host "Emulator online, waiting for boot complete..."
    do {
        Start-Sleep -Seconds 3
        $bootCompleted = adb shell getprop sys.boot_completed 2>$null
    } while ($bootCompleted -ne "1")

    Write-Host "Emulator boot complete!"
}

function Start-Emulator {
    Write-Host "Killing all running emulators..."
    adb devices | ForEach-Object {
        if ($_ -match "emulator-(\d+)\s+device") {
            $emulatorId = $matches[1]
            Write-Host "Killing emulator-$emulatorId ..."
            adb -s "emulator-$emulatorId" emu kill
        }
    }
    Start-Sleep -Seconds 5

    Write-Host "Starting AVD $avdName with wipe-data..."
    Start-Process "emulator" "-avd $avdName -wipe-data -netdelay none -netspeed full"
    Wait-ForBoot
}

function Setup-DeviceOwner {
    Write-Host "Installing APK..."
    adb install -r "$apkPath"

    Write-Host "Activating DeviceAdminReceiver..."
    adb shell dpm set-active-admin "$packageName/$deviceAdminReceiver"

    Write-Host "Setting Device Owner..."
    $result = adb shell dpm set-device-owner "$packageName/$deviceAdminReceiver" 2>&1

    if ($result -match "java.lang.IllegalStateException") {
        Write-Host "⚠️  Failed to set device owner (probably due to accounts). Retrying with full wipe..."
        Start-Emulator
        Setup-DeviceOwner
    } else {
        Write-Host "Device Owner set successfully!"
    }
}

# =========================
# Main
# =========================
Start-Emulator
Setup-DeviceOwner

Write-Host "Checking Device Owner..."
adb shell dpm list-owners

