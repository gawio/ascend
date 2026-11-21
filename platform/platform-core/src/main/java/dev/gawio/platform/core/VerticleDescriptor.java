package dev.gawio.platform.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Builder;

import java.util.ArrayList;
import java.util.List;

@Builder
public record VerticleDescriptor(
        String name,
        String address,
        int instances,
        boolean isDeploy
) {

    public static List<VerticleDescriptor> parse(JsonArray array) {
        List<VerticleDescriptor> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.getJsonObject(i);
            String name = object.getString("name");
            if (name == null) {
                throw new IllegalArgumentException("verticle.json: missing name at index " + i);
            }
            String address = object.getString("address");
            if (address == null) {
                throw new IllegalArgumentException("verticle.json: missing address at index " + i);
            }
            int instances = getInteger(object.getInteger("instances"), 1);
            
            boolean isDeploy = getBoolean(object.getBoolean("isDeploy"), true);

            VerticleDescriptor.builder()
                    .name(name)
                    .address(address)
                    .instances(instances)
                    .isDeploy(isDeploy)
                    .build();
        }
        return list;
    }

    private static int getInteger(Integer key, int fallback) {
        return key == null ? fallback : key;
    }

    private static boolean getBoolean(Boolean key, boolean fallback) {
        return key == null ? fallback : key;
    }
}
