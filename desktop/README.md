# MalekPlay Ultra Share — Desktop Companion

A small Windows, Linux, and macOS companion for the Ultra Share v1 protocol. It can send files to an Android receiver and receive files from the MalekPlay Android app over a private local network.

## Requirements

- Python 3.10 or newer
- Both devices connected to the same Wi-Fi network, or the computer connected to the Android phone's personal hotspot
- The `cryptography` package listed in `requirements.txt`

## Install on Windows

Open PowerShell in this folder:

```powershell
py -3 -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install -r requirements.txt
```

If PowerShell blocks activation, use the environment's executable directly:

```powershell
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

Linux/macOS:

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
```

## Receive files on the computer

1. Open MalekPlay on Android and start **Ultra Share → Receive**.
2. Start the desktop receiver:

   ```bash
   python ultra_share_pc.py receive --output ./Received
   ```

3. Enter the pairing code shown by MalekPlay when prompted.
4. On Android, scan for nearby devices and select the computer if discovery is available. Otherwise enter the computer's private IPv4 address manually.

The desktop receiver listens on TCP port **38741** and advertises itself over UDP port **38742**. On Windows, allow Python through Windows Defender Firewall for **Private networks only**. Do not expose these ports to public networks.

## Send a file from the computer to Android

1. Start **Ultra Share → Receive** on the Android device and keep its pairing code visible.
2. On the computer, discover receivers:

   ```bash
   python ultra_share_pc.py discover
   ```

3. Send a file using the phone's displayed local IPv4 address:

   ```bash
   python ultra_share_pc.py send --host 192.168.1.25 --file "./video.mp4"
   ```

4. Enter the Android pairing code when prompted.

Use the actual private IP address displayed by the phone; the example address above is illustrative.

## Hotspot use

If there is no Wi-Fi router, enable the phone's personal hotspot, connect the computer to it, then start receiving in MalekPlay. The app advertises addresses on hotspot interfaces when Android exposes them. No internet connection is required.

## Security and transfer behavior

- Pairing uses the same **9-digit PIN** shown by the Android app. Both sides derive a 256-bit session secret with PBKDF2-HMAC-SHA256 (210,000 iterations) and a fresh challenge salt; HMAC-SHA256 proves possession without sending the PIN.
- Legacy 64-character hexadecimal pairing keys are still accepted by the desktop utility, but the Android UI currently displays 9-digit PINs.
- File metadata and content frames are protected with AES-256-GCM.
- SHA-256 verifies the completed file.
- Interrupted transfers retain a partial file and resume from the receiver's partial length when the same file is sent again.
- A verified-receipt marker prevents a duplicate copy if the final acknowledgement was lost.
- Existing destination files are preserved; a numbered filename is selected instead.
- The desktop client accepts private or link-local IPv4 addresses only.
- The pairing code is held in process memory and is not saved by this script.

This companion is for trusted devices on a private LAN. It does not use cloud storage or a third-party transfer server. Wi-Fi Direct group creation is not initiated by this desktop script; use a shared Wi-Fi network or the phone hotspot.
