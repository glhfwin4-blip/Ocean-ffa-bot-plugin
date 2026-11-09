package fun.oceanffa.bridge;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class OceanBridge extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private HttpClient http;
    private ScheduledExecutorService scheduler;
    private String apiUrl, apiKey, serverId;

    @Override public void onEnable() {
        saveDefaultConfig();
        apiUrl = getConfig().getString("api-url", "").replaceAll("/$", "");
        apiKey = getConfig().getString("api-key", "");
        serverId = getConfig().getString("server-id", "ocean-ffa-1");
        if (apiUrl.isBlank() || apiKey.isBlank() || apiKey.equals("CHANGE-ME")) {
            getLogger().severe("Configure api-url and api-key in plugins/OceanBridge/config.yml");
        }
        http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("discord").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
            if (args.length != 2 || !args[0].equalsIgnoreCase("link")) { player.sendMessage("§bUsage: /discord link <code>"); return true; }
            verifyLink(player, args[1]); return true;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "OceanBridge-HTTP"); t.setDaemon(true); return t; });
        long seconds = Math.max(10, getConfig().getLong("heartbeat-seconds", 20));
        scheduler.scheduleAtFixedRate(this::heartbeat, 2, seconds, TimeUnit.SECONDS);
        getLogger().info("OceanBridge enabled. API: " + apiUrl);
    }

    @Override public void onDisable() { if (scheduler != null) scheduler.shutdownNow(); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p=event.getPlayer(); sendEvent("PLAYER_JOIN", Map.of("player", player(p)));
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player p=event.getPlayer(); sendEvent("PLAYER_QUIT", Map.of("player", player(p)));
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player victim=event.getEntity(); Player killer=victim.getKiller();
        if (killer == null) return;
        sendEvent("PLAYER_KILL", Map.of("killer",player(killer),"victim",player(victim)));
    }
    private Map<String,String> player(Player p) { return Map.of("uuid",p.getUniqueId().toString(),"username",p.getName()); }
    private void sendEvent(String type, Map<String,Object> data) {
        Map<String,Object> body=new java.util.HashMap<>(data);
        body.put("eventId", UUID.randomUUID().toString()); body.put("serverId",serverId); body.put("type",type); body.put("occurredAt",Instant.now().toString());
        post("/minecraft/events",body);
    }
    private void heartbeat() {
        post("/minecraft/heartbeat",Map.of("players",Bukkit.getOnlinePlayers().size(),"maxPlayers",Bukkit.getMaxPlayers(),"version",Bukkit.getMinecraftVersion()));
    }
    private void verifyLink(Player player,String code) {
        if (!code.matches("(?i)[A-Z0-9]{6}")) { player.sendMessage("§cInvalid code format."); return; }
        Map<String,Object> body=Map.of("code",code.toUpperCase(),"uuid",player.getUniqueId().toString(),"username",player.getName());
        postWithCallback("/minecraft/link/verify",body,ok -> Bukkit.getScheduler().runTask(this,()->player.sendMessage(ok?"§aYour Discord account has been linked!":"§cCould not link account. Check code and try again.")));
    }
    private void post(String path,Map<String,Object> body) { postWithCallback(path,body, ignored -> {}); }
    private void postWithCallback(String path,Map<String,Object> body,java.util.function.Consumer<Boolean> callback) {
        if (apiUrl.isBlank() || apiKey.isBlank() || apiKey.equals("CHANGE-ME")) { callback.accept(false); return; }
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(apiUrl+path)).timeout(java.time.Duration.ofSeconds(8))
                .header("Content-Type","application/json").header("X-API-Key",apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
            http.sendAsync(request,HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->callback.accept(error==null && response.statusCode()>=200 && response.statusCode()<300));
        } catch (Exception ex) { getLogger().warning("Could not send API request: "+ex.getMessage()); callback.accept(false); }
    }
}
