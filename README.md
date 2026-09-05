# QwenHapticRead

An Android app that uses your phone's motion sensors and the Qwen AI model to generate personalized vibration (haptic) feedback, aimed at reducing motion sickness while reading on your phone in a moving vehicle. It reads real-time accelerometer and gyroscope data, sends it along with your comfort feedback to Qwen, and vibrates the phone according to the AI-generated strategy.

## Tech Stack

- **Language:** Kotlin
- **Platform:** Native Android (min SDK 26+)
- **AI Model:** Qwen, via Alibaba Cloud DashScope API
- **Sensors:** Android `SensorManager` — `TYPE_LINEAR_ACCELERATION`, `TYPE_GYROSCOPE`
- **Networking:** OkHttp

## Setup & Run Instructions

### Prerequisites
- Android Studio (latest stable version)
- An Android device (recommended) or emulator, API 26+
- An Alibaba Cloud DashScope (Qwen) API key

### Steps

1. **Clone the repository**
   ```bash
   git clone https://github.com/Abi-Jaish/QwenHapticRead.git
   cd QwenHapticRead
   ```

2. **Add your API key**

   The API key has been removed from this repository for security purposes. Generate your own key from Alibaba Cloud's DashScope console, then add it to your local `local.properties` file (this file is not committed to the repo):
   ```properties
   QWEN_API_KEY=your_api_key_here
   ```
   Reference this key wherever the API call is made in the code.

3. **Open in Android Studio**
   - `File > Open` → select the project's root folder
   - Wait for Gradle to sync (requires an internet connection)

4. **Run the app**
   - Connect a physical Android device with USB debugging enabled (recommended — real sensors and vibration), or start an emulator
   - Click **Run ▶**

   Once running, you'll get the app's mobile interface — a reading screen with two buttons, **"Doesn't feel good / Re-evaluate"** and **"Stop."**

## Testing the App

You can test the full sensing → AI → vibration loop directly on a physical device:

1. **Simulate motion:** Shake or move the phone in your hand — tilting, rotating, or moving it side to side — to simulate the kind of motion you'd feel in a moving vehicle (turning, braking, accelerating). The app's accelerometer and gyroscope pick up this real movement in the background.
2. **Tap "Re-evaluate":** This sends the motion data collected, along with your current comfort score, to the Qwen model. Qwen returns a summary describing the type of vibration to use and its intensity.
3. **Feel the vibration:** Based on Qwen's response, the phone vibrates at the intensity and pattern the AI decided on.
4. **Tap "Stop":** This immediately stops the vibration.
5. **Repeat:** Tapping "Re-evaluate" again restarts the same process — new motion data and comfort score are sent to Qwen, which returns a new (typically different) intensity and pattern, and the phone vibrates accordingly.

This lets you test how the app's vibration response changes as motion and comfort inputs change, entirely on-device.

## APK for Quick Testing

A pre-built **APK file is included in this repository**. You can download and install it directly onto an Android phone to test the app as a standalone mobile application, without needing to build it in Android Studio.

## Note on API Key Security

The Qwen/DashScope API key was previously present in this repository's source code and has since been **removed**. You must supply your own key locally (see Setup step 2 above) to build and run the app — no working key is included in this repo.
