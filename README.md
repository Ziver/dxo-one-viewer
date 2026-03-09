# DxO One Viewer

A Java GUI application for changing the camera settings of a DXO-One camera connected through USB.

## Prerequisites

1.  **DxO One Camera**: Connected via a micro-USB cable.
2.  **autoexec.ash**: You must have an `autoexec.ash` file on your microSD card to enable USB control.
    - Content of `autoexec.ash`:
      ```bash
      # Enable USB control mode
      t usb enable
      ```
3.  **Java 11+**: Installed on your system (JRE or JDK).
4.  **libusb**: Ensure `libusb` is installed on your OS (e.g., `sudo apt install libusb-1.0-0` on Ubuntu).

## Windows Setup

To run this application natively on Windows, you must configure the correct USB drivers using **Zadig**:

1.  **Download Zadig**: Get it from [zadig.akeo.ie](https://zadig.akeo.ie/).
2.  **Connect Camera**: Plug in your DxO One and ensure it is powered on (with the `autoexec.ash` file on the SD card).
3.  **List Devices**: In Zadig, go to **Options** > **List All Devices**.
4.  **Select Device**: Look for **DxO One** (Vendor ID `2B8F`). 
    - If you see multiple interfaces (e.g., `Interface 0`, `Interface 1`), it is recommended to replace the driver for **both**, starting with `Interface 0`.
5.  **Install Driver**: Select **WinUSB** as the driver and click **Replace Driver**.
    - *Note: This will prevent Windows from seeing the camera as a standard mass-storage drive. To revert, uninstall the device from Device Manager.*

## Building and Running

### Build a Fat JAR (Recommended for Windows)
To create a single executable file that includes all dependencies:
1.  In your terminal, run:
    ```bash
    ./gradlew clean jar
    ```
2.  The JAR will be at `build/libs/dxo-one-viewer.jar`.
3.  Copy this file to your Windows machine and run:
    ```cmd
    java -jar dxo-one-viewer.jar
    ```

### Run with Gradle
If running directly on the machine where the source code is located:
- **Windows (PowerShell/Cmd)**: `.\gradlew.bat run`
- **Linux/macOS**: `./gradlew run`

## Prerequisites

- **Connect**: Scans for and connects to the DxO One.
- **Settings**: Adjust ISO, Aperture, and Exposure Time.
- **Capture**: Trigger the shutter remotely.

## Implementation Details

- **USB Communication**: Uses `usb4java` (libusb wrapper) for low-level USB access.
- **JSON-RPC**: Implements the DxO One's internal JSON-RPC protocol over USB.
- **GUI**: Built with Java Swing for maximum compatibility.

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** - see the [LICENSE](LICENSE) file for details.

## Author & Attribution

This project was developed by **Ziver Koc** with the assistance of **Gemini (AI)**. It is intended to provide a modern, cross-platform interface for the DxO One camera following its discontinuation.

## Reference

This project is based on the reverse-engineering work done by [jsyang/dxo1control](https://github.com/jsyang/dxo1control).
