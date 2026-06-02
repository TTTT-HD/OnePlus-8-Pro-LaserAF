# OnePlus 8 Pro Laser AF Distance Sensor

A simple Android application to read the real-time distance value from the Laser AutoFocus (LAF) sensor (VL53L1) on the OnePlus 8 Pro. 
This project is largely developed with Gemini CLI.

## Features
- Reads distance in millimeters (mm) in real-time.
- Uses OnePlus-specific `com.oneplus.camera2.metadata.TOF_Value` vendor tag from the Camera2 API.
- Minimalistic UI showing the distance.

## How it works
The app opens a camera session in the background and listens to `TotalCaptureResult` metadata. It extracts the TOF (Time-of-Flight) value which the OnePlus camera HAL provides via vendor-specific metadata keys.

## Requirements
- OnePlus 8 Pro (specifically tested, might work on other OnePlus devices with LAF).
- Android 10 or higher.
- Camera permission.

## Development
Built using:
- Java
- Android Camera2 API
- Gradle

## License
MIT
