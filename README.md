# Phone MCP — let Claude control your Android phone

An Android app that runs an **MCP server on the phone itself**. Claude (Claude Code,
Claude Desktop, or any MCP client) connects to it over your network and can see the
screen, read the UI, tap, swipe, type, press system buttons and launch apps.

No root, no ADB and no PC-side bridge are needed. Everything goes through Android's
**AccessibilityService** API.

```
┌──────────────┐   HTTP POST /mcp (JSON-RPC)    ┌──────────────────────────────────┐
│ Claude Code  │ ─────────────────────────────▶ │ Phone MCP app                    │
│ (MCP client) │   Authorization: Bearer <tok>  │  ├─ McpHttpServer (port 8765)    │
└──────────────┘ ◀───────────────────────────── │  ├─ ToolRegistry (MCP tools)     │
                    screenshots, UI tree, ...   │  └─ PhoneControlService          │
                                                │      (AccessibilityService:      │
                                                │       gestures, screenshots,     │
                                                │       window content, actions)   │
                                                └──────────────────────────────────┘
```

## Requirements

- Android 11 (API 30) or newer. API 30 is needed for accessibility screenshots.
- The phone and the computer running Claude must reach each other: the same Wi-Fi,
  Tailscale/VPN, or USB with `adb forward`.

## Install and set up

1. Build the APK (see [Building](#building)) and install it:
   `adb install app/build/outputs/apk/debug/app-debug.apk`. You can also copy the APK
   to the phone and open it there.
2. Open **Phone MCP** and allow notifications. While the server runs, an ongoing
   notification shows that the phone can be controlled remotely.
3. Tap **Open Accessibility Settings**, then go to *Installed apps* → **Phone MCP** and
   turn it on. Starting the service also starts the MCP server.
   - **Android 13+ and the "Restricted setting" dialog:** if you sideloaded the APK
     from a browser or file manager, the toggle is greyed out. Go to *Settings → Apps →
     Phone MCP → ⋮ (top right) → Allow restricted settings*, then try again. Installing
     with `adb install` avoids this.
4. Optional, for `get_notifications`: tap **Grant notification access** and turn it on
   for Phone MCP. The same "Allow restricted settings" step can apply.
5. Optional, for `unlock`: tap **Set unlock PIN or password** and enter the PIN or
   password you use on the lock screen. Patterns and fingerprints can't be used.
6. Back in the app, tap **Copy Claude Code command** and run it on your computer:

   ```bash
   claude mcp add --transport http phone http://192.168.1.23:8765/mcp \
     --header "Authorization: Bearer <token shown in the app>"
   ```

7. In Claude Code, ask for something like *"open the weather app on my phone and tell
   me tomorrow's forecast"*.

To stop remote control, turn the accessibility service off or tap
**Stop server** in the app.

### Connecting over USB instead of Wi-Fi

```bash
adb forward tcp:8765 tcp:8765
claude mcp add --transport http phone http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

## MCP tools

The coordinates in every tool use the **screenshot's pixel space**. The screen is
scaled so that its long edge is at most 1280 px, and the same scale applies to
screenshots, UI-tree bounds and gesture inputs. Claude can therefore tap exactly what
it sees, and the image does not get resized again on the API side.

| Tool | What it does |
|---|---|
| `screenshot` | Captures the screen and returns a JPEG image along with the current app and the screen size. Adds a warning if the screen is off or locked. |
| `get_ui_tree` | Returns the accessibility tree for every window as compact text: numbered elements with text, content description, view ID, bounds, centre point and flags. Accepts `filter` (substring search) and `interactive_only`. |
| `tap_element` | Clicks an element by its number from the last `get_ui_tree` call. It uses the accessibility click action and falls back to tapping the element's centre. |
| `tap` | Taps at (x, y). |
| `double_tap` | Double-taps at (x, y). |
| `long_press` | Long-presses at (x, y), with an optional duration. |
| `swipe` | Swipes from (x1, y1) to (x2, y2) over a given duration. |
| `scroll` | Scrolls `up`, `down`, `left` or `right`. You give the direction of the content you want to see, and the tool works out the finger direction. |
| `type_text` | Types into the focused input field at the cursor. Accepts `clear` (replace the existing text) and `submit` (press the IME action key, such as Enter or Search). Returns an error if the field doesn't end up containing the text. |
| `press_key` | Presses `back`, `home`, `recents`, `notifications`, `quick_settings`, `power_dialog`, `lock_screen` or `take_screenshot`. |
| `list_apps` | Lists launchable apps (label and package name). Accepts an optional filter. |
| `launch_app` | Launches an app by package name or label. Returns an error while the phone is locked. |
| `open_uri` | Opens a URI with `ACTION_VIEW`, such as `https://…`, `tel:…`, `geo:…`, `mailto:…` or a deep link. Returns an error while the phone is locked. |
| `get_notifications` | Reads active notifications (app, time, title, text), newest first. Accepts `app` (filter) and `include_ongoing`. Needs notification access and an unlocked phone. |
| `get_screen_info` | Returns the screen size, scale, rotation, foreground app, whether the screen is on and whether the device is locked. |
| `wake_screen` | Turns the screen on. |
| `unlock` | Wakes the phone, opens the PIN pad and enters the PIN or password saved in the app. Makes one attempt per call. After a failed attempt, remote unlock pauses until you save the PIN again in the app. |
| `wait` | Waits up to 10 s, for example while an app loads. |

## Security

This app gives **full control of your phone** to anyone who has the token and can
reach the port. Keep that in mind:

- Every request needs `Authorization: Bearer <token>`. The token is 256-bit random
  and is compared in constant time. You can regenerate it in the app at any time.
- Traffic is **plain HTTP**. On untrusted Wi-Fi, use USB (`adb forward`) or a
  WireGuard/Tailscale tunnel instead.
- An ongoing notification is visible whenever the server is listening.
- `get_notifications` is off until you grant notification access, and it refuses to
  run while the phone is locked. `get_ui_tree` can still see what the lock screen
  itself displays.
- **Remote unlock is off until you save a PIN or password in the app.** Once it's on,
  the token is as powerful as your PIN: anyone with the token who can reach the port can
  unlock the phone and use any app. Use a fresh token and a private network (USB,
  Tailscale) if you turn it on.
  - The PIN is encrypted with an Android Keystore key and never leaves the phone. The
    `unlock` tool takes no input and never returns the PIN.
  - Each call makes at most one attempt. One wrong attempt pauses remote unlock until you
    save the PIN again, so the tool can't trigger lockouts or wipe protection.
  - After a reboot, the phone has to be unlocked by hand once, because the app can't run
    before the first unlock.
- Android's own limits still apply. Patterns and biometrics can't be entered remotely. Windows marked `FLAG_SECURE` (banking apps, some password fields)
  show up black in screenshots. Hardware volume and power keys can't be pressed.

## Building

Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Code map

| File | Role |
|---|---|
| `PhoneControlService.kt` | The AccessibilityService. It owns the server's lifecycle and the "active" notification. |
| `DeviceController.kt` | All device actions: gestures, screenshots, UI tree, text input, global actions and apps. |
| `ToolRegistry.kt` | MCP tool definitions (JSON Schemas) and their handlers. |
| `McpHttpServer.kt` | MCP Streamable HTTP transport (JSON responses), JSON-RPC dispatch and auth. |
| `UnlockSecret.kt` | Keystore-encrypted storage for the unlock PIN or password, plus the pause-after-failure flag. |
| `UnlockActivity.kt` | Invisible activity that turns the screen on and asks the system to show the PIN pad. |
| `NotificationReader.kt` | NotificationListenerService that gives access to active notifications. |
| `AppSettings.kt` | Token and port storage, plus local IP discovery. |
| `MainActivity.kt` | Setup screen: status, endpoint, token and the copyable `claude mcp add` command. |

## Smoke test

Once the service is enabled on the phone, run this from your computer:

```bash
scripts/smoke_test.py http://PHONE_IP:8765/mcp TOKEN --screenshot shot.jpg
```

It runs the MCP handshake, lists the tools, prints the screen info and saves a screenshot.
