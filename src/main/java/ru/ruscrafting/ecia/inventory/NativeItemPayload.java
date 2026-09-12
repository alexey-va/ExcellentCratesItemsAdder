package ru.ruscrafting.ecia.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bukkit.inventory.ItemStack;
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec;
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Native Paper bytes retain ItemsAdder, EC and voucher persistent metadata. */
public final class NativeItemPayload {
    private static final int MAX_PAYLOAD = 2_000_000;
    private final PaperItemStackBinaryCodec items = NativePaperItemStackBinaryCodec.INSTANCE;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public List<String> capture(ItemStack[] items) {
        return Arrays.stream(items).map(item -> item == null || item.isEmpty() ? ""
                : Base64.getEncoder().encodeToString(this.items.encodeItem(item))).toList();
    }

    public ItemStack[] restore(List<String> payload) {
        return payload.stream().map(value -> value.isEmpty() ? null
                : items.decodeItem(Base64.getDecoder().decode(value))).toArray(ItemStack[]::new);
    }

    public String write(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.length() > MAX_PAYLOAD) throw new IllegalArgumentException("Oversized native item payload");
            return encoded;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode native item payload", e);
        }
    }

    public <T> T read(String value, Class<T> type) {
        if (value.length() > MAX_PAYLOAD) throw new IllegalArgumentException("Oversized native item payload");
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot decode native item payload", e);
        }
    }

    public String items(ItemStack[] items) { return write(capture(items)); }

    public ItemStack[] items(String payload) {
        return restore(List.of(read(payload, String[].class)));
    }
}
