# MalekPlay Ultra Share — Desktop Companion

A small Windows, Linux, and macOS companion for the Ultra Share v1 protocol. It can send files to an Android receiver and receive files from the MalekPlay Android app over a private local network.

## Requirements

- Python 3.10 or newer
- Both devices connected to the same Wi-Fi network, or the computer connected to the Android phone's personal hotspot
- The `cryptography` package listed in `requirements.txt`

## Browser-based desktop console

Install the dependency as above, then start the dedicated browser companion:

```bash
python ultra_share_web.py --output ./Received
```

The terminal displays the computer's receive PIN. Open `http://127.0.0.1:8765` in a browser on that same computer. The page can discover a MalekPlay receiver on the local network, send selected files to the phone using its private IPv4 address and 9-digit PIN, and list/download files received from Android. The browser companion uses the existing authenticated AES-GCM transfer protocol and SHA-256 verification; it does not upload files to a cloud service.

The browser control page is intentionally bound to `127.0.0.1` and validates Host/Origin headers to resist DNS-rebinding and cross-origin requests. It is not intended to be opened from another device on the LAN. Allow the transfer port through the firewall only on private networks and keep the pairing PIN private.

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

## Browser interface on the computer

You can use a local browser page instead of the desktop command line:

1. Install the desktop companion requirements as shown above.
2. Start the browser companion from this folder:

   ```bash
   python ultra_share_web.py --output ./Received
   ```

3. Enter the 9-digit PIN shown by MalekPlay when prompted, then open **http://127.0.0.1:8765** on the same computer.
4. Use **البحث عن الهاتف على الشبكة** to discover a receiving Android phone, or enter its private IPv4 address manually. Choose a file and send it from the page.
5. Files sent from Android to the computer appear in the page's received-files list and can be downloaded through the browser.

The web control page binds to loopback only; it is not exposed as a public website or LAN file server. The pairing PIN is held in memory and sent only in a local request header. Android-to-computer transfers continue to use the encrypted Ultra Share protocol and are saved in the selected `Received` folder. Keep the terminal open while using the page.

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


## Ultra Share V2 batch transport and MPC2 control channel

The desktop companion now keeps the legacy V1 listener available while adding the Android V2 data plane and the separately authenticated MPC2 approval channel:

- V1 data: TCP **38741** (legacy per-file compatibility).
- V2 batch data: TCP **38742** (one encrypted manifest and multiple files per batch).
- MPC2 control: TCP **38744** (device request, explicit approval, and PING/PONG session heartbeat).
- Discovery remains UDP **38742**; using the same numeric port for UDP discovery and TCP V2 data is intentional because they are different transports.

Start receiving both data versions with:

```bash
python ultra_share_pc.py receive --output ./Received
```

When an Android device requests an MPC2 session, approve it in the desktop terminal with `y`. The data socket is accepted only after the control session is active and the V2 pairing proof succeeds. The V2 receiver stores resumable partial files and verified receipts below the hidden `.ultrashare-v2-state` directory; authenticated Android peer IDs are used to keep resume state stable when the peer's LAN address changes.

To send a V2 batch from the desktop to Android, start **Ultra Share → Receive** on the phone, then run:

```bash
python ultra_share_pc.py send-v2 --host 192.168.1.25 --file "./video.mp4" "./document.pdf"
```

Approve the incoming control request on Android. The desktop keeps the MPC2 heartbeat alive while the independent V2 data connection transfers and verifies the files. The Android app still enforces its own session approval; the V2 sender does not bypass that control channel.

The browser companion exposes separate **V1** and **V2** send actions. Its current V2 browser action submits selected files one at a time (each is a V2 batch); Android-to-desktop reception accepts a native multi-file V2 batch. Incoming desktop control requests are approved in the terminal running the browser companion.

Run the desktop protocol and compatibility tests from the repository root:

```bash
python -m pip install -r desktop/requirements.txt
python -m py_compile desktop/ultra_share_v2.py desktop/ultra_share_pc.py desktop/ultra_share_web.py
python -m unittest discover -s desktop -p "test_*.py" -v
```

The new branch-only GitHub Actions workflow runs the same compile and unittest checks on pushes to `feature/zapya-engineering-analysis`. A passing workflow and real Android/desktop device-to-device testing are still required before calling V2 fully verified.
