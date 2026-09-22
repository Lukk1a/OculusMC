package dev.lukka.oculus;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;

public class WorldMapRenderer {

    private static final Map<String, byte[]> OVERVIEW_CACHE = new ConcurrentHashMap<>();

    public static void clearCache() {
        OVERVIEW_CACHE.clear();
    }

    public static File getRegionDirectory(String worldName) {
        String lower = worldName != null ? worldName.toLowerCase() : "world";
        File dimDir;
        if (lower.contains("nether")) {
            dimDir = new File("world/dimensions/minecraft/the_nether/region");
        } else if (lower.contains("end")) {
            dimDir = new File("world/dimensions/minecraft/the_end/region");
        } else {
            dimDir = new File("world/dimensions/minecraft/overworld/region");
        }

        if (dimDir.exists() && dimDir.isDirectory()) {
            return dimDir;
        }

        File legacy = new File(lower, "region");
        if (legacy.exists() && legacy.isDirectory()) {
            return legacy;
        }

        File standard = new File("world/region");
        if (standard.exists() && standard.isDirectory()) {
            return standard;
        }

        return dimDir;
    }

    public static byte[] getOverviewMapPng(String worldName, boolean forceRefresh) {
        String key = worldName != null ? worldName : "world";
        if (!forceRefresh && OVERVIEW_CACHE.containsKey(key)) {
            return OVERVIEW_CACHE.get(key);
        }

        int size = 1024;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);

        // Fill background with dark void color
        int bg = key.contains("nether") ? 0xFF140808 : (key.contains("end") ? 0xFF0B0A14 : 0xFF08090A);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, bg);
            }
        }

        File regionDir = getRegionDirectory(key);
        if (regionDir.exists() && regionDir.isDirectory()) {
            renderRegion(new File(regionDir, "r.-1.-1.mca"), img, 0, 0, key);
            renderRegion(new File(regionDir, "r.0.-1.mca"), img, 512, 0, key);
            renderRegion(new File(regionDir, "r.-1.0.mca"), img, 0, 512, key);
            renderRegion(new File(regionDir, "r.0.0.mca"), img, 512, 512, key);
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            byte[] bytes = baos.toByteArray();
            OVERVIEW_CACHE.put(key, bytes);
            return bytes;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static Map<String, Object> getMapMeta(String worldName) {
        Map<String, Object> meta = new HashMap<>();
        String key = worldName != null ? worldName : "world";
        meta.put("world", key);
        meta.put("width", 1024);
        meta.put("height", 1024);

        Map<String, Integer> bounds = new HashMap<>();
        bounds.put("minX", -512);
        bounds.put("maxX", 512);
        bounds.put("minZ", -512);
        bounds.put("maxZ", 512);
        meta.put("bounds", bounds);

        File regionDir = getRegionDirectory(key);
        List<String> found = new ArrayList<>();
        if (regionDir.exists() && regionDir.isDirectory()) {
            File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
            if (files != null) {
                for (File f : files) found.add(f.getName());
            }
        }
        meta.put("regionsAvailable", found);
        return meta;
    }

    public static int getBlockColor(String name, String dimension) {
        if (name == null) return 0xFF141517;
        String n = name.toLowerCase();

        if (dimension.contains("nether")) {
            if (n.contains("lava")) return 0xFFE04000;
            if (n.contains("crimson_nylium") || n.contains("crimson_stem")) return 0xFF8A1E24;
            if (n.contains("warped_nylium") || n.contains("warped_stem")) return 0xFF2A6E66;
            if (n.contains("soul_sand") || n.contains("soul_soil")) return 0xFF4D3B31;
            if (n.contains("basalt") || n.contains("blackstone")) return 0xFF424247;
            if (n.contains("glowstone")) return 0xFFC9AD60;
            if (n.contains("nether_bricks")) return 0xFF40181D;
            return 0xFF6D2427; // default netherrack red
        }

        if (dimension.contains("end")) {
            if (n.contains("obsidian")) return 0xFF120E1E;
            if (n.contains("purpur")) return 0xFFA97DA9;
            if (n.contains("chorus")) return 0xFF614061;
            if (n.contains("end_rod")) return 0xFFFFFFFF;
            return 0xFFDFE4A4; // end stone
        }

        // Overworld Palette
        if (n.contains("water") || n.contains("bubble_column")) return 0xFF3568C8;
        if (n.contains("grass") || n.contains("moss")) return 0xFF5B8C32;
        if (n.contains("stone") || n.contains("deepslate") || n.contains("andesite") || n.contains("diorite") || n.contains("granite")) return 0xFF6D6D6D;
        if (n.contains("dirt") || n.contains("podzol") || n.contains("farmland") || n.contains("path") || n.contains("mud")) return 0xFF79553A;
        if (n.contains("sand") || n.contains("sandstone")) return 0xFFD7C797;
        if (n.contains("leaves")) return 0xFF2D6914;
        if (n.contains("wood") || n.contains("log") || n.contains("planks") || n.contains("fence") || n.contains("door")) return 0xFF685332;
        if (n.contains("snow") || n.contains("powder_snow")) return 0xFFEEF2F5;
        if (n.contains("ice")) return 0xFF90B0FE;
        if (n.contains("lava")) return 0xFFD93A00;
        if (n.contains("gravel")) return 0xFF838082;
        if (n.contains("clay")) return 0xFF9FA4AE;
        if (n.contains("terracotta")) return 0xFF965D42;
        if (n.contains("wool") || n.contains("carpet")) return 0xFFC5C9CD;
        if (n.contains("copper")) return 0xFFC06D50;
        if (n.contains("iron") || n.contains("anvil")) return 0xFFD8D8D8;
        if (n.contains("glass")) return 0x80C5D8E8;
        if (n.contains("brick")) return 0xFF8B473B;

        return 0xFF4A6B2A; // fallback terrain green
    }

    @SuppressWarnings("unchecked")
    public static void renderRegion(File mcaFile, BufferedImage img, int imgOffsetX, int imgOffsetY, String dimension) {
        if (!mcaFile.exists()) return;

        try (RandomAccessFile raf = new RandomAccessFile(mcaFile, "r")) {
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    try {
                        int idx = 4 * (cx + cz * 32);
                        raf.seek(idx);
                        int offset = (raf.read() << 16) | (raf.read() << 8) | raf.read();
                        if (offset == 0) continue;

                        raf.seek(offset * 4096L);
                        int len = raf.readInt();
                        raf.readByte(); // skip compression type byte (2)
                        byte[] buf = new byte[len - 1];
                        raf.readFully(buf);

                        DataInputStream dis = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(buf)));
                        dis.readByte();
                        dis.readUTF();
                        Map<String, Object> root = (Map<String, Object>) readTag((byte) 10, dis);
                        Map<String, Object> heightmaps = (Map<String, Object>) root.get("Heightmaps");
                        if (heightmaps == null) continue;

                        long[] ws = (long[]) heightmaps.get("WORLD_SURFACE");
                        if (ws == null) {
                            ws = (long[]) heightmaps.get("MOTION_BLOCKING");
                        }
                        if (ws == null) continue;

                        int[] heights = new int[256];
                        int valIdx = 0;
                        for (long val : ws) {
                            for (int i = 0; i < 7 && valIdx < 256; i++) {
                                heights[valIdx++] = (int) ((val >>> (i * 9)) & 0x1FF) - 64;
                            }
                        }

                        Map<Integer, Map<String, Object>> sectionMap = new HashMap<>();
                        List<Object> sections = (List<Object>) root.get("sections");
                        if (sections != null) {
                            for (Object o : sections) {
                                Map<String, Object> s = (Map<String, Object>) o;
                                Object yObj = s.get("Y");
                                if (yObj instanceof Number) {
                                    sectionMap.put(((Number) yObj).intValue(), s);
                                }
                            }
                        }

                        for (int bz = 0; bz < 16; bz++) {
                            for (int bx = 0; bx < 16; bx++) {
                                int h = heights[bz * 16 + bx];
                                int secY = (h >> 4);
                                String blockName = "minecraft:grass_block";
                                Map<String, Object> sec = sectionMap.get(secY);
                                if (sec != null) {
                                    Map<String, Object> bs = (Map<String, Object>) sec.get("block_states");
                                    if (bs != null) {
                                        List<Object> palette = (List<Object>) bs.get("palette");
                                        if (palette != null && !palette.isEmpty()) {
                                            if (palette.size() == 1) {
                                                blockName = (String) ((Map<String, Object>) palette.get(0)).get("Name");
                                            } else {
                                                long[] data = (long[]) bs.get("data");
                                                if (data != null) {
                                                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                                                    int blockIdx = (h & 15) * 256 + bz * 16 + bx;
                                                    int perLong = 64 / bits;
                                                    int longIdx = blockIdx / perLong;
                                                    int offsetInLong = (blockIdx % perLong) * bits;
                                                    if (longIdx < data.length) {
                                                        int palIdx = (int) ((data[longIdx] >>> offsetInLong) & ((1 << bits) - 1));
                                                        if (palIdx < palette.size()) {
                                                            blockName = (String) ((Map<String, Object>) palette.get(palIdx)).get("Name");
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                int color = getBlockColor(blockName, dimension);

                                // Topographic hillshading
                                int prevH = bz > 0 ? heights[(bz - 1) * 16 + bx] : h;
                                if (h > prevH) {
                                    int r = Math.min(255, ((color >> 16) & 0xFF) + 18);
                                    int g = Math.min(255, ((color >> 8) & 0xFF) + 18);
                                    int b = Math.min(255, (color & 0xFF) + 18);
                                    color = (r << 16) | (g << 8) | b;
                                } else if (h < prevH) {
                                    int r = Math.max(0, ((color >> 16) & 0xFF) - 18);
                                    int g = Math.max(0, ((color >> 8) & 0xFF) - 18);
                                    int b = Math.max(0, (color & 0xFF) - 18);
                                    color = (r << 16) | (g << 8) | b;
                                }

                                int px = imgOffsetX + cx * 16 + bx;
                                int py = imgOffsetY + cz * 16 + bz;
                                if (px >= 0 && px < img.getWidth() && py >= 0 && py < img.getHeight()) {
                                    img.setRGB(px, py, color);
                                }
                            }
                        }
                    } catch (Throwable ignoredChunk) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public static Object readTag(byte type, DataInputStream dis) throws IOException {
        switch (type) {
            case 0: return null;
            case 1: return dis.readByte();
            case 2: return dis.readShort();
            case 3: return dis.readInt();
            case 4: return dis.readLong();
            case 5: return dis.readFloat();
            case 6: return dis.readDouble();
            case 7: {
                int len = dis.readInt();
                byte[] b = new byte[len];
                dis.readFully(b);
                return b;
            }
            case 8: return dis.readUTF();
            case 9: {
                byte elemType = dis.readByte();
                int len = dis.readInt();
                List<Object> list = new ArrayList<>(len);
                for (int i = 0; i < len; i++) list.add(readTag(elemType, dis));
                return list;
            }
            case 10: {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    byte tagType = dis.readByte();
                    if (tagType == 0) break;
                    String name = dis.readUTF();
                    map.put(name, readTag(tagType, dis));
                }
                return map;
            }
            case 11: {
                int len = dis.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readInt();
                return arr;
            }
            case 12: {
                int len = dis.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = dis.readLong();
                return arr;
            }
            default: throw new IOException("Unknown tag: " + type);
        }
    }
}

