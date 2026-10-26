package dev.betledger;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** BetLedger dashboard. Immediate mode: every frame draws the page and re-registers the click zones. */
public class Gui extends Screen {
    static final String[] COLORS = {"E58CFF", "57C7FF", "4ADE80", "FBBF24", "F87171", "FFFFFF"};
    static final int DIM = 0xFF8C89B8, TEXT = 0xFFFFFFFF, GREEN = 0xFF2BE36B, RED = 0xFFFF4A3D, BORDER = 0xFF3A3A5C;

    record Hit(int x, int y, int w, int h, Runnable run) {}

    final List<Hit> hits = new ArrayList<>();
    GuiGraphicsExtractor g;
    String page = "home";
    int mx, my, ix, iw, bot;
    boolean listening; // waiting for a key to rebind the menu key

    Gui() { super(Component.literal("BetLedger")); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        g = graphics; mx = mouseX; my = mouseY; hits.clear();
        int pw = Math.min(width - 16, 460), ph = Math.min(height - 16, 260);
        int px = (width - pw) / 2, py = (height - ph) / 2;
        box(px, py, pw, ph, bg(), BORDER);
        ix = px + 12; iw = pw - 24; bot = py + ph - 20;
        if (page.equals("home")) home(py + 10); else sub(py + 10);

        // footer: promote the creator's discord
        String a = "made by " + BetLedger.cfg.creator + "  ·  ", d = "join the discord";
        int fx = px + (pw - font.width(a + d)) / 2, fy = py + ph - 13, dx = fx + font.width(a), dw = font.width(d);
        t(a, fx, fy, DIM);
        t(d, dx, fy, hover(dx, fy, dw, 9) ? TEXT : accent());
        hit(dx, fy, dw, 9, this::openDiscord);
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() == 0) for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (e.x() >= h.x() && e.x() < h.x() + h.w() && e.y() >= h.y() && e.y() < h.y() + h.h()) {
                h.run().run(); BetLedger.save(); return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override public boolean keyPressed(KeyEvent e) {
        if (listening) {
            listening = false;
            if (e.key() != InputConstants.KEY_ESCAPE) {
                BetLedger.guiKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(e.key()));
                KeyMapping.resetMapping();
                Minecraft.getInstance().options.save();
            }
            return true;
        }
        return super.keyPressed(e);
    }

    void openDiscord() {
        String u = BetLedger.cfg.discordUrl;
        if (u.contains("YOUR-INVITE")) BetLedger.say("Set your link: /betledger set discordUrl <invite link>");
        else try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(u)); }
        catch (Throwable t) { BetLedger.say("Open this link: " + u); } // no desktop/browser available
    }

    // ---------------- pages ----------------
    void home(int top) {
        var c = BetLedger.cfg;
        BetLedger.Session s = BetLedger.data.current;
        box(ix, top, iw, 24, panel(), BORDER);
        big("Bet", ix + 8, top + 7, 1.5f, TEXT);
        big("Ledger", ix + 8 + (int) (font.width("Bet") * 1.5f), top + 7, 1.5f, accent());
        int tx = ix + iw - 32;
        tog(tx, top + 7, c.tracking, () -> c.tracking = !c.tracking);
        t("tracking", tx - 6 - font.width("tracking"), top + 8, TEXT);

        int y0 = top + 30, bh = 40, by = bot - bh, mh = by - 6 - y0;
        int lw = iw * 3 / 5, rw = iw - lw - 6, rx = ix + lw + 6, rh = (mh - 12) / 3;

        // session net card + sparkline of cumulative net
        card(ix, y0, lw, mh, "SESSION NET", null);
        double n = s.net();
        big((n > 0 ? "+" : "") + BetLedger.fmt(n), ix + 8, y0 + 18, 2f, n > 0 ? GREEN : n < 0 ? RED : TEXT);
        long m = (System.currentTimeMillis() - s.start) / 60000;
        String ic = String.valueOf(s.inCount), oc = String.valueOf(s.outCount);
        t(ic, ix + 8, y0 + 38, GREEN);
        t("-", ix + 8 + font.width(ic), y0 + 38, DIM);
        t(oc, ix + 8 + font.width(ic + "-"), y0 + 38, RED);
        t(m / 60 + "h " + m % 60 + "m", ix + 16 + font.width(ic + "-" + oc), y0 + 38, DIM);
        String out = BetLedger.fmt(s.sent) + " out", in = BetLedger.fmt(s.received) + " in";
        int re = ix + lw - 8;
        t(out, re - font.width(out), y0 + 38, RED);
        t(in, re - font.width(out) - 8 - font.width(in), y0 + 38, GREEN);
        int cy0 = y0 + 52, cy1 = y0 + mh - 8, cw = lw - 16;
        if (s.curve.isEmpty()) {
            t("no payments yet", ix + (lw - font.width("no payments yet")) / 2, (cy0 + cy1) / 2, DIM);
        } else if (cy1 - cy0 > 12) {
            double mxv = 1;
            for (double v : s.curve) mxv = Math.max(mxv, Math.abs(v));
            int mid = (cy0 + cy1) / 2, hh = (cy1 - cy0) / 2;
            g.fill(ix + 8, mid, ix + 8 + cw, mid + 1, BORDER);
            for (int i = 0; i < cw; i++) {
                double v = s.curve.get(Math.min(s.curve.size() - 1, i * s.curve.size() / cw));
                int h = (int) Math.round(Math.abs(v) / mxv * hh);
                if (h == 0) continue;
                if (v >= 0) g.fill(ix + 8 + i, mid - h, ix + 9 + i, mid, GREEN);
                else g.fill(ix + 8 + i, mid + 1, ix + 9 + i, mid + 1 + h, RED);
            }
        }

        // right column
        card(rx, y0, rw, rh, "SESSIONS", () -> page = "sessions");
        t(c.tracking ? "running" : "paused", rx + 8, y0 + 19, c.tracking ? GREEN : RED);
        int ry = y0 + rh + 6;
        card(rx, ry, rw, rh, "PLAYERS", () -> page = "players");
        t(s.players.size() + " tracked", rx + 8, ry + 19, TEXT);
        ry += rh + 6;
        double owed = s.players.keySet().stream().mapToDouble(BetLedger::rake).sum();
        card(rx, ry, rw, rh, "RAKEBACK", () -> page = "players");
        t(owed > 0 ? BetLedger.fmt(owed) + " owed" : "nothing owed", rx + 8, ry + 19, owed > 0 ? TEXT : DIM);

        // bottom row
        int bw = (iw - 12) / 3;
        card(ix, by, bw, bh, "TOOLS", () -> page = "tools");
        t("timer, layout, labels", ix + 8, by + 20, DIM);
        card(ix + bw + 6, by, bw, bh, "LOOK", () -> page = "look");
        t("accent and opacity", ix + bw + 14, by + 20, DIM);
        g.fill(ix + 2 * bw - 18, by + 6, ix + 2 * bw - 4, by + 18, accent());
        card(ix + 2 * (bw + 6), by, bw, bh, "SETTINGS", () -> page = "settings");
        t("guard, rakeback, hud", ix + 2 * (bw + 6) + 8, by + 20, DIM);
    }

    void sub(int top) {
        btn(ix, top, 44, 16, "back", () -> page = "home");
        t(page.toUpperCase(), ix + 54, top + 4, TEXT);
        int y = top + 24;
        switch (page) {
            case "tools" -> tools(y);
            case "look" -> look(y);
            case "settings" -> settings(y);
            case "auction" -> auction(y);
            case "players" -> players(y);
            case "sessions" -> sessions(y);
            default -> page = "home";
        }
    }

    void tools(int y) {
        var c = BetLedger.cfg;
        int r = row(y, "Timer", "countdown on your hud");
        r = btnR(r, y + 5, c.timerEnd > System.currentTimeMillis() ? "stop" : "start",
            () -> c.timerEnd = c.timerEnd > System.currentTimeMillis() ? 0 : System.currentTimeMillis() + c.timerMinutes * 60000L);
        r = btnR(r, y + 5, "+", () -> c.timerMinutes = Math.min(180, c.timerMinutes + 1));
        r = valR(r, y + 5, c.timerMinutes + "m");
        btnR(r, y + 5, "-", () -> c.timerMinutes = Math.max(1, c.timerMinutes - 1));

        y += 27;
        r = row(y, "Top gamblers", "leaderboard rows on your hud");
        r = btnR(r, y + 5, "+", () -> c.hudTopPlayers = Math.min(8, c.hudTopPlayers + 1));
        r = valR(r, y + 5, String.valueOf(c.hudTopPlayers));
        btnR(r, y + 5, "-", () -> c.hudTopPlayers = Math.max(0, c.hudTopPlayers - 1));

        y += 27;
        r = row(y, "Text on hud", c.hudLabel.isEmpty() ? "/betledger set hudLabel <text>" : c.hudLabel);
        btnR(r, y + 5, "clear", () -> c.hudLabel = "");

        y += 27;
        r = row(y, "Edit hud layout", "move and resize the hud");
        r = btnR(r, y + 5, "+", () -> c.hudScale = Math.min(3, Math.round((c.hudScale + 0.1) * 10) / 10.0));
        r = valR(r, y + 5, c.hudScale + "x");
        r = btnR(r, y + 5, "-", () -> c.hudScale = Math.max(0.5, Math.round((c.hudScale - 0.1) * 10) / 10.0));
        r = btnR(r - 8, y + 5, "+", () -> c.hudY += 10);
        r = valR(r, y + 5, "y " + c.hudY);
        r = btnR(r, y + 5, "-", () -> c.hudY = Math.max(0, c.hudY - 10));
        r = btnR(r - 8, y + 5, "+", () -> c.hudX += 10);
        r = valR(r, y + 5, "x " + c.hudX);
        btnR(r, y + 5, "-", () -> c.hudX = Math.max(0, c.hudX - 10));

        y += 27;
        r = row(y, "Auction", "run a bidding round: payments you receive are bids");
        btnR(r, y + 5, "open", () -> page = "auction");
    }

    void look(int y) {
        var c = BetLedger.cfg;
        row(y, "Accent colour", "buttons, highlights, discord link");
        swatches(y, c.accent, v -> c.accent = v);
        y += 27;
        row(y, "Hud text colour", "colour of the stats on screen");
        swatches(y, c.hudColor, v -> c.hudColor = v);
        y += 27;
        int r = row(y, "Panel opacity", "how see-through this window is");
        r = btnR(r, y + 5, "+", () -> c.uiAlpha = Math.min(255, c.uiAlpha + 15));
        r = valR(r, y + 5, c.uiAlpha * 100 / 255 + "%");
        btnR(r, y + 5, "-", () -> c.uiAlpha = Math.max(60, c.uiAlpha - 15));
        y += 27;
        r = row(y, "Hud background", "dark box behind the hud");
        togR(r, y + 5, c.hudBackground, () -> c.hudBackground = !c.hudBackground);
    }

    void settings(int y) {
        var c = BetLedger.cfg;
        int r = row(y, "Large pay guard", "ask yes/no before big /pay");
        togR(r, y + 5, c.confirmLargePay, () -> c.confirmLargePay = !c.confirmLargePay);
        y += 27;
        r = row(y, "Guard threshold", "pays at or above this are held");
        r = btnR(r, y + 5, "+", () -> c.confirmThreshold = c.confirmThreshold * 2);
        r = valR(r, y + 5, BetLedger.fmt(c.confirmThreshold));
        btnR(r, y + 5, "-", () -> c.confirmThreshold = Math.max(1000, c.confirmThreshold / 2));
        y += 27;
        r = row(y, "Compact numbers", "1.5K instead of 1,500");
        togR(r, y + 5, c.compactNumbers, () -> c.compactNumbers = !c.compactNumbers);
        y += 27;
        r = row(y, "Hud visible", "show the live hud");
        togR(r, y + 5, c.hud, () -> c.hud = !c.hud);
        y += 27;
        r = row(y, "Hud rakeback line", "show rakeback owed on the hud");
        togR(r, y + 5, c.hudShowRakeback, () -> c.hudShowRakeback = !c.hudShowRakeback);
        y += 27;
        r = row(y, "Default rakeback", "percent for players with no override");
        r = btnR(r, y + 5, "+", () -> c.rakebackPercent = Math.min(100, c.rakebackPercent + 1));
        r = valR(r, y + 5, c.rakebackPercent + "%");
        btnR(r, y + 5, "-", () -> c.rakebackPercent = Math.max(0, c.rakebackPercent - 1));
        y += 27;
        r = row(y, "Rakeback base", "loss = their net loss, volume = all they sent");
        btnR(r, y + 5, c.rakebackBase, () -> c.rakebackBase = c.rakebackBase.equals("loss") ? "volume" : "loss");
        y += 27;
        r = row(y, "Menu keybind", "click, then press the key you want (Esc cancels)");
        btnR(r, y + 5, listening ? "press a key..." : BetLedger.guiKey.getTranslatedKeyMessage().getString(), () -> listening = true);
    }

    void auction(int y) {
        var c = BetLedger.cfg;
        long now = System.currentTimeMillis();
        boolean on = c.auctionEnd > now;
        int r = row(y, c.auctionItem + (on ? "  [LIVE " + (c.auctionEnd - now) / 1000 + "s]" : "  [idle]"),
            c.auctionLeader.isEmpty() ? "no bids yet" : c.auctionLeader + " leads at " + BetLedger.fmt(c.auctionBid));
        r = btnR(r, y + 5, "clear", () -> { c.auctionLeader = ""; c.auctionBid = 0; c.auctionEnd = 0; });
        btnR(r, y + 5, on ? "stop" : "start", () -> {
            if (on) c.auctionEnd = 0;
            else { c.auctionLeader = ""; c.auctionBid = 0; c.auctionEnd = System.currentTimeMillis() + c.auctionSeconds * 1000L; }
        });
        y += 27;
        r = row(y, "Duration", "how long bidding stays open");
        r = btnR(r, y + 5, "+", () -> c.auctionSeconds = Math.min(600, c.auctionSeconds + 15));
        r = valR(r, y + 5, c.auctionSeconds + "s");
        btnR(r, y + 5, "-", () -> c.auctionSeconds = Math.max(15, c.auctionSeconds - 15));
        y += 27;
        r = row(y, "Minimum bid", "smaller payments are ignored");
        r = btnR(r, y + 5, "+", () -> c.auctionMin = c.auctionMin < 1000 ? 1000 : c.auctionMin * 2);
        r = valR(r, y + 5, BetLedger.fmt(c.auctionMin));
        btnR(r, y + 5, "-", () -> c.auctionMin = c.auctionMin <= 1000 ? 0 : c.auctionMin / 2);
        y += 27;
        row(y, "Item", "/betledger set auctionItem <text>   (now: " + c.auctionItem + ")");
        y += 27;
        r = row(y, "Announce", "send the current standing to public chat");
        btnR(r, y + 5, "send", () -> {
            var p = Minecraft.getInstance().player;
            if (p != null) p.connection.sendChat("Auction " + c.auctionItem + ": "
                + (c.auctionLeader.isEmpty() ? "no bids yet" : c.auctionLeader + " leads at " + BetLedger.fmt(c.auctionBid)));
        });
    }

    void players(int y) {
        var c = BetLedger.cfg;
        List<Map.Entry<String, double[]>> ps = new ArrayList<>(BetLedger.data.current.players.entrySet());
        ps.sort((a, b) -> Double.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        if (ps.isEmpty()) { t("no players yet: payments show up here", ix, y + 4, DIM); return; }
        for (int i = 0; i < ps.size() && y + 26 <= bot; i++, y += 27) {
            String p = ps.get(i).getKey();
            double[] v = ps.get(i).getValue();
            int r = row(y, p, "net " + BetLedger.fmt(v[1] - v[0]) + "  owed " + BetLedger.fmt(BetLedger.rake(p)));
            r = btnR(r, y + 5, "+", () -> c.playerRakeback.put(p, Math.min(100, BetLedger.pct(p) + 1)));
            r = valR(r, y + 5, BetLedger.pct(p) + "%");
            btnR(r, y + 5, "-", () -> c.playerRakeback.put(p, Math.max(0, BetLedger.pct(p) - 1)));
        }
    }

    void sessions(int y) {
        BetLedger.Session s = BetLedger.data.current;
        int r = row(y, "Current: " + s.name, s.bets + " bets, net " + BetLedger.fmt(s.net()));
        r = btnR(r, y + 5, "reset", () -> BetLedger.data.current = new BetLedger.Session());
        r = btnR(r, y + 5, "stop + save", BetLedger::stop);
        btnR(r, y + 5, "new", () -> BetLedger.start("session " + (BetLedger.data.history.size() + 1)));
        y += 27;
        r = row(y, "Export", "write every session to a CSV in your config folder");
        btnR(r, y + 5, "export", () -> BetLedger.say("Exported " + BetLedger.export()));
        y += 27;
        List<BetLedger.Session> h = BetLedger.data.history;
        for (int i = h.size() - 1; i >= 0 && y + 26 <= bot; i--, y += 27) {
            BetLedger.Session x = h.get(i);
            row(y, x.name, "net " + BetLedger.fmt(x.net()) + " in " + x.bets + " bets");
        }
    }

    // ---------------- widgets ----------------
    /** Draws a settings row and returns the right edge where controls start. */
    int row(int y, String title, String desc) {
        box(ix, y, iw, 24, panel(), BORDER);
        t(title, ix + 8, y + 4, TEXT);
        t(desc, ix + 8, y + 14, DIM);
        return ix + iw - 8;
    }

    int btnR(int r, int y, String s, Runnable run) { int w = font.width(s) + 12; btn(r - w, y, w, 14, s, run); return r - w - 4; }
    int togR(int r, int y, boolean on, Runnable run) { tog(r - 24, y + 2, on, run); return r - 28; }
    int valR(int r, int y, String s) { int w = font.width(s); t(s, r - w, y + 3, TEXT); return r - w - 6; }

    void swatches(int y, String current, java.util.function.Consumer<String> set) {
        int r = ix + iw - 8;
        for (int i = COLORS.length - 1; i >= 0; i--) {
            String col = COLORS[i];
            r -= 14;
            box(r, y + 5, 12, 14, rgb(col), col.equalsIgnoreCase(current) ? TEXT : BORDER);
            hit(r, y + 5, 12, 14, () -> set.accept(col));
            r -= 4;
        }
    }

    void card(int x, int y, int w, int h, String title, Runnable run) {
        boolean hv = run != null && hover(x, y, w, h);
        box(x, y, w, h, panel(), hv ? accent() : BORDER);
        t(title, x + 8, y + 7, DIM);
        if (run != null) hit(x, y, w, h, run);
    }

    void btn(int x, int y, int w, int h, String s, Runnable run) {
        boolean hv = hover(x, y, w, h);
        box(x, y, w, h, hv ? 0xFF26263C : panel(), hv ? accent() : BORDER);
        t(s, x + (w - font.width(s)) / 2, y + (h - 8) / 2 + 1, TEXT);
        hit(x, y, w, h, run);
    }

    void tog(int x, int y, boolean on, Runnable run) {
        box(x, y, 24, 10, on ? 0xFF14532D : 0xFF2A2A3E, on ? GREEN : BORDER);
        int kx = on ? x + 14 : x + 2;
        g.fill(kx, y + 2, kx + 8, y + 8, on ? GREEN : DIM);
        hit(x, y, 24, 10, run);
    }

    /** Filled box with 1px rounded corners (corner pixels left empty). */
    void box(int x, int y, int w, int h, int fill, int border) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, border);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        g.fill(x, y + 1, x + 1, y + h - 1, border);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
    }

    void t(String s, int x, int y, int c) { g.text(font, s, x, y, c, false); }

    void big(String s, int x, int y, float sc, int c) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(sc, sc);
        g.text(font, s, 0, 0, c, false);
        g.pose().popMatrix();
    }

    void hit(int x, int y, int w, int h, Runnable run) { hits.add(new Hit(x, y, w, h, run)); }
    boolean hover(int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    int accent() { return rgb(BetLedger.cfg.accent); }
    int bg() { return (Math.max(0, Math.min(255, BetLedger.cfg.uiAlpha)) << 24) | 0x0B0B14; }
    int panel() { return (Math.max(0, Math.min(255, BetLedger.cfg.uiAlpha)) << 24) | 0x13131F; }

    static int rgb(String hex) {
        try { return 0xFF000000 | (int) Long.parseLong(hex.replace("#", ""), 16); } catch (Exception e) { return 0xFFFFFFFF; }
    }
}
