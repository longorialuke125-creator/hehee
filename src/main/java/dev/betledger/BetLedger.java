package dev.betledger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BetLedger: client-side /pay gambling tracker. Everything lives in this one file. */
public class BetLedger implements ClientModInitializer {
    // ---- config (every public field is editable in-game with /betledger set <field> <value>) ----
    public static class Config {
        public boolean hud = true, tracking = true;
        public String accent = "E58CFF";      // RRGGBB
        public int uiAlpha = 215;               // window opacity 60-255
        public String creator = "YOUR NAME";     // shown in the window footer
        public String discordUrl = "https://discord.gg/YOUR-INVITE";
        public String hudLabel = "";
        public int timerMinutes = 5;
        public long timerEnd = 0;
        public String auctionItem = "item", auctionLeader = "";
        public int auctionSeconds = 60;
        public double auctionMin = 0, auctionBid = 0;
        public long auctionEnd = 0;
        public int hudX = 6, hudY = 6;
        public double hudScale = 1.0;
        public String hudColor = "FFFFFF";      // text colour, RRGGBB
        public boolean hudBackground = true;
        public int hudTopPlayers = 3;           // 0 = hide player rows
        public boolean hudShowRakeback = true;
        public boolean compactNumbers = true;   // 1.5K instead of 1500
        public boolean confirmLargePay = true;
        public double confirmThreshold = 1_000_000;
        public double rakebackPercent = 5;      // default rakeback %
        public String rakebackBase = "loss";    // "loss" = player's net loss, "volume" = everything they sent you
        public Map<String, Double> playerRakeback = new HashMap<>(); // per-player % override
        public String receivedRegex = "(\\w{3,16}) (?:paid|sent) you \\$?([\\d,.]+\\s?[kKmMbB]?)";
        public String sentRegex = "You (?:paid|sent) (\\w{3,16}) \\$?([\\d,.]+\\s?[kKmMbB]?)";
    }

    public static class Session {
        public String name = "session";
        public long start = System.currentTimeMillis(), end = 0;
        public double sent, received;
        public int bets, inCount, outCount;
        public List<Double> curve = new ArrayList<>(); // cumulative net after each payment
        public Map<String, double[]> players = new HashMap<>(); // name -> {sent to them, received from them}
        double net() { return received - sent; }
    }

    public static class Data { public Session current = new Session(); public List<Session> history = new ArrayList<>(); }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();
    static Data data = new Data();
    static Path cfgFile, dataFile;
    static String pending; // /pay command waiting for yes/no
    static boolean bypass, openGui;
    static KeyMapping guiKey;

    @Override public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        cfgFile = dir.resolve("betledger.json");
        dataFile = dir.resolve("betledger-sessions.json");
        cfg = load(cfgFile, Config.class, cfg);
        data = load(dataFile, Data.class, data);
        save();

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) parse(msg.getString()); });

        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            if (bypass || !cfg.confirmLargePay) return true;
            Matcher m = Pattern.compile("^pay\\s+(\\S+)\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(cmd);
            if (!m.find()) return true;
            double a;
            try { a = amt(m.group(2)); } catch (Exception e) { return true; }
            if (a < cfg.confirmThreshold) return true;
            pending = cmd;
            say("Large pay: " + fmt(a) + " to " + m.group(1) + ". Type /betledger yes or /betledger no");
            return false;
        });

        KeyMapping key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.betledger.toggle", InputConstants.KEY_RBRACKET, KeyMapping.Category.MISC));
        guiKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.betledger.gui", InputConstants.KEY_RSHIFT, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            while (key.consumeClick()) { cfg.hud = !cfg.hud; save(); }
            while (guiKey.consumeClick()) openGui = true;
            if (openGui && c.screen == null) { openGui = false; c.setScreen(new Gui()); }
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("betledger", "hud"), (g, tick) -> {
                Minecraft mc = Minecraft.getInstance();
                if (!cfg.hud || mc.options.hideGui) return;
                List<String> lines = lines();
                int w = 0;
                for (String l : lines) w = Math.max(w, mc.font.width(l));
                int color = 0xFF000000 | (int) Long.parseLong(cfg.hudColor.replace("#", ""), 16);
                g.pose().pushMatrix();
                g.pose().translate(cfg.hudX, cfg.hudY);
                g.pose().scale((float) cfg.hudScale, (float) cfg.hudScale);
                if (cfg.hudBackground) g.fill(-2, -2, w + 2, lines.size() * 10 + 1, 0x90000000);
                for (int i = 0; i < lines.size(); i++) g.text(mc.font, lines.get(i), 0, i * 10, color, true);
                g.pose().popMatrix();
            });

        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            var root = ClientCommands.literal("betledger");
            root.executes(c -> { lines().forEach(BetLedger::say); return 1; });
            root.then(ClientCommands.literal("toggle").executes(c -> { cfg.hud = !cfg.hud; save(); return 1; }));
            root.then(ClientCommands.literal("start").executes(c -> start("session"))
                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                    .executes(c -> start(StringArgumentType.getString(c, "name")))));
            root.then(ClientCommands.literal("stop").executes(c -> { stop(); say("Session saved."); return 1; }));
            root.then(ClientCommands.literal("reset").executes(c -> { data.current = new Session(); save(); say("Session reset."); return 1; }));
            root.then(ClientCommands.literal("players").executes(c -> { playerLines(99).forEach(BetLedger::say); return 1; }));
            root.then(ClientCommands.literal("history").executes(c -> {
                for (Session s : data.history) say(s.name + ": " + fmt(s.net()) + " in " + s.bets + " bets");
                return 1; }));
            root.then(ClientCommands.literal("export").executes(c -> { say("Exported " + export()); return 1; }));
            root.then(ClientCommands.literal("yes").executes(c -> {
                if (pending == null) { say("Nothing pending."); return 0; }
                bypass = true;
                try { Minecraft.getInstance().player.connection.sendCommand(pending); } finally { bypass = false; pending = null; }
                return 1; }));
            root.then(ClientCommands.literal("no").executes(c -> { pending = null; say("Cancelled."); return 1; }));
            root.then(ClientCommands.literal("rake")
                .then(ClientCommands.argument("player", StringArgumentType.word())
                    .executes(c -> { String p = StringArgumentType.getString(c, "player"); say(p + ": owed " + fmt(rake(p)) + " at " + pct(p) + "%"); return 1; })
                    .then(ClientCommands.argument("percent", StringArgumentType.word()).executes(c -> {
                        cfg.playerRakeback.put(StringArgumentType.getString(c, "player"), Double.parseDouble(StringArgumentType.getString(c, "percent")));
                        save(); say("Saved."); return 1; }))));
            root.then(ClientCommands.literal("set")
                .then(ClientCommands.argument("field", StringArgumentType.word())
                    .then(ClientCommands.argument("value", StringArgumentType.greedyString()).executes(c -> set(
                        StringArgumentType.getString(c, "field"), StringArgumentType.getString(c, "value"))))));
            root.then(ClientCommands.literal("get").executes(c -> { for (Field f : Config.class.getFields()) try { say(f.getName() + " = " + f.get(cfg)); } catch (Exception ignored) {} return 1; }));
            root.then(ClientCommands.literal("gui").executes(c -> { openGui = true; return 1; }));
            d.register(root);
        });
    }

    // ---- parsing ----
    static void parse(String text) {
        if (!cfg.tracking) return;
        try {
            Matcher m = Pattern.compile(cfg.sentRegex).matcher(text);
            if (m.find()) { record(m.group(1), amt(m.group(2)), true); return; }
            m = Pattern.compile(cfg.receivedRegex).matcher(text);
            if (m.find()) record(m.group(1), amt(m.group(2)), false);
        } catch (Exception ignored) { /* bad user regex or number: skip the message */ }
    }

    static void record(String player, double a, boolean sent) {
        Session s = data.current;
        double[] p = s.players.computeIfAbsent(player, k -> new double[2]);
        if (sent) { s.sent += a; p[0] += a; } else { s.received += a; p[1] += a; }
        s.bets++;
        if (!sent && cfg.auctionEnd > System.currentTimeMillis() && a >= cfg.auctionMin && a > cfg.auctionBid) { cfg.auctionBid = a; cfg.auctionLeader = player; } // payments received during a round are bids
        if (sent) s.outCount++; else s.inCount++;
        s.curve.add(s.net());
        if (s.curve.size() > 400) s.curve.remove(0);
        save();
    }

    static double amt(String s) {
        s = s.replace(",", "").replace("$", "").trim();
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        double mult = 1;
        if ("kmb".indexOf(c) >= 0) { mult = c == 'k' ? 1e3 : c == 'm' ? 1e6 : 1e9; s = s.substring(0, s.length() - 1).trim(); }
        return Double.parseDouble(s) * mult;
    }

    // ---- rakeback ----
    static double pct(String p) { return cfg.playerRakeback.getOrDefault(p, cfg.rakebackPercent); }

    static double rake(String p) {
        double[] v = data.current.players.getOrDefault(p, new double[2]);
        double base = cfg.rakebackBase.equals("volume") ? v[1] : Math.max(0, v[1] - v[0]);
        return base * pct(p) / 100;
    }

    // ---- display ----
    static List<String> lines() {
        Session s = data.current;
        List<String> l = new ArrayList<>();
        l.add(s.name + "  " + (System.currentTimeMillis() - s.start) / 60000 + "m  " + s.bets + " bets");
        l.add("In " + fmt(s.received) + "  Out " + fmt(s.sent));
        l.add("Net " + (s.net() >= 0 ? "+" : "") + fmt(s.net()));
        if (cfg.timerEnd > System.currentTimeMillis()) { long r = (cfg.timerEnd - System.currentTimeMillis()) / 1000; l.add(String.format("Timer %d:%02d", r / 60, r % 60)); }
        if (!cfg.hudLabel.isEmpty()) l.add(cfg.hudLabel);
        if (cfg.auctionEnd > System.currentTimeMillis()) l.add("Auction " + cfg.auctionItem + ": " + (cfg.auctionLeader.isEmpty() ? "no bids" : cfg.auctionLeader + " " + fmt(cfg.auctionBid)) + " " + (cfg.auctionEnd - System.currentTimeMillis()) / 1000 + "s");
        if (cfg.hudShowRakeback) l.add("Rakeback owed " + fmt(s.players.keySet().stream().mapToDouble(BetLedger::rake).sum()));
        l.addAll(playerLines(cfg.hudTopPlayers));
        return l;
    }

    static List<String> playerLines(int n) {
        List<String> out = new ArrayList<>();
        s().players.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue()[1] + b.getValue()[0], a.getValue()[1] + a.getValue()[0]))
            .limit(n).forEach(e -> out.add(e.getKey() + ": net " + fmt(e.getValue()[1] - e.getValue()[0]) + " rake " + fmt(rake(e.getKey()))));
        return out;
    }

    static Session s() { return data.current; }

    static String fmt(double d) {
        if (!cfg.compactNumbers) return String.format("%,.0f", d);
        double a = Math.abs(d);
        if (a >= 1e9) return String.format("%.2fB", d / 1e9);
        if (a >= 1e6) return String.format("%.2fM", d / 1e6);
        if (a >= 1e3) return String.format("%.1fK", d / 1e3);
        return String.format("%.0f", d);
    }

    // ---- sessions / io ----
    static int start(String name) { stop(); data.current.name = name; save(); say("Started " + name); return 1; }

    static void stop() {
        if (data.current.bets > 0) { data.current.end = System.currentTimeMillis(); data.history.add(data.current); }
        data.current = new Session();
        save();
    }

    static String export() {
        Path out = cfgFile.resolveSibling("betledger-export.csv");
        StringBuilder sb = new StringBuilder("session,player,sent,received,net\n");
        List<Session> all = new ArrayList<>(data.history);
        all.add(data.current);
        for (Session x : all) x.players.forEach((p, v) -> sb.append(x.name).append(',').append(p).append(',').append(v[0]).append(',').append(v[1]).append(',').append(v[1] - v[0]).append('\n'));
        try { Files.writeString(out, sb); } catch (IOException e) { return "failed: " + e.getMessage(); }
        return out.toString();
    }

    static int set(String field, String value) {
        try {
            Field f = Config.class.getField(field);
            Class<?> t = f.getType();
            if (t == boolean.class) f.setBoolean(cfg, Boolean.parseBoolean(value));
            else if (t == int.class) f.setInt(cfg, Integer.parseInt(value));
            else if (t == double.class) f.setDouble(cfg, Double.parseDouble(value));
            else if (t == String.class) f.set(cfg, value);
            else { say("Not settable here (edit config/betledger.json)."); return 0; }
            save(); say(field + " = " + value); return 1;
        } catch (Exception e) { say("Unknown or invalid: " + field + " (see /betledger get)"); return 0; }
    }

    static <T> T load(Path p, Class<T> c, T fallback) {
        try { if (Files.exists(p)) return GSON.fromJson(Files.readString(p), c); } catch (Exception ignored) {}
        return fallback;
    }

    static void save() {
        try { Files.writeString(cfgFile, GSON.toJson(cfg)); Files.writeString(dataFile, GSON.toJson(data)); } catch (IOException ignored) {}
    }

    static void say(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[BetLedger] " + s));
    }
}
