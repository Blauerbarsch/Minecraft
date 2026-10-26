package dev.graverestore;

import org.bukkit.Server;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Turns an item from the grave log back into a real ItemStack.
 *
 * The grave plugin stored items with Paper's own JSON format
 * ({"DataVersion":…, "id":…, "count":…, "components":{…}, "schema_version":1}).
 * Paper reads that format back with UnsafeValues#deserializeItemFromJson – the exact counterpart,
 * so enchantments, damage, lore, plugin data and shulker contents come back unchanged.
 * Called via reflection so the plugin still loads on servers without that method (then /give is used).
 */
final class ItemJson {
    private ItemJson() {}

    private static Method parse, asObject, deserialize;
    private static Object unsafe;
    private static boolean unavailable;

    private static synchronized boolean init(Server server, Logger log) {
        if (deserialize != null) return true;
        if (unavailable) return false;
        try {
            unsafe = server.getClass().getMethod("getUnsafe").invoke(server);
            Class<?> parser = Class.forName("com.google.gson.JsonParser");
            Class<?> jsonObject = Class.forName("com.google.gson.JsonObject");
            parse = parser.getMethod("parseString", String.class);
            asObject = Class.forName("com.google.gson.JsonElement").getMethod("getAsJsonObject");
            deserialize = unsafe.getClass().getMethod("deserializeItemFromJson", jsonObject);
            return true;
        } catch (Throwable t) {
            unavailable = true;
            log.warning("Paper-Itemformat nicht verfügbar (" + t.getClass().getSimpleName() + ") – es wird /give benutzt.");
            return false;
        }
    }

    /** null if the item could not be rebuilt this way. */
    static ItemStack toItem(Server server, Map<String, Object> item, Logger log) {
        if (!init(server, log)) return null;
        try {
            Object json = asObject.invoke(parse.invoke(null, Json.write(item)));
            Object stack = deserialize.invoke(unsafe, json);
            return stack instanceof ItemStack is && !is.getType().isAir() ? is : null;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log.warning("Item " + item.get("id") + " ließ sich nicht direkt erzeugen: " + c.getMessage());
            return null;
        }
    }
}
