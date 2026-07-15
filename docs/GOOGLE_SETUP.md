# Google Photos setup

The frame can play photos you pick from **Google Photos** (DESIGN §5.2). Because Google removed
the old read APIs, the only official path is the **Picker API**: you explicitly pick photos, the
frame downloads them, and to add more you pick again. This is a one-time ~10-minute setup in the
Google Cloud console, then a 30-second sign-in on the tablet.

> **Why a "Desktop" client and not "Android"?** Google deprecated the custom-URI-scheme and
> loopback redirects for the *Android* OAuth client type, and its replacement is the Play-Services
> SDK — which Fire OS doesn't have. The **Desktop** client type + a loopback (`127.0.0.1`) redirect
> is the only browser flow left that works with no Google Play Services *and* the Picker scope. See
> `docs/API_NOTES.md` for the gory details. Practical upshot: **no package name or SHA-1 needed.**

## One-time Google Cloud setup

1. **Create a project.** Go to <https://console.cloud.google.com/>, create a new project (any
   name, e.g. "Photo Frame").

2. **Enable the Picker API.** APIs & Services → Library → search **"Photos Picker API"** →
   **Enable**. (This is *not* the old "Photos Library API".)

3. **Configure the OAuth consent screen** (APIs & Services → OAuth consent screen):
   - User type: **External**.
   - Fill in the app name and your email where required.
   - **Add the scope** `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
   - **Publishing status: set to "In production".** ⚠️ Important — in "Testing" mode Google
     expires refresh tokens after **7 days**, so the frame would stop syncing weekly. Production
     status keeps the token alive indefinitely. You do **not** need Google to *verify* the app; the
     "unverified app" warning at sign-in is fine for personal use (you click through it once).

4. **Create the OAuth client** (APIs & Services → Credentials → Create credentials → OAuth client
   ID):
   - Application type: **Desktop app**.
   - Name it, Create.
   - Copy the **Client ID** (`…apps.googleusercontent.com`) and **Client secret**. (The "secret"
     isn't really secret for an installed app — it's just an identifier the token endpoint wants.)

## On the tablet

1. Open the frame → tap the screen → gear → **Sources** → **＋** → **Add Google Photos**.
2. Enter a name, paste the **Client ID** and **Client secret**, tap **Sign in with Google**.
3. The tablet browser (Silk) opens. Sign in, click through the **"Google hasn't verified this
   app"** warning (**Advanced → Go to … (unsafe)**), and grant access. You'll see "Signed in — you
   can close this tab." Return to the frame.
4. A **QR code** appears on the frame. **Scan it with your phone**, pick photos in Google Photos,
   and finish. The frame downloads them and starts showing them. Done.

## Adding more photos later

Sources → your Google source → **Add more photos**. It shows a fresh QR — scan, pick, done. No
sign-in needed (the frame refreshes your token silently). New picks **append** by default; toggle
**Replace existing photos** on the QR screen to swap the set instead.

## Notes & troubleshooting

- **Sign-in happens on the tablet, picking happens on the phone.** The loopback redirect must
  return to the same device that opened the browser, so the one-time grant is on the tablet. Every
  actual photo-pick is the nice phone-QR flow.
- **"Google didn't return a refresh token."** Your consent screen is still in *Testing*, or you'd
  already granted access before. Set publishing status to **In production** and sign in again (the
  frame forces a fresh consent so a refresh token is issued).
- **Picked photos persist.** Unlike Immich, Google picks are stored locally as the source of truth
  and are never auto-removed by sync or cache eviction — they stay until you **Replace** or delete
  the source.
- Videos are skipped (photos only).

## Google Photos auto-sync (Phase 5, unofficial)

The Picker requires you to re-pick to add photos. For true hands-free auto-sync from Google Photos,
Phase 5 adds a **shared-album watcher**: create a Google Photos **Live Album** (auto-adds photos of
chosen people/pets), get its "anyone with the link" share URL, and paste it into the frame. That
path needs no Cloud project at all. _(Documented here once Phase 5 lands.)_
